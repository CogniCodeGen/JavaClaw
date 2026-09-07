package com.javaclaw.builtin.contracts;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DependencyEvidenceContractsTest {
    private static final String BEFORE = "a".repeat(64);
    private static final String AFTER = "b".repeat(64);

    @Test
    void completionRequiresBothObservationsAndCannotHideOmittedDirectories() {
        var full = new DependencyEvidence.Inventory(List.of(file()), 4, true, List.of());
        var partial = new DependencyEvidence.Inventory(List.of(file()), 4, false, List.of("node_modules"));
        assertTrue(evidence("op", full, Optional.of(full), List.of(), List.of(), true)
                .complete());
        assertFalse(evidence("op", partial, Optional.empty(), List.of(), List.of(), false)
                .complete());
        assertThrows(
                IllegalArgumentException.class,
                () -> evidence("op", full, Optional.empty(), List.of(), List.of(), true));
        assertThrows(
                IllegalArgumentException.class,
                () -> evidence("op", partial, Optional.of(full), List.of(), List.of(), true));
        assertThrows(
                IllegalArgumentException.class,
                () -> evidence("op", full, Optional.of(partial), List.of(), List.of(), true));
        assertThrows(
                IllegalArgumentException.class,
                () -> evidence(null, full, Optional.empty(), List.of(), List.of(), false));
        assertThrows(
                IllegalArgumentException.class,
                () -> evidence("a/b", full, Optional.empty(), List.of(), List.of(), false));
        assertThrows(
                IllegalArgumentException.class,
                () -> evidence("op", full, Optional.empty(), Collections.nCopies(20_033, change()), List.of(), false));
        assertThrows(
                IllegalArgumentException.class,
                () -> evidence(
                        "op",
                        full,
                        Optional.empty(),
                        List.of(),
                        Collections.nCopies(257, artifact("stdout", "native-output", "execution", 1)),
                        false));
    }

    @Test
    void inventoryBoundsPreventUnboundedOrContradictoryEvidence() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new DependencyEvidence.Inventory(Collections.nCopies(10_001, file()), 0, false, List.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new DependencyEvidence.Inventory(List.of(), -1, false, List.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new DependencyEvidence.Inventory(List.of(), 0, false, Collections.nCopies(257, "omitted")));
        assertThrows(
                IllegalArgumentException.class,
                () -> new DependencyEvidence.Inventory(List.of(), 0, false, List.of("x".repeat(4097))));
        assertThrows(
                IllegalArgumentException.class,
                () -> new DependencyEvidence.Inventory(List.of(), 0, true, List.of(".git")));
        assertTrue(new DependencyEvidence.Inventory(List.of(), 0, true, List.of()).complete());
    }

    @Test
    void onlyActualFileChangesAndValidRawArtifactReferencesAreRepresentable() {
        assertEquals(AFTER, change().afterSha256().orElseThrow());
        assertThrows(
                IllegalArgumentException.class,
                () -> new DependencyEvidence.Change(".", Optional.empty(), Optional.of(AFTER)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new DependencyEvidence.Change("file", Optional.empty(), Optional.empty()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new DependencyEvidence.Change("file", Optional.of(BEFORE), Optional.of(BEFORE)));
        assertThrows(IllegalArgumentException.class, () -> new DependencyEvidence.FileDigest(".", 0, BEFORE));
        assertThrows(IllegalArgumentException.class, () -> new DependencyEvidence.FileDigest("file", -1, BEFORE));
        assertThrows(IllegalArgumentException.class, () -> new DependencyEvidence.FileDigest("file", 0, "bad"));
        assertThrows(IllegalArgumentException.class, () -> new DependencyEvidence.FileDigest("file", 0, null));
        assertEquals(
                "pip-report",
                artifact("pip-report", "pip-report", "execution", 0).kind());
        assertEquals("lock", artifact("package-lock.json", "lock", "after", 1).kind());
        assertEquals("manifest", artifact("pom.xml", "manifest", "before", 1).kind());
        assertThrows(IllegalArgumentException.class, () -> artifact(null, "manifest", "before", 1));
        assertThrows(IllegalArgumentException.class, () -> artifact("", "manifest", "before", 1));
        assertThrows(IllegalArgumentException.class, () -> artifact("x".repeat(4097), "manifest", "before", 1));
        assertThrows(IllegalArgumentException.class, () -> artifact("source", "made-up", "before", 1));
        assertThrows(IllegalArgumentException.class, () -> artifact("source", "manifest", "future", 1));
        assertThrows(IllegalArgumentException.class, () -> artifact("source", "manifest", "before", -1));
    }

    private static DependencyEvidence evidence(
            String id,
            DependencyEvidence.Inventory before,
            Optional<DependencyEvidence.Inventory> after,
            List<DependencyEvidence.Change> changes,
            List<DependencyEvidence.Artifact> artifacts,
            boolean complete) {
        return new DependencyEvidence(
                id, CodingContracts.PackageManager.NPM, before, after, changes, artifacts, complete);
    }

    private static DependencyEvidence.FileDigest file() {
        return new DependencyEvidence.FileDigest("source.java", 4, BEFORE);
    }

    private static DependencyEvidence.Change change() {
        return new DependencyEvidence.Change("source.java", Optional.of(BEFORE), Optional.of(AFTER));
    }

    private static DependencyEvidence.Artifact artifact(String source, String kind, String phase, long size) {
        return new DependencyEvidence.Artifact(source, kind, phase, BEFORE, size, true);
    }
}
