package com.javaclaw.memory;

import com.javaclaw.util.SensitiveDataRedactor;
import com.javaclaw.memory.model.PreferenceProposal;
import java.nio.file.Path;

/** Promotes only structured claims backed by a committed, original user turn. */
final class MemoryPreferenceWriter {
    private MemoryPreferenceWriter() {}
    static void remember(MemoryService memory, MemoryGraphScope scope, Path sourceDirectory,
                         String turnId, PreferenceProposal proposal) {
        if (scope.kind() != MemoryGraphScope.Kind.THREAD || turnId == null
                || turnId.isBlank() || proposal == null) return;
        String text = proposal.sourceQuote() == null ? "" : proposal.sourceQuote().strip();
        if (text.isBlank() || text.length() > 500
                || proposal.confidence() < PreferenceProposal.MIN_CONFIDENCE
                || !Double.isFinite(proposal.confidence()) || proposal.confidence() > 1
                || SensitiveDataRedactor.containsLikelyCredential(text)) return;
        MemoryService source = memory.inScope(scope); // validate ownership and deletion
        MemoryService habits = memory.inScope(scope.habits());
        MemoryStoreRegistry.withLiveGraph(sourceDirectory, () -> {
            var episode = source.store().findTurn(turnId);
            if (episode == null || !episode.habitEvidence
                    || MemoryTurnStatus.fromStorageValue(episode.terminalStatus)
                            != MemoryTurnStatus.COMPLETED
                    || !scope.threadId().equals(episode.originThreadId)
                    || !turnId.equals(episode.originTurnId)
                    || episode.userInput == null || !episode.userInput.contains(text)
                    || SensitiveDataRedactor.containsLikelyCredential(episode.userInput)) return;
            String evidenceKey = episode.evidenceKey();
            if (habits.facts().stream().anyMatch(f -> text.equals(f.text)
                    && !f.superseded && !f.contested)) return;
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
