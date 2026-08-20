package com.javaclaw.service.api;

/** Entry point loaded only by the isolated service-plugin Runner. */
public interface ServicePlugin {
    void start(ServicePluginContext context) throws Exception;
    void stop() throws Exception;
}
