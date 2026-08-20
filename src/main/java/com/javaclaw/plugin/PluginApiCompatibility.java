package com.javaclaw.plugin;

import com.javaclaw.plugin.api.PluginDescriptor;

/** Centralizes compatibility checks for both in-process and service plugin descriptors. */
final class PluginApiCompatibility {

    private PluginApiCompatibility() { }

    static boolean isCompatible(PluginDescriptor descriptor) {
        return descriptor.pluginType() == PluginDescriptor.PluginType.SERVICE_PLUGIN
                ? major(descriptor.service().apiVersion()).equals("1")
                : isInProcessCompatible(descriptor.apiVersion());
    }

    static boolean isInProcessCompatible(String version) {
        return major(PluginDescriptor.HOST_API_VERSION).equals(major(version));
    }

    private static String major(String version) {
        if (version == null || version.isBlank()) return "";
        int dot = version.indexOf('.');
        return dot < 0 ? version.strip() : version.substring(0, dot).strip();
    }
}
