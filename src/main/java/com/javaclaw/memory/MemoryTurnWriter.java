package com.javaclaw.memory;

import com.javaclaw.framework.api.RunId;
import com.javaclaw.memory.model.Episode;
import com.javaclaw.util.SensitiveDataRedactor;

/** Durable, idempotent raw projection; failed/cancelled turns never enter fact distillation. */
final class MemoryTurnWriter {
    private MemoryTurnWriter() {}
    static void remember(MemoryService memory, MemoryGraphScope scope, RunId runId, String turnId,
                         long sequence, String input, String reply, String trace, boolean reviewHabits,
                         String originThreadId, String originTurnId, String status) {
        if (scope.kind() != MemoryGraphScope.Kind.THREAD || turnId == null || turnId.isBlank())
            throw new IllegalArgumentException("会话投影需要有效的 Thread 和 Turn 身份");
        MemoryService selected = memory.inScope(scope);
        Episode episode = new Episode(scope.threadId(), input, reply);
        episode.turnId = turnId;
        episode.terminalStatus = status;
        episode.distilled = !"completed".equals(status);
        episode.ownerRunId = runId == null || episode.distilled ? null : runId.value();
        episode.originThreadId = originThreadId;
        episode.originTurnId = originTurnId;
        episode.sourceEventSequence = sequence;
        episode.habitEvidence = reviewHabits && !episode.distilled;
        episode.toolTraceJson = trace;
        selected.rememberEpisode(runId, episode, reviewHabits);
        if (!SensitiveDataRedactor.containsLikelyCredential(input)
                && !SensitiveDataRedactor.containsLikelyCredential(reply)
                && !SensitiveDataRedactor.containsLikelyCredential(trace)) {
            selected.store().withProjectionLock(() -> {
                if (selected.store().findTurn(turnId) == null)
                    throw new IllegalStateException("原文尚未可靠保存，不能确认记忆投影");
            });
        }
    }
}
