package com.javaclaw.service.api;

public interface ServicePluginContext {
    InternalServiceRegistry internalServices();
    ExternalEndpointRegistry externalEndpoints();
    DesktopServiceClient desktopServices();
    ManagedPluginExecutor executor();
    PluginResourceContext resources();
    PluginNetworkContext network();
    PluginConfig config();
    PluginLogger logger();
}
