package com.javaclaw.memory;

import com.javaclaw.framework.api.RunScope;
import com.javaclaw.memory.model.MemoryRoot;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Root-owned deletion projection, including workspaces with no live MemoryService. */
public final class ThreadMemoryCleanup {
    private final Path memoryRoots;
    public ThreadMemoryCleanup(Path globalDataRoot) {
        memoryRoots = globalDataRoot.toAbsolutePath().normalize().resolve("memory-stores");
    }
    public void delete(RunScope owner) {
        Path root = workspaceRoot(owner.workspaceId());
        MemoryGraphScope scope = MemoryGraphScope.thread(owner);
        Path thread = scope.directory(root), habits = scope.habits().directory(root);
        safePath(root, thread); safePath(root, habits);
        safePath(root, thread.resolveSibling(thread.getFileName() + ".deleted"));
        MemoryStoreRegistry.delete(thread);
        MemoryStoreRegistry.updateMetadata(habits, data -> markHabitSources(data, scope.threadId()));
        if ("local-user".equals(scope.userId()))
            MemoryStoreRegistry.updateMetadata(root, data -> hideLegacySources(data, scope.threadId()));
    }
    private Path workspaceRoot(String id) {
        Path root = memoryRoots.resolve(id).normalize();
        if (!memoryRoots.equals(root.getParent()) || id.contains("/") || id.contains("\\"))
            throw new IllegalArgumentException("工作区记忆路径必须是独立目录标识");
        safePath(memoryRoots, root);
        return root;
    }
    private static void safePath(Path root, Path target) {
        for (Path current = target; current != null && current.startsWith(root); current = current.getParent())
            if (Files.isSymbolicLink(current)) throw new IllegalArgumentException("记忆清理不能穿过符号链接");
    }
    private static List<Object> markHabitSources(MemoryRoot root, String thread) {
        List<Object> changed = new ArrayList<>();
        java.util.function.Consumer<com.javaclaw.memory.model.Fact> mark = fact -> {
            if (!fact.evidenceDeleted && fact.evidenceKeys != null
                    && fact.evidenceKeys.stream().anyMatch(key -> key.startsWith(thread + ":"))) {
                fact.evidenceDeleted = true; changed.add(fact);
            }
        };
        root.facts.iterate(mark); root.pendingFacts.iterate(mark);
        return changed;
    }
    private static List<Object> hideLegacySources(MemoryRoot root, String thread) {
        java.util.function.Consumer<com.javaclaw.memory.model.Episode> hideEpisode = episode -> {
            if (thread.equals(episode.sessionId)) root.migratedIds.add("episode:" + episode.id);
        };
        root.episodes.iterate(hideEpisode); root.pendingEpisodes.iterate(hideEpisode);
        java.util.function.Consumer<com.javaclaw.memory.model.Fact> hideFact = fact -> {
            if (fact.source != null && thread.equals(fact.source.sessionId)) root.migratedIds.add("fact:" + fact.id);
        };
        root.facts.iterate(hideFact); root.pendingFacts.iterate(hideFact);
        root.corrections.iterate(correction -> {
            if (correction.targetFactId != null && root.migratedIds.contains("fact:" + correction.targetFactId))
                root.migratedIds.add("correctionrecord:" + correction.id);
        });
        java.util.function.Consumer<com.javaclaw.memory.model.Fact> hideCorrected = fact -> {
            if (fact.correctionId != null && root.migratedIds.contains("correctionrecord:" + fact.correctionId))
                root.migratedIds.add("fact:" + fact.id);
        };
        root.facts.iterate(hideCorrected); root.pendingFacts.iterate(hideCorrected);
        root.working.remove(thread);
        return List.of(root, root.migratedIds, root.working);
    }
}
