package com.javaclaw.memory.model;

/** A model-proposed explicit preference, anchored to an exact span of a committed user turn. */
public record PreferenceProposal(String sourceQuote, double confidence) {
    public static final double MIN_CONFIDENCE = 0.82;
}
