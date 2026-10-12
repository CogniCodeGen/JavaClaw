package com.javaclaw.agent.vision;

/**
 * A requested condition supported by a literal excerpt in the visible main content.
 * The host binds this evidence to a successfully committed frame before using it.
 */
public record DesktopVisualConditionEvidence(
        String criterionId, String subject, DesktopVisualTarget content, double confidence) { }
