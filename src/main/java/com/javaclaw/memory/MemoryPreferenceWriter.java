package com.javaclaw.memory;

import com.javaclaw.util.SensitiveDataRedactor;
import java.nio.file.Path;

/** Deterministic promotion of explicit user preferences, fenced by their source graph. */
final class MemoryPreferenceWriter {
    private MemoryPreferenceWriter() {}
    static void remember(MemoryService memory, MemoryGraphScope scope, Path sourceDirectory,
                         String turnId, String input) {
        if (scope.kind() != MemoryGraphScope.Kind.THREAD || input == null
                || SensitiveDataRedactor.containsLikelyCredential(input)) return;
        memory.inScope(scope); // validate ownership and reject deleted source threads
        String evidenceKey = scope.threadId() + ":" + turnId;
        for (String clause : input.split("[。！!?？\\n]")) {
            String text = clause.strip();
            if (!text.matches("^(?:请?记住[：:,，]?\\s*我的(?:偏好|习惯).*|我(?:更|一直|通常)?(?:喜欢|偏好|习惯)(?!问|知道).*|以后(?:请)?(?:都|默认)(?:用|使用|以).*)")) continue;
            if (text.length() > 500) continue;
            MemoryService habits = memory.inScope(scope.habits());
            MemoryStoreRegistry.withLiveGraph(sourceDirectory, () -> {
                if (habits.facts().stream().anyMatch(f -> text.equals(f.text) && !f.superseded && !f.contested)) return;
                var fact = new com.javaclaw.memory.model.Fact("明确偏好", text, null);
                fact.id = java.util.UUID.nameUUIDFromBytes((evidenceKey + "\n" + text)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
                fact.userAsserted = true;
                fact.sourceKind = "USER_EXPLICIT_PREFERENCE";
                fact.evidenceKeys.add(evidenceKey);
                habits.store().addPendingFact(fact, "user.preference");
            });
        }
    }
}
