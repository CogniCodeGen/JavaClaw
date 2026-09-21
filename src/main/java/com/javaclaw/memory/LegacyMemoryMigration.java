package com.javaclaw.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.memory.model.Episode;
import com.javaclaw.memory.model.Fact;
import com.javaclaw.memory.model.Persona;
import com.javaclaw.memory.store.MemoryStore;
import com.javaclaw.prompt.MemoryPrompts;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

/** Conservative, repeatable copy migration. Original data is quarantined by persistent assignment markers and never recalled. */
final class LegacyMemoryMigration {
    private LegacyMemoryMigration() {}

    /** Deletion also fences legacy originals that were not yet eligible for migration. */
    static void hideThread(MemoryService memory, MemoryGraphScope deleted) {
        var legacyScope = memory.scopes().stream().filter(s -> s.kind() == MemoryGraphScope.Kind.LEGACY)
                .findFirst().orElse(null);
        if (legacyScope == null) return;
        MemoryStore store = memory.inScope(legacyScope).store();
        store.withProjectionLock(() -> {
            java.util.stream.Stream.concat(store.allEpisodes().stream(), store.allPendingEpisodes().stream())
                    .filter(e -> deleted.threadId().equals(e.sessionId))
                    .forEach(e -> store.root().migratedIds.add("episode:" + e.id));
            java.util.stream.Stream.concat(store.allFacts().stream(), store.allPendingFacts().stream())
                    .filter(f -> f.source != null && deleted.threadId().equals(f.source.sessionId))
                    .forEach(f -> store.root().migratedIds.add("fact:" + f.id));
            store.removeCheckpoint(deleted.threadId());
            hideAssignedCorrections(store);
            store.persistProjectionState();
        });
    }

    static int migrate(MemoryService memory, ObjectMapper json, Predicate<String> knownThread) {
        MemoryGraphScope legacyScope = memory.scopes().stream()
                .filter(scope -> scope.kind() == MemoryGraphScope.Kind.LEGACY).findFirst().orElse(null);
        if (legacyScope == null) return 0;
        MemoryService legacy = memory.inScope(legacyScope);
        Map<String, Episode> episodes = new HashMap<>();
        Map<String, MemoryService> targets = new HashMap<>();
        int moved = 0;
        java.util.List<Episode> rawEpisodes = new ArrayList<>(legacy.store().allEpisodes());
        rawEpisodes.addAll(legacy.store().allPendingEpisodes());
        for (Episode original : rawEpisodes) {
            if (original.id == null || original.sessionId == null || original.sessionId.isBlank()
                    || !knownThread.test(original.sessionId)) continue;
            MemoryGraphScope scope = new MemoryGraphScope(legacyScope.workspaceId(), legacyScope.userId(),
                    original.sessionId, MemoryGraphScope.Kind.THREAD);
            MemoryService target;
            try { target = memory.inScope(scope); }
            catch (IllegalStateException deleted) { continue; }
            Episode copy = json.convertValue(original, Episode.class);
            copy.turnId = "legacy:" + original.id;
            copy.originThreadId = original.sessionId;
            copy.originTurnId = copy.turnId;
            copy.ownerRunId = null; // Legacy data is not permission to replay a model or a tool.
            copy.distilled = true;
            if (target.store().addTurnOnce(copy, "migration")) moved++;
            Episode stored = target.store().findTurn(copy.turnId);
            episodes.put(original.id, stored);
            targets.put(original.id, target);
            legacy.store().root().migratedIds.add("episode:" + original.id);
            legacy.store().persistProjectionState();
        }
        java.util.List<Fact> rawFacts = new ArrayList<>(legacy.store().allFacts());
        rawFacts.addAll(legacy.store().allPendingFacts());
        for (Fact original : rawFacts) {
            MemoryService target;
            if ("HABIT_REVIEW".equals(original.sourceKind)) target = memory;
            else if (original.source != null && targets.containsKey(original.source.id)) {
                target = targets.get(original.source.id);
            } else continue; // No reliable origin: remain quarantined, never silently become a habit.
            if (target.facts().stream().anyMatch(fact -> Objects.equals(fact.id, original.id))) {
                legacy.store().root().migratedIds.add("fact:" + original.id);
                legacy.store().persistProjectionState();
                continue;
            }
            Fact copy = json.convertValue(original, Fact.class);
            copy.source = target == memory ? null : episodes.get(original.source.id);
            copy.about = new ArrayList<>();
            if (original.about != null) {
                for (var entity : original.about) {
                    if (entity == null || entity.name == null) continue;
                    copy.about.add(target.store().getOrCreateEntity(entity.name, entity.type, "migration"));
                }
            }
            if (copy.evidenceKeys == null) copy.evidenceKeys = new ArrayList<>();
            if (copy.source != null && !copy.evidenceKeys.contains(copy.source.evidenceKey())) {
                copy.evidenceKeys.add(copy.source.evidenceKey());
            }
            if (copy.pending || copy.embedding == null) target.store().addPendingFact(copy, "migration");
            else target.store().addFact(copy, "migration");
            legacy.store().root().migratedIds.add("fact:" + original.id);
            legacy.store().persistProjectionState();
            moved++;
        }
        Persona persona = legacy.getPersona();
        Persona current = memory.getPersona();
        if (persona != null && (current == null
                || MemoryPrompts.DEFAULT_AGENTS_SKELETON.equals(current.content))) {
            memory.store().updatePersona(target -> {
                target.content = persona.content;
                target.identity = persona.identity;
                target.tone = persona.tone;
                target.structured = persona.structured;
                target.preferences = persona.preferences == null ? new ArrayList<>() : new ArrayList<>(persona.preferences);
                target.taboos = persona.taboos == null ? new ArrayList<>() : new ArrayList<>(persona.taboos);
            }, "migration");
            legacy.store().root().migratedIds.add("persona");
            legacy.store().persistProjectionState();
        }
        hideAssignedCorrections(legacy.store());
        legacy.store().persistProjectionState();
        return moved;
    }

    private static void hideAssignedCorrections(MemoryStore store) {
        for (var correction : store.allCorrections()) {
            if (correction.targetFactId != null && store.root().migratedIds.contains("fact:" + correction.targetFactId))
                store.root().migratedIds.add("correctionrecord:" + correction.id);
        }
        java.util.stream.Stream.concat(store.allFacts().stream(), store.allPendingFacts().stream())
                .filter(f -> f.correctionId != null && store.root().migratedIds.contains("correctionrecord:" + f.correctionId))
                .forEach(f -> store.root().migratedIds.add("fact:" + f.id));
    }
}
