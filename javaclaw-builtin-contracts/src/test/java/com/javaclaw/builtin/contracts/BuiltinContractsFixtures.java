package com.javaclaw.builtin.contracts;

import java.time.Duration;
import java.time.Instant;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.TurnBudget;

final class BuiltinContractsFixtures {
    static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");
    static final String DIGEST = "a".repeat(64);

    private BuiltinContractsFixtures() {}

    static CanonicalPayload payload() {
        return new CanonicalPayload("{\"value\":1}");
    }

    static TurnBudget budget() {
        return new TurnBudget(100, 50, 4, 2, Duration.ofMinutes(1));
    }
}
