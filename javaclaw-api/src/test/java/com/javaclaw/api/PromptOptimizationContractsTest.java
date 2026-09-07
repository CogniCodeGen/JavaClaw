package com.javaclaw.api;

import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PromptOptimizationContractsTest {
    private static final String DIGEST = "a".repeat(64);
    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");

    @Test
    void readyDraftRequiresContentDigestAndNewerAdoptedProfile() {
        PromptOptimizationRef ref = ref();
        PromptOptimizationResult result = new PromptOptimizationResult(
                PromptOptimizationState.READY, 3, Optional.of("简洁说明"), Optional.of(DIGEST), Optional.empty());
        PromptOptimizationDraft draft = new PromptOptimizationDraft(
                ref,
                result,
                new PromptOptimizationProvenance("role-optimization-v1", DIGEST, NOW, NOW),
                Optional.of(new AgentRoleRef("profile", 2)));

        assertEquals("简洁说明", draft.result().content().orElseThrow());
        assertThrows(
                IllegalArgumentException.class,
                () -> new PromptOptimizationResult(
                        PromptOptimizationState.READY, 1, Optional.empty(), Optional.empty(), Optional.empty()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new PromptOptimizationDraft(
                        ref, result, draft.provenance(), Optional.of(new AgentRoleRef("profile", 1))));
    }

    @Test
    void nonReadyDraftMustNotExposeContent() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new PromptOptimizationResult(
                        PromptOptimizationState.RUNNING,
                        2,
                        Optional.of("不应暴露"),
                        Optional.of(DIGEST),
                        Optional.empty()));
    }

    private static PromptOptimizationRef ref() {
        return new PromptOptimizationRef(
                PromptOptimizationId.parse("00000000-0000-0000-0000-000000000001"),
                WorkspaceId.parse("00000000-0000-0000-0000-000000000002"),
                new AgentRoleRef("profile", 1),
                ThreadId.parse("00000000-0000-0000-0000-000000000003"),
                TurnId.parse("00000000-0000-0000-0000-000000000004"));
    }
}
