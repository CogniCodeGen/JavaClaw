package com.javaclaw.application.inference;

/** A service-plugin log with a category supplied independently of its display text. */
public record InferenceLogEntry(Kind kind, String text) {
    public enum Kind { RUNTIME, INFERENCE_INVOCATION }

    public InferenceLogEntry {
        kind = kind == null ? Kind.RUNTIME : kind;
        text = text == null ? "" : text;
    }
}
