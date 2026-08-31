package com.javaclaw.agent.model;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.javaclaw.core.api.ApprovalPolicy;
import com.javaclaw.core.api.TurnConfig;
import com.javaclaw.sandbox.api.SandboxPolicy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CompactionThresholdsTest {
    @Test
    void disablesUnknownAndExplicitZeroWindowsAndDefaultsKnownWindowToEightyPercent() {
        assertFalse(CompactionThresholds.autoThreshold(config(Map.of())).isPresent());
        assertEquals(
                800,
                CompactionThresholds.autoThreshold(config(Map.of("modelContextWindowTokens", "1000")))
                        .orElseThrow());
        assertFalse(CompactionThresholds.autoThreshold(
                        config(Map.of("modelContextWindowTokens", "1000", "modelAutoCompactTokenLimit", "0")))
                .isPresent());
        assertEquals(
                713,
                CompactionThresholds.autoThreshold(
                                config(Map.of("modelContextWindowTokens", "1000", "modelAutoCompactTokenLimit", "713")))
                        .orElseThrow());
        assertFalse(CompactionThresholds.autoThreshold(config(Map.of("modelAutoCompactTokenLimit", "500")))
                .isPresent());
    }

    @Test
    void rejectsInvalidThresholdsWithoutMaintainingAModelWindowTable() {
        assertThrows(
                IllegalArgumentException.class,
                () -> CompactionThresholds.validate(Map.of("modelContextWindowTokens", "0")));
        assertThrows(
                IllegalArgumentException.class,
                () -> CompactionThresholds.validate(
                        Map.of("modelContextWindowTokens", "1000", "modelAutoCompactTokenLimit", "1000")));
        assertThrows(
                IllegalArgumentException.class,
                () -> CompactionThresholds.validate(Map.of("modelAutoCompactTokenLimit", "-1")));
        assertThrows(
                IllegalArgumentException.class,
                () -> CompactionThresholds.validate(Map.of("modelContextWindowTokens", "unknown")));
        long threshold = CompactionThresholds.autoThreshold(
                        config(Map.of("modelContextWindowTokens", Long.toString(Long.MAX_VALUE))))
                .orElseThrow();
        assertEquals((Long.MAX_VALUE / 5) * 4 + ((Long.MAX_VALUE % 5) * 4) / 5, threshold);
    }

    private static TurnConfig config(Map<String, String> attributes) {
        Path workingDirectory = Path.of(".").toAbsolutePath().normalize();
        return new TurnConfig(
                "model",
                "provider",
                "",
                workingDirectory,
                SandboxPolicy.readOnly(Set.of(workingDirectory), Set.of()),
                ApprovalPolicy.NEVER,
                Set.of(),
                attributes);
    }
}
