package io.quarkiverse.helm.deployment;

import static io.github.yamlpath.utils.StringUtils.EMPTY;
import static io.quarkiverse.helm.deployment.HelmChartUploader.pushToHelmRepository;
import static io.quarkiverse.helm.deployment.utils.SystemPropertiesUtils.getPropertyFromSystem;
import static io.quarkiverse.helm.deployment.utils.SystemPropertiesUtils.getSystemProperties;
import static io.quarkiverse.helm.deployment.utils.SystemPropertiesUtils.hasSystemProperties;
import static io.quarkus.deployment.Capability.OPENSHIFT;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Scanner;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.config.ConfigValue;
import org.jboss.logging.Logger;

import io.dekorate.ConfigReference;
import io.dekorate.Session;
import io.dekorate.helm.config.HelmChartConfigBuilder;
import io.dekorate.helm.config.HelmDependencyBuilder;
import io.dekorate.helm.config.ValuesSchema;
import io.dekorate.helm.config.ValuesSchemaBuilder;
import io.dekorate.helm.config.ValuesSchemaProperty;
import io.dekorate.helm.config.ValuesSchemaPropertyBuilder;
import io.dekorate.kubernetes.config.ContainerBuilder;
import io.dekorate.kubernetes.decorator.AddInitContainerDecorator;
import io.dekorate.project.Project;
import io.dekorate.utils.Strings;
import io.quarkiverse.helm.deployment.decorators.LowPriorityAddEnvVarDecorator;
import io.quarkiverse.helm.deployment.utils.HelmConfigUtils;
import io.quarkus.deployment.Capabilities;
import io.quarkus.deployment.IsNormal;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.ApplicationInfoBuildItem;
import io.quarkus.deployment.pkg.builditem.ArtifactResultBuildItem;
import io.quarkus.deployment.pkg.builditem.OutputTargetBuildItem;
import io.quarkus.deployment.util.FileUtil;
import io.quarkus.kubernetes.spi.ConfiguratorBuildItem;
import io.quarkus.kubernetes.spi.DecoratorBuildItem;
import io.quarkus.kubernetes.spi.DekorateOutputBuildItem;
import io.quarkus.kubernetes.spi.GeneratedKubernetesResourceBuildItem;

public class HelmProcessor {
    private static final Logger LOGGER = Logger.getLogger(HelmProcessor.class);

    private static final String NAME_FORMAT_REG_EXP = "[a-z0-9]([-a-z0-9]*[a-z0-9])?(\\.[a-z0-9]([-a-z0-9]*[a-z0-9])?)*";
    private static final List<String> HELM_INVALID_CHARACTERS = Arrays.asList("-");
    private static final String BUILD_TIME_PROPERTIES = "/build-time-list";
    private static final String INIT_CONTAINER_CONDITION_FORMAT = "$(env | grep %s | grep -q false) && exit 0; %s";

    private static final String QUARKUS_KUBERNETES_NAME = "quarkus.kubernetes.name";
    private static final String QUARKUS_KNATIVE_NAME = "quarkus.knative.name";
    private static final String QUARKUS_OPENSHIFT_NAME = "quarkus.openshift.name";
    private static final String QUARKUS_CONTAINER_IMAGE_NAME = "quarkus.container-image.name";
    private static final String SERVICE_NAME_PLACEHOLDER = "::service-name";
    private static final String SERVICE_PORT_PLACEHOLDER = "::service-port";
    private static final String SPLIT = ":";
    private static final String PROPERTIES_CONFIG_SOURCE = "PropertiesConfigSource";
    // Lazy loaded when calling `isBuildTimeProperty(xxx)`.
    private static Set<String> buildProperties;

    @BuildStep(onlyIf = { HelmEnabled.class, IsNormal.class })
    void mapSystemPropertiesIfEnabled(Capabilities capabilities, ApplicationInfoBuildItem info, HelmChartConfig helmConfig,
            BuildProducer<DecoratorBuildItem> decorators) {
        if (helmConfig.mapSystemProperties) {
            String deploymentName = getDeploymentName(capabilities, info);
            Config config = ConfigProvider.getConfig();
            Map<String, String> propertiesFromConfigSource = new HashMap<>();
            for (String propName : config.getPropertyNames()) {
                ConfigValue propValue = config.getConfigValue(propName);
                if (isPropertiesConfigSource(propValue.getSourceName())) {
                    propertiesFromConfigSource.put(propName, propValue.getRawValue());
                }
            }

            for (Map.Entry<String, String> entry : propertiesFromConfigSource.entrySet()) {
                if (!isBuildTimeProperty(entry.getKey())) {
                    mapProperty(deploymentName, decorators, entry.getValue(), propertiesFromConfigSource);
                }
            }
        }
    }

    @BuildStep(onlyIf = { HelmEnabled.class, IsNormal.class })
    void configureHelmDependencyOrder(Capabilities capabilities, ApplicationInfoBuildItem info, HelmChartConfig config,
            BuildProducer<DecoratorBuildItem> decorators) {
        if (config.dependencies == null || config.dependencies.isEmpty()) {
            return;
        }

        String deploymentName = getDeploymentName(capabilities, info);

        for (Map.Entry<String, HelmDependencyConfig> entry : config.dependencies.entrySet()) {
            HelmDependencyConfig dependency = entry.getValue();
            if (dependency.waitForService.isPresent()) {
                String containerName = "wait-for-" + defaultString(dependency.name, entry.getKey());
                ContainerBuilder container = new ContainerBuilder()
                        .withName(containerName)
                        .withImage(dependency.waitForServiceImage)
                        .withCommand("sh");

                String argument = null;

                String service = dependency.waitForService.get();
                if (service.contains(SPLIT)) {
                    // it's service name and service port
                    String[] parts = service.split(SPLIT);
                    String serviceName = parts[0];
                    String servicePort = parts[1];
                    argument = dependency.waitForServicePortCommandTemplate
                            .replaceAll(SERVICE_NAME_PLACEHOLDER, serviceName)
                            .replaceAll(SERVICE_PORT_PLACEHOLDER, servicePort);

                } else {
                    argument = dependency.waitForServiceOnlyCommandTemplate
                            .replaceAll(SERVICE_NAME_PLACEHOLDER, service);
                }

                // if the condition is set, we need to map it as env property as well
                if (dependency.condition.isPresent()) {
                    String property = HelmConfigUtils.deductProperty(config, dependency.condition.get());
                    decorators.produce(new DecoratorBuildItem(
                            new LowPriorityAddEnvVarDecorator(deploymentName, containerName, property, "true")));

                    argument = String.format(INIT_CONTAINER_CONDITION_FORMAT, property, argument);
                }

                decorators.produce(new DecoratorBuildItem(
                        new AddInitContainerDecorator(deploymentName, container.withArguments("-c", argument).build())));
            }
        }
    }

    @BuildStep(onlyIf = { HelmEnabled.class, IsNormal.class })
    void generateResources(ApplicationInfoBuildItem app, OutputTargetBuildItem outputTarget,
            Optional<DekorateOutputBuildItem> dekorateOutput,
            List<GeneratedKubernetesResourceBuildItem> generatedResources,
            // this is added to ensure that the build step will be run
            BuildProducer<ArtifactResultBuildItem> dummy,
            HelmChartConfig config) {
        if (dekorateOutput.isPresent()) {
            doGenerateResources(app, outputTarget, dekorateOutput.get(), generatedResources, config);
        } else if (config.enabled) {
            LOGGER.warn("Quarkus Helm extension is skipped since no Quarkus Kubernetes extension is configured. ");
        }
    }

    @BuildStep
    void disableDefaultHelmListener(BuildProducer<ConfiguratorBuildItem> helmConfiguration) {
        helmConfiguration.produce(new ConfiguratorBuildItem(new DisableDefaultHelmListener()));
    }

    private void doGenerateResources(ApplicationInfoBuildItem app, OutputTargetBuildItem outputTarget,
            DekorateOutputBuildItem dekorateOutput,
            List<GeneratedKubernetesResourceBuildItem> generatedResources,
            HelmChartConfig config) {
        validate(config);
        Project project = (Project) dekorateOutput.getProject();

        // Deduct folders
        Path inputFolder = getInputDirectory(config, project);
        Path outputFolder = getOutputDirectory(config, outputTarget);

        // Dekorate session writer
        final QuarkusHelmWriterSessionListener helmWriter = new QuarkusHelmWriterSessionListener();
        final Map<String, Set<File>> deploymentTargets = toDeploymentTargets(dekorateOutput.getGeneratedFiles(),
                generatedResources);

        // Config
        io.dekorate.helm.config.HelmChartConfig dekorateHelmChartConfig = toDekorateHelmChartConfig(app, config);
        List<ConfigReference> valueReferencesFromUser = toValueReferences(config);

        // Deduct deployment target to push
        String deploymentTargetToPush = deductDeploymentTarget(config, deploymentTargets);

        // separate generated helm charts into the deployment targets
        for (Map.Entry<String, Set<File>> filesInDeploymentTarget : deploymentTargets.entrySet()) {
            String deploymentTarget = filesInDeploymentTarget.getKey();
            Path chartOutputFolder = outputFolder.resolve(deploymentTarget);
            deleteOutputHelmFolderIfExists(chartOutputFolder);

            Map<String, String> generated = helmWriter.writeHelmFiles(project,
                    dekorateHelmChartConfig,
                    valueReferencesFromUser,
                    getConfigReferencesFromSession(deploymentTarget, dekorateOutput),
                    inputFolder,
                    chartOutputFolder,
                    filesInDeploymentTarget.getValue(),
                    config.valuesProfileSeparator);

            // Push to Helm repository if enabled
            if (config.repository.push && deploymentTargetToPush.equals(deploymentTarget)) {
                String tarball = generated.keySet().stream()
                        .filter(file -> file.endsWith(config.extension))
                        .findFirst()
                        .orElseThrow(() -> new RuntimeException("Couldn't find the tarball file. There should have "
                                + "been generated when pushing to a Helm repository is enabled."));
                pushToHelmRepository(new File(tarball), config.repository);
            }
        }
    }

    private void validate(HelmChartConfig config) {
        if (config.name.isPresent()) {
            if (!config.name.get().matches(NAME_FORMAT_REG_EXP)) {
                throw new IllegalStateException(String.format("Wrong name '%s'. Regular expression used for validation "
                        + "is '%s'", config.name.get(), NAME_FORMAT_REG_EXP));
            }
        }

        for (Map.Entry<String, AddIfStatementConfig> addIfStatement : config.addIfStatement.entrySet()) {
            String name = addIfStatement.getValue().property.orElse(addIfStatement.getKey());
            if (addIfStatement.getValue().onResourceKind.isEmpty() && addIfStatement.getValue().onResourceName.isEmpty()) {
                throw new IllegalStateException(String.format("Either 'quarkus.helm.add-if-statement.%s.on-resource-kind' "
                        + "or 'quarkus.helm.add-if-statement.%s.on-resource-kind' must be provided.",
                        addIfStatement.getKey(), addIfStatement.getKey()));
            }

            if (!config.disableNamingValidation && HELM_INVALID_CHARACTERS.stream().anyMatch(name::contains)) {
                throw new RuntimeException(
                        String.format("The property of the `add-if-statement` '%s' is invalid. Can't use '-' characters."
                                + "You can disable the naming validation using "
                                + "`quarkus.helm.disable-naming-validation=true`", name));
            }
        }

        if (!config.disableNamingValidation) {
            for (Map.Entry<String, HelmDependencyConfig> dependency : config.dependencies.entrySet()) {
                String name = dependency.getValue().name.orElse(dependency.getKey());
                if (dependency.getValue().condition.isPresent()
                        && HELM_INVALID_CHARACTERS.stream().anyMatch(dependency.getValue().condition.get()::contains)) {
                    throw new RuntimeException(
                            String.format("Condition of the dependency '%s' is invalid. Can't use '-' characters."
                                    + "You can disable the naming validation using "
                                    + "`quarkus.helm.disable-naming-validation=true`", name));
                }
            }

            for (Map.Entry<String, ValueReferenceConfig> value : config.values.entrySet()) {
                String name = value.getValue().property.orElse(value.getKey());
                if (HELM_INVALID_CHARACTERS.stream().anyMatch(name::contains)) {
                    throw new RuntimeException(
                            String.format("Property of the value '%s' is invalid. Can't use '-' characters."
                                    + "You can disable the naming validation using "
                                    + "`quarkus.helm.disable-naming-validation=true`", name));
                }
            }
        }
    }

    private String deductDeploymentTarget(HelmChartConfig config, Map<String, Set<File>> deploymentTargets) {
        if (config.repository.push) {
            // if enabled, use the deployment target from the user if set
            if (config.repository.deploymentTarget.isPresent()) {
                return config.repository.deploymentTarget.get();
            } else {
                List<String> deploymentTargetNames = deploymentTargets.keySet().stream().collect(Collectors.toList());
                if (deploymentTargetNames.size() == 1) {
                    return deploymentTargetNames.get(0);
                } else {
                    throw new IllegalStateException("Multiple deployment target found: '"
                            + deploymentTargetNames.stream().collect(
                                    Collectors.joining(", "))
                            + "'. To push the Helm Chart to the repository, "
                            + "you need to select only one using the property `quarkus.helm.repository.deployment-target`");
                }
            }
        }

        return null;
    }

    private void deleteOutputHelmFolderIfExists(Path outputFolder) {
        try {
            FileUtil.deleteIfExists(outputFolder);
        } catch (IOException ignored) {

        }
    }

    private Path getInputDirectory(HelmChartConfig config, Project project) {
        Path path = Paths.get(config.inputDirectory);
        if (!path.isAbsolute()) {
            return project.getRoot().resolve(path);
        }

        return path;
    }

    private Path getOutputDirectory(HelmChartConfig config, OutputTargetBuildItem outputTarget) {
        Path path = Paths.get(config.outputDirectory);
        if (!path.isAbsolute()) {
            return outputTarget.getOutputDirectory().resolve(path);
        }

        return path;
    }

    private Map<String, Set<File>> toDeploymentTargets(List<String> generatedFiles,
            List<GeneratedKubernetesResourceBuildItem> generatedResources) {
        Map<String, Set<File>> filesByDeploymentTarget = new HashMap<>();
        for (String generatedFile : generatedFiles) {
            if (generatedFile.toLowerCase(Locale.ROOT).endsWith(".json")) {
                // skip json files
                continue;
            }

            File file = new File(generatedFile);
            String deploymentTarget = file.getName().substring(0, file.getName().indexOf("."));
            if (filesByDeploymentTarget.containsKey(deploymentTarget)) {
                // It's already included.
                continue;
            }

            Set<File> files = new HashSet<>();
            if (!file.exists()) {
                Optional<byte[]> content = generatedResources.stream()
                        .filter(resource -> file.getName().equals(resource.getName()))
                        .map(GeneratedKubernetesResourceBuildItem::getContent)
                        .findFirst();
                if (content.isPresent()) {
                    // The dekorate output generated files are sometimes not persisted yet, so we need to workaround it by
                    // creating a temp file with the content from generatedResources.
                    try {
                        File tempFile = File.createTempFile("tmp", file.getName());
                        tempFile.deleteOnExit();
                        Files.write(tempFile.toPath(), content.get());
                        files.add(tempFile);
                    } catch (IOException ignored) {
                        // if we could not create the temp file, we add the one from
                        files.add(file);
                    }
                }
            } else {
                files.add(file);
            }

            filesByDeploymentTarget.put(deploymentTarget, files);
        }

        return filesByDeploymentTarget;
    }

    private io.dekorate.helm.config.HelmChartConfig toDekorateHelmChartConfig(ApplicationInfoBuildItem app,
            HelmChartConfig config) {
        HelmChartConfigBuilder builder = new HelmChartConfigBuilder()
                .withEnabled(config.enabled)
                .withApiVersion(config.apiVersion)
                .withName(config.name.orElse(app.getName()))
                .withCreateTarFile(config.createTarFile || config.repository.push)
                .withCreateValuesSchemaFile(config.createValuesSchemaFile)
                .withCreateReadmeFile(config.createReadmeFile)
                .withVersion(config.version.orElse(app.getVersion()))
                .withExtension(config.extension)
                .withValuesRootAlias(config.valuesRootAlias)
                .withNotes(config.notes);
        config.description.ifPresent(builder::withDescription);
        config.keywords.ifPresent(builder::addAllToKeywords);
        config.icon.ifPresent(builder::withIcon);
        config.condition.ifPresent(builder::withCondition);
        config.tags.ifPresent(builder::withTags);
        config.appVersion.ifPresent(builder::withAppVersion);
        config.deprecated.ifPresent(builder::withDeprecated);
        config.annotations.entrySet().forEach(e -> builder.addNewAnnotation(e.getKey(), e.getValue()));
        config.kubeVersion.ifPresent(builder::withKubeVersion);
        config.type.ifPresent(builder::withType);
        config.home.ifPresent(builder::withHome);
        config.sources.ifPresent(builder::addAllToSources);
        config.maintainers.entrySet()
                .forEach(e -> builder.addNewMaintainer(
                        defaultString(e.getValue().name, e.getKey()),
                        defaultString(e.getValue().email),
                        defaultString(e.getValue().url)));
        config.dependencies.entrySet()
                .forEach(e -> builder.addToDependencies(toDekorateHelmDependencyConfig(e.getKey(), e.getValue())));
        config.tarFileClassifier.ifPresent(builder::withTarFileClassifier);
        config.expressions.values().forEach(e -> builder.addNewExpression(e.path, e.expression));
        config.addIfStatement.entrySet()
                .forEach(e -> {
                    builder.addNewAddIfStatement(
                            defaultString(e.getValue().property, e.getKey()),
                            defaultString(e.getValue().onResourceKind),
                            defaultString(e.getValue().onResourceName),
                            e.getValue().withDefaultValue,
                            e.getValue().description);
                });

        builder.withValuesSchema(toValuesSchema(config.valuesSchema));

        return builder.build();
    }

    private ValuesSchema toValuesSchema(ValuesSchemaConfig valuesSchema) {
        List<ValuesSchemaProperty> properties = new ArrayList<>();
        for (Map.Entry<String, ValuesSchemaPropertyConfig> property : valuesSchema.properties.entrySet()) {
            String name = property.getValue().name.orElse(property.getKey());

            properties.add(new ValuesSchemaPropertyBuilder()
                    .withName(name)
                    .withType(property.getValue().type)
                    .withDescription(defaultString(property.getValue().description))
                    .withMaximum(property.getValue().maximum.orElse(Integer.MAX_VALUE))
                    .withMinimum(property.getValue().minimum.orElse(Integer.MIN_VALUE))
                    .withRequired(property.getValue().required)
                    .withPattern(defaultString(property.getValue().pattern))
                    .build());
        }

        return new ValuesSchemaBuilder()
                .withTitle(valuesSchema.title)
                .withProperties(properties.toArray(new ValuesSchemaProperty[0]))
                .build();
    }

    private io.dekorate.helm.config.HelmDependency toDekorateHelmDependencyConfig(String dependencyName,
            HelmDependencyConfig dependency) {
        HelmDependencyBuilder builder = new HelmDependencyBuilder()
                .withName(defaultString(dependency.name, dependencyName))
                .withAlias(defaultString(dependency.alias, defaultString(dependency.name, dependencyName)))
                .withVersion(dependency.version)
                .withRepository(dependency.repository)
                .withCondition(defaultString(dependency.condition))
                .withTags(defaultArray(dependency.tags))
                .withEnabled(dependency.enabled.orElse(true));

        return builder.build();
    }

    private List<ConfigReference> toValueReferences(HelmChartConfig config) {
        return config.values.entrySet().stream()
                .map(e -> new ConfigReference.Builder(defaultString(e.getValue().property, e.getKey()),
                        defaultArray(e.getValue().paths))
                        .withValue(toValue(e.getValue()))
                        .withDescription(defaultString(e.getValue().description, EMPTY))
                        .withExpression(defaultString(e.getValue().expression))
                        .withProfile(defaultString(e.getValue().profile))
                        .withRequired(e.getValue().required)
                        .withPattern(defaultString(e.getValue().pattern))
                        .withMaximum(e.getValue().maximum.orElse(Integer.MAX_VALUE))
                        .withMinimum(e.getValue().minimum.orElse(Integer.MIN_VALUE))
                        .build())
                .collect(Collectors.toList());
    }

    private Object toValue(ValueReferenceConfig v) {
        if (v.valueAsInt.isPresent()) {
            return v.valueAsInt.get();
        } else if (v.valueAsBool.isPresent()) {
            return v.valueAsBool.get();
        } else if (!v.valueAsMap.isEmpty()) {
            return v.valueAsMap;
        } else if (v.valueAsList.isPresent()) {
            return v.valueAsList.get();
        }

        return v.value.orElse(null);
    }

    private String defaultString(Optional<String> value) {
        return defaultString(value, null);
    }

    private String defaultString(Optional<String> value, String defaultStr) {
        if (value.isEmpty() || StringUtils.isEmpty(value.get())) {
            return defaultStr;
        }

        return value.get();
    }

    private static String[] defaultArray(Optional<List<String>> optional) {
        return optional.map(l -> l.toArray(new String[0])).orElse(new String[0]);
    }

    private String mapProperty(String deploymentName, BuildProducer<DecoratorBuildItem> decorators, String property,
            Map<String, String> propertiesFromConfigSource) {
        if (!hasSystemProperties(property)) {
            return property;
        }

        String lastPropertyValue = property;
        for (String systemProperty : getSystemProperties(property)) {
            String defaultValue = EMPTY;
            if (systemProperty.contains(SPLIT)) {
                int splitPosition = systemProperty.indexOf(SPLIT);
                defaultValue = systemProperty.substring(splitPosition + SPLIT.length());
                systemProperty = systemProperty.substring(0, splitPosition);

                if (hasSystemProperties(defaultValue)) {
                    defaultValue = mapProperty(deploymentName, decorators, defaultValue, propertiesFromConfigSource);
                }
            }

            // Incorporate if and only if the system property name is valid in Helm and it's not already defined in the
            // application properties.
            if (!propertiesFromConfigSource.containsKey(systemProperty)
                    && HELM_INVALID_CHARACTERS.stream().noneMatch(systemProperty::contains)) {
                // Check whether the system property is provided:
                defaultValue = getPropertyFromSystem(systemProperty, defaultValue);

                decorators.produce(new DecoratorBuildItem(
                        new LowPriorityAddEnvVarDecorator(deploymentName, systemProperty, defaultValue)));

                lastPropertyValue = defaultValue;
            }
        }

        return lastPropertyValue;
    }

    public static String getDeploymentName(Capabilities capabilities, ApplicationInfoBuildItem info) {
        Config config = ConfigProvider.getConfig();
        Optional<String> resourceName;
        if (capabilities.isPresent(OPENSHIFT)) {
            resourceName = config.getOptionalValue(QUARKUS_OPENSHIFT_NAME, String.class);
        } else {
            resourceName = config.getOptionalValue(QUARKUS_KNATIVE_NAME, String.class)
                    .or(() -> config.getOptionalValue(QUARKUS_KUBERNETES_NAME, String.class));
        }

        return resourceName
                .or(() -> config.getOptionalValue(QUARKUS_CONTAINER_IMAGE_NAME, String.class))
                .orElse(info.getName());
    }

    private boolean isPropertiesConfigSource(String sourceName) {
        return Strings.isNotNullOrEmpty(sourceName) && sourceName.startsWith(PROPERTIES_CONFIG_SOURCE);
    }

    private boolean isBuildTimeProperty(String name) {
        if (buildProperties == null) {
            buildProperties = new HashSet<>();
            try {
                Scanner scanner = new Scanner(new File(HelmProcessor.class.getResource(BUILD_TIME_PROPERTIES).getFile()));
                while (scanner.hasNextLine()) {
                    buildProperties.add(scanner.nextLine());
                }
            } catch (Exception e) {
                LOGGER.debugf("Can't read the build time properties file at '%s'. Caused by: %s",
                        BUILD_TIME_PROPERTIES,
                        e.getMessage());
            }
        }

        return buildProperties.stream().anyMatch(build -> name.matches(build) // It's a regular expression
                || (build.endsWith(".") && name.startsWith(build)) // contains with
                || name.equals(build)); // or it's equal to
    }

    private List<ConfigReference> getConfigReferencesFromSession(String deploymentTarget,
            DekorateOutputBuildItem dekorateOutput) {
        List<ConfigReference> configReferencesFromDecorators = ((Session) dekorateOutput.getSession())
                .getResourceRegistry()
                .getConfigReferences(deploymentTarget)
                .stream()
                .flatMap(decorator -> decorator.getConfigReferences().stream())
                .collect(Collectors.toList());

        Collections.reverse(configReferencesFromDecorators);
        return configReferencesFromDecorators;
    }
}
