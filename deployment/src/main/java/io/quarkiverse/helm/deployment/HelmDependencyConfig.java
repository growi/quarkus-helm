package io.quarkiverse.helm.deployment;

import java.util.List;
import java.util.Optional;

import io.quarkus.runtime.annotations.ConfigGroup;
import io.quarkus.runtime.annotations.ConfigItem;

@ConfigGroup
public class HelmDependencyConfig {
    /**
     * Name of the dependency.
     */
    @ConfigItem
    public Optional<String> name;

    /**
     * Version of the dependency.
     */
    @ConfigItem
    public String version;

    /**
     * Repository of the dependency.
     */
    @ConfigItem
    public String repository;

    /**
     * Dependency condition. If the property starts with `@.`, then the property won't be added under the root element in the
     * generated `values.yaml` file.
     */
    @ConfigItem
    public Optional<String> condition;

    /**
     * Dependency tags.
     */
    @ConfigItem
    public Optional<List<String>> tags;

    /**
     * Whether this dependency should be loaded.
     */
    @ConfigItem
    public Optional<Boolean> enabled;

    /**
     * Alias of the dependency.
     */
    @ConfigItem
    public Optional<String> alias;

    /**
     * Instruct the application to wait for the service that should be installed as part of this Helm dependency.
     * You can set only a service name or a combination of a service name plus the service port (service:port).
     */
    @ConfigItem
    public Optional<String> waitForService;

    /**
     * If wait for service is set, it will use this image to configure the init-containers within the deployment resource.
     */
    @ConfigItem(defaultValue = "busybox:1.34.1")
    public String waitForServiceImage;

    /**
     * If wait for service is set, it will use this command to run the init-containers within the deployment resource.
     */
    @ConfigItem(defaultValue = "for i in $(seq 1 200); do nc -z -w3 ::service-name ::service-port && exit 0; done; exit 1")
    public String waitForServicePortCommandTemplate;

    /**
     * If wait for service is set, it will use this command to run the init-containers within the deployment resource.
     */
    @ConfigItem(defaultValue = "until nslookup ::service-name; do echo waiting for service; sleep 2; done")
    public String waitForServiceOnlyCommandTemplate;
}
