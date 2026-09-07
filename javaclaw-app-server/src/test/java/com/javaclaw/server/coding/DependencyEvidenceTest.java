package com.javaclaw.server.coding;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.AttachmentScope;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.builtin.contracts.CodingContracts;
import com.javaclaw.builtin.contracts.CodingResults;
import com.javaclaw.builtin.contracts.DependencyEvidence;
import com.javaclaw.nativehost.coding.WorkspaceFileAccess;
import com.javaclaw.nativehost.sandbox.PlatformSandboxExecutor;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.server.persistence.AttachmentService;
import com.javaclaw.server.persistence.CodingOperationRepository;
import com.javaclaw.server.persistence.DependencyEvidenceRepository;
import com.javaclaw.server.persistence.ExtensionCatalogRepository;
import com.javaclaw.server.persistence.ManagedWorktreeService;
import com.javaclaw.server.turn.CodingExecutionAuthority;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DependencyEvidenceTest {
    @TempDir
    Path directory;

    @Test
    void partialSnapshotsOnlyDescribeObservedChangesAndNeverInventMissingFiles() {
        var before = inventory(List.of(file("changed.java", 'a'), file("missing.java", 'b')), false);
        var after = inventory(List.of(file("changed.java", 'c'), file("appeared.java", 'd')), false);
        var changes = CodingDependencyEvidence.changes(before, after);
        assertEquals(
                List.of(new DependencyEvidence.Change(
                        "changed.java", Optional.of("a".repeat(64)), Optional.of("c".repeat(64)))),
                changes);
        assertEquals(
                3,
                CodingDependencyEvidence.changes(inventory(before.files(), true), inventory(after.files(), true))
                        .size());
    }

    @Test
    void reportEnvelopePreservesRawBytesAndRejectsMixedOutputOrUnknownExit() {
        CanonicalJson json = new CanonicalJson();
        byte[] report = "{\"version\":\"1\",\"install\":[]}".getBytes(StandardCharsets.UTF_8);
        String encoded = Base64.getEncoder().encodeToString(report);
        String envelope = "{\"format\":\"javaclaw.pip-report.v1\",\"exitCode\":0,\"reportBase64\":\"" + encoded
                + "\",\"error\":\"\"}";
        assertArrayEquals(
                report, CodingPipEvidence.report(json, command(envelope, 0)).orElseThrow());
        assertTrue(CodingPipEvidence.report(json, command("build log\n" + envelope, 0))
                .isEmpty());
        assertTrue(CodingPipEvidence.report(json, command(envelope, 1)).isEmpty());
        assertTrue(
                CodingPipEvidence.report(json, command(envelope.replace("\"error\":\"\"", "\"error\":\"missing\""), 0))
                        .isEmpty());
    }

    @Test
    void beforeAndAfterEvidenceUseWorkspaceOwnershipWithoutStartingTheCommandAgain() throws Exception {
        try (var fixture = new CodingTestFixture(directory)) {
            var operations = new CodingOperationRepository(fixture.database, fixture.json, fixture.clock);
            var repository = new DependencyEvidenceRepository(fixture.database, fixture.json);
            var intent = new CodingOperationRepository.Intent(
                    "preparation",
                    fixture.turn.id(),
                    fixture.workspace.id(),
                    "prepare",
                    "dependencies_prepare",
                    fixture.root,
                    fixture.json.parse("{}"));
            operations.prepare(intent);
            var before = inventory(List.of(file("source.java", 'a')), true);
            var initial = new DependencyEvidence(
                    "preparation",
                    CodingContracts.PackageManager.NPM,
                    before,
                    Optional.empty(),
                    List.of(),
                    List.of(),
                    false);
            repository.record(fixture.workspace.id(), initial);
            assertEquals(
                    "PREPARED",
                    operations
                            .find(fixture.workspace.id(), "preparation")
                            .orElseThrow()
                            .state());
            assertEquals(initial, repository.read(fixture.workspace.id(), "preparation"));
            assertThrows(SecurityException.class, () -> repository.read(WorkspaceId.random(), "preparation"));
            operations.start("preparation");
            var complete = new DependencyEvidence(
                    "preparation",
                    CodingContracts.PackageManager.NPM,
                    before,
                    Optional.of(before),
                    List.of(),
                    List.of(),
                    true);
            repository.record(fixture.workspace.id(), complete);
            assertEquals(
                    "STARTED",
                    operations
                            .find(fixture.workspace.id(), "preparation")
                            .orElseThrow()
                            .state());
            operations.finish("preparation", fixture.json.parse("{}"), List.of(), true);
            assertThrows(SecurityException.class, () -> repository.record(fixture.workspace.id(), initial));
            assertEquals(complete, repository.read(fixture.workspace.id(), "preparation"));
        }
    }

    @Test
    void evidenceCannotClaimCompletenessWithOmissionsOrTreatTheRootAsAFile() {
        assertThrows(IllegalArgumentException.class, () -> new DependencyEvidence.FileDigest(".", 0, "a".repeat(64)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new DependencyEvidence.Inventory(List.of(), 0, true, List.of(".git")));
        assertFalse(inventory(List.of(), false).complete());
    }

    @Test
    void observedFactsUseTheSharedFileChangeSchemaOperationNames() {
        var before = Optional.of("a".repeat(64));
        var after = Optional.of("b".repeat(64));
        var changes = List.of(
                new DependencyEvidence.Change("new.txt", Optional.empty(), after),
                new DependencyEvidence.Change("updated.txt", before, after),
                new DependencyEvidence.Change("deleted.txt", before, Optional.empty()));
        var facts = changes.stream().map(CodingDependencyPreparer::observedFact).toList();
        assertEquals(
                List.of("create", "update", "delete"),
                facts.stream()
                        .map(com.javaclaw.api.CorePayloads.FileChange::operation)
                        .toList());
        var codecs = com.javaclaw.protocol.CoreItemCodecs.createRegistry(new CanonicalJson());
        for (var fact : facts) {
            assertEquals(
                    com.javaclaw.api.CoreSchemas.FILE_CHANGE,
                    codecs.encode(fact).schemaId());
            // Coding 的严格 PatchChange 契约与 Core FileChange 共享 operation 词表。
            new CodingResults.PatchChange(
                    fact.relativePath().toString(),
                    fact.operation(),
                    fact.beforeDigest(),
                    fact.afterDigest(),
                    Optional.empty(),
                    "");
        }
    }

    @Test
    void nativeObservationStoresManifestBytesAndSourceChangesWithWorkspaceBoundReferences() throws Exception {
        try (var fixture = new CodingLifecycleFixture(directory, new ControlledCodingSandbox())) {
            Files.writeString(fixture.base.root.resolve("package.json"), "{\"name\":\"before\"}");
            Files.writeString(fixture.base.root.resolve("Main.java"), "class Before {}");
            var input = new CodingContracts.DependenciesPrepare(CodingContracts.PackageManager.NPM, ".", List.of());
            var invocation = fixture.invocation("evidence", "dependencies_prepare", input);
            var dependencies = dependencies(fixture.base);
            var evidence = new CodingDependencyEvidence(dependencies);
            var files = new WorkspaceFileAccess(fixture.base.root, fixture.base.permission);
            var before = evidence.begin(files, invocation, input);
            Files.writeString(fixture.base.root.resolve("Main.java"), "class After {}");
            Files.writeString(fixture.base.root.resolve("package-lock.json"), "{\"lockfileVersion\":3}");
            var result = evidence.finish(before, files, invocation, input, Optional.empty());
            assertTrue(result.observedChanges().stream()
                    .anyMatch(change -> change.path().equals("Main.java")));
            assertTrue(result.observedChanges().stream()
                    .anyMatch(change -> change.path().equals("package-lock.json")
                            && change.beforeSha256().isEmpty()
                            && change.afterSha256().isPresent()));
            var original = result.artifacts().stream()
                    .filter(artifact -> artifact.source().equals("package.json")
                            && artifact.phase().equals("before"))
                    .findFirst()
                    .orElseThrow();
            assertEquals(
                    "{\"name\":\"before\"}",
                    new String(
                            dependencies
                                    .attachments()
                                    .read(AttachmentScope.workspace(fixture.base.workspace.id()), original.sha256())
                                    .content(),
                            StandardCharsets.UTF_8));
            assertThrows(
                    RuntimeException.class,
                    () -> dependencies
                            .attachments()
                            .read(AttachmentScope.workspace(WorkspaceId.random()), original.sha256()));
            assertFalse(result.complete());
        }
    }

    static CodingPlatform.Dependencies dependencies(CodingTestFixture fixture) {
        var attachments = new AttachmentService(fixture.database, fixture.json, fixture.clock);
        var sandbox = new PlatformSandboxExecutor();
        var worktrees = new ManagedWorktreeService(fixture.database, attachments, fixture.json, fixture.clock, sandbox);
        var authority = new CodingExecutionAuthority(
                fixture.core,
                fixture.profiles,
                worktrees,
                new ExtensionCatalogRepository(fixture.database, fixture.json, fixture.clock),
                fixture.json);
        return new CodingPlatform.Dependencies(
                fixture.database,
                fixture.core,
                authority,
                attachments,
                new FixtureToolchains(),
                sandbox,
                fixture.json,
                fixture.clock);
    }

    private static DependencyEvidence.Inventory inventory(List<DependencyEvidence.FileDigest> files, boolean complete) {
        return new DependencyEvidence.Inventory(
                files, files.size(), complete, complete ? List.of() : List.of("budget"));
    }

    private static DependencyEvidence.FileDigest file(String path, char digest) {
        return new DependencyEvidence.FileDigest(path, 1, String.valueOf(digest).repeat(64));
    }

    private static CodingResults.CommandResult command(String stdout, int exitCode) {
        return new CodingResults.CommandResult(
                new CodingResults.CommandSummary(
                        "pip-test",
                        List.of("python"),
                        ".",
                        Optional.of(exitCode),
                        CodingResults.ProcessState.COMPLETED,
                        1),
                new CodingResults.Output(stdout, "", stdout.length(), false));
    }
}
