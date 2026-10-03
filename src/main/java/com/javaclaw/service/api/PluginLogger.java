package com.javaclaw.service.api;

public interface PluginLogger {
    enum Kind { INFERENCE_INVOCATION }

    void debug(String message);
    void info(String message);
    default void info(Kind kind, String message) { info(message); }
    void warn(String message);
    void error(String message, Throwable failure);
}
