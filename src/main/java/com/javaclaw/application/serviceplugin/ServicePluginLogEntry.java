package com.javaclaw.application.serviceplugin;

/** A service-plugin log with a category supplied independently of its display text. */
public record ServicePluginLogEntry(Kind kind, String text) {
    public enum Kind { RUNTIME, INFERENCE_INVOCATION }

    public ServicePluginLogEntry {
        kind = kind == null ? Kind.RUNTIME : kind;
        text = text == null ? "" : text;
    }
}
