package com.javaclaw.plugin;

import com.javaclaw.plugin.api.PluginDescriptor;

import java.nio.file.Path;

/**
 * Host-side static contribution hook for verified service-plugin descriptors.
 * Implementations receive metadata only; this hook never loads plugin classes in Desktop.
 */
public interface ServicePluginContributionRegistry {

    ServicePluginContributionRegistry NOOP = new ServicePluginContributionRegistry() {
        @Override public void register(
                PluginDescriptor descriptor, Path pluginJar, String artifactSha256) { }
        @Override public void unregister(String pluginId) { }
    };

    void register(PluginDescriptor descriptor, Path pluginJar, String artifactSha256)
            throws Exception;

    void unregister(String pluginId) throws Exception;
}
