package com.javaclaw.memory;

import com.javaclaw.memory.model.Episode;
import java.nio.file.Path;

/** Raw-only snapshot copying for callers without a graph mutation journal. */
final class MemoryForkSnapshots {
    private MemoryForkSnapshots() {}
    static void copy(MemoryService memory, Path root, MemoryGraphScope source,
                     MemoryGraphScope target, long cutoffEventSequence) {
        if (source.kind() != MemoryGraphScope.Kind.THREAD || target.kind() != MemoryGraphScope.Kind.THREAD
                || source.equals(target)) throw new IllegalArgumentException("无效的图谱分支");
        MemoryService from = memory.inScope(source);
        MemoryService to = memory.inScope(target);
        MemoryStoreRegistry.withLiveGraph(source.directory(root), () -> {
            for (Episode original : from.episodes()) {
                if (original.sourceEventSequence > cutoffEventSequence) continue;
                Episode copy = new Episode(target.threadId(), original.userInput, original.assistantReply);
                copy.id = original.id;
                copy.turnId = original.turnId;
                copy.originThreadId = original.originThreadId == null ? source.threadId() : original.originThreadId;
                copy.originTurnId = original.originTurnId == null ? original.turnId : original.originTurnId;
                copy.sourceEventSequence = original.sourceEventSequence;
                copy.timestamp = original.timestamp;
                copy.toolTraceJson = original.toolTraceJson;
                to.store().addTurnOnce(copy, "thread.fork");
            }
        });
    }
}
