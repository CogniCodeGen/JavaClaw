package com.javaclaw.plugins.deliverance.internal;


public interface ServicePlugin {
    void start(ServicePluginContext context) throws Exception;
    void stop() throws Exception;
}
