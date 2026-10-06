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
        if (thread == null || thread.isBlank()) {
            throw new IllegalArgumentException("记忆清理必须指定来源会话");
        }
        List<Object> changed = new ArrayList<>();
        java.util.function.Consumer<com.javaclaw.memory.model.Fact> mark = fact -> {
            if (!fact.evidenceDeleted && fact.evidenceKeys != null
                    && fact.evidenceKeys.stream().anyMatch(key -> belongsToThread(key, thread))) {
                fact.evidenceDeleted = true; changed.add(fact);
            }
        };
        root.facts.iterate(mark); root.pendingFacts.iterate(mark);
        var stats = root.stats;
        if (stats == null) return changed;
        boolean progressChanged = false;
        if (stats.pendingHabitObservations != null) {
            var observations = new ArrayList<>(stats.pendingHabitObservations);
            if (observations.removeIf(observation -> observation != null
                    && belongsToThread(observation.evidenceKey, thread))) {
                stats.pendingHabitObservations = observations;
                changed.add(observations);
                progressChanged = true;
            }
        }
        if (stats.pendingHabitEvidenceKeys != null) {
            var keys = new ArrayList<>(stats.pendingHabitEvidenceKeys);
            if (keys.removeIf(key -> belongsToThread(key, thread))) {
                stats.pendingHabitEvidenceKeys = keys;
                changed.add(keys);
                progressChanged = true;
            }
        }
        if (progressChanged) {
            // An in-flight review must not overwrite deletion with its older ledger snapshot.
            stats.habitReviewRevision++;
            changed.add(stats);
        }
        return changed;
    }

    private static boolean belongsToThread(String evidenceKey, String thread) {
        if (evidenceKey == null) return false;
        int separator = evidenceKey.indexOf(':');
        // Evidence keys have two nonempty identity components. Ambiguous legacy keys
        // must never let deletion of one thread remove another thread's observations.
        return separator > 0 && separator == evidenceKey.lastIndexOf(':')
                && separator < evidenceKey.length() - 1
                && evidenceKey.substring(0, separator).equals(thread);
    }
}
