package com.javaclaw.server.coding;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.AttachmentScope;
import com.javaclaw.api.CancellationSource;
import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.builtin.contracts.CodingContracts;
import com.javaclaw.builtin.contracts.CodingEnvironmentContracts;
import com.javaclaw.builtin.contracts.CodingResults;
import com.javaclaw.extension.spi.ContributionKind;
import com.javaclaw.extension.spi.ExtensionRequest;
import com.javaclaw.extension.spi.ExtensionResponse;
import com.javaclaw.server.persistence.AttachmentService;
import com.javaclaw.server.persistence.CodingCommandOutputRepository;
import com.javaclaw.server.persistence.CodingOperationRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 真实 H2 管理查询契约：标识用于定位，归属和当前权限决定是否可读。 */
class CodingManagementBoundaryTest {
    @TempDir
    Path directory;

    private CodingTestFixture fixture;
    private CodingOperationRepository operations;

    @BeforeEach
    void initialize() throws Exception {
        fixture = new CodingTestFixture(directory);
        operations = new CodingOperationRepository(fixture.database, fixture.json, fixture.clock);
    }

    @AfterEach
    void close() throws Exception {
        fixture.close();
    }

    @Test
    void configurationUpdatesPreserveFrozenEnvironmentAndRejectUnreviewedArtifacts() throws Exception {
        var original = fixture.json.decode(
                query("environment/read", new Empty()), CodingEnvironmentContracts.Environment.class);
        var catalog =
                fixture.json.decode(query("toolchain/catalog", new Empty()), CodingEnvironmentContracts.Catalog.class);
        assertFalse(catalog.artifacts().isEmpty());
        assertTrue(fixture.json
                .decode(query("toolchain/list", new Empty()), CodingEnvironmentContracts.InstalledList.class)
                .toolchains()
                .isEmpty());
        var spec = new CodingEnvironmentContracts.EnvironmentSpec(
                "受审阅环境", original.spec().toolchains(), Set.of("repo.maven.apache.org"), false);
        ExtensionRequest update = request(
                "environment/update",
                new CodingEnvironmentContracts.EnvironmentUpdate(spec),
                fixture.workspace.id(),
                Optional.empty(),
                Optional.empty(),
                original.revision());
        var saved = invoke(update, ContributionKind.COMMAND);
        assertEquals(original.revision() + 1, saved.revision());
        assertEquals(saved, invoke(update, ContributionKind.COMMAND));
        assertEquals(
                spec,
                fixture.json
                        .decode(saved.payload(), CodingEnvironmentContracts.Environment.class)
                        .spec());
        assertEquals(
                original,
                new com.javaclaw.server.persistence.CodingEnvironmentRepository(
                                fixture.database, fixture.json, fixture.clock)
                        .frozen(fixture.turn.id()));
        var reference = original.spec().toolchains().getFirst();
        var unknown =
                new CodingEnvironmentContracts.ToolchainRef(reference.kind(), reference.version(), "0".repeat(64));
        var badSpec = new CodingEnvironmentContracts.EnvironmentSpec("未知发行", List.of(unknown), Set.of(), true);
        assertThrows(
                SecurityException.class,
                () -> invoke(
                        request(
                                "environment/update",
                                new CodingEnvironmentContracts.EnvironmentUpdate(badSpec),
                                fixture.workspace.id(),
                                Optional.empty(),
                                Optional.empty(),
                                saved.revision()),
                        ContributionKind.COMMAND));
    }

    @Test
    void preparationOutputUsesPersistedBlobsAndByteCursorsAcrossBothChannels() throws Exception {
        recordPreparation("prepared", "output", "error");
        var result = fixture.json.decode(
                query("preparation/read", new CodingResults.ResourceRead("prepared")),
                CodingResults.PreparationResult.class);
        assertEquals(CodingContracts.PackageManager.NPM, result.manager());
        var first = output("prepared", 0, 8);
        assertEquals("output", first.stdout());
        assertEquals("er", first.stderr());
        assertEquals(8, first.nextOffsetBytes());
        assertTrue(first.truncated());
        var last = output("prepared", 8, 8);
        assertEquals("", last.stdout());
        assertEquals("ror", last.stderr());
        assertFalse(last.truncated());
        assertEquals(11, last.nextOffsetBytes());
        assertEquals("", output("prepared", 11, 8).stderr());
        assertThrows(IllegalArgumentException.class, () -> output("prepared", 12, 8));
    }

    @Test
    void identifiersNeverGrantCrossWorkspaceThreadTurnOrResourceKindAccess() throws Exception {
        recordPreparation("prepared", "out", "err");
        var value = new CodingResults.ResourceRead("prepared");
        assertThrows(
                SecurityException.class,
                () -> invoke(
                        request("preparation/read", value, WorkspaceId.random(), Optional.empty(), Optional.empty(), 0),
                        ContributionKind.QUERY));
        assertThrows(
                SecurityException.class,
                () -> invoke(
                        request(
                                "preparation/read",
                                value,
                                fixture.workspace.id(),
                                Optional.of(ThreadId.random()),
                                Optional.empty(),
                                0),
                        ContributionKind.QUERY));
        assertThrows(
                SecurityException.class,
                () -> invoke(
                        request(
                                "preparation/read",
                                value,
                                fixture.workspace.id(),
                                Optional.empty(),
                                Optional.of(TurnId.random()),
                                0),
                        ContributionKind.QUERY));
        assertThrows(SecurityException.class, () -> query("diff/read", value));
        assertThrows(SecurityException.class, () -> query("terminal/read", value));
        assertThrows(
                SecurityException.class, () -> query("preparation/read", new CodingResults.ResourceRead("absent")));
        assertEquals(
                CodingContracts.PackageManager.NPM,
                fixture.json
                        .decode(
                                invoke(
                                                request(
                                                        "preparation/read",
                                                        value,
                                                        fixture.workspace.id(),
                                                        Optional.of(fixture.turn.threadId()),
                                                        Optional.of(fixture.turn.id()),
                                                        0),
                                                ContributionKind.QUERY)
                                        .payload(),
                                CodingResults.PreparationResult.class)
                        .manager());
    }

    @Test
    void patchEvidenceIsQueryableWithoutReapplyingAnyFileChanges() throws Exception {
        var patch = new CodingContracts.ApplyPatch(List.of(
                new CodingContracts.FileEdit("new.txt", Optional.empty(), Optional.of("保留真实内容"), Optional.empty())));
        var result = fixture.invoke("file_apply_patch", patch, "patch");
        assertTrue(result.success());
        String operationId = new com.javaclaw.server.persistence.H2Transactions(fixture.database)
                .execute(connection -> {
                    try (var query = connection.prepareStatement(
                                    "SELECT ID FROM CORE.CODING_OPERATION WHERE CALL_ID='patch'");
                            var rows = query.executeQuery()) {
                        assertTrue(rows.next());
                        String id = rows.getString(1);
                        assertFalse(rows.next());
                        return id;
                    }
                });
        var resource = new CodingResults.ResourceRead(operationId);
        assertEquals(result.response().payload(), query("change/list", resource));
        assertEquals(result.response().payload(), query("diff/read", resource));
        assertEquals("保留真实内容", java.nio.file.Files.readString(fixture.root.resolve("new.txt")));
    }

    @Test
    void managementCannotExecuteToolsAndCancelledQueriesCannotReturnEvidence() {
        var empty =
                request("environment/read", new Empty(), fixture.workspace.id(), Optional.empty(), Optional.empty(), 0);
        assertThrows(SecurityException.class, () -> invoke(empty, ContributionKind.TOOL));
        assertThrows(SecurityException.class, () -> invoke(empty, ContributionKind.COMMAND));
        assertThrows(SecurityException.class, () -> query("dependencies_prepare", new Empty()));
        var cancellation = new CancellationSource();
        cancellation.cancel("测试取消");
        try (var binding = fixture.platform.bindManagement(empty, ContributionKind.QUERY, cancellation)) {
            assertThrows(com.javaclaw.api.TurnCancelledException.class, binding::invoke);
        }
    }

    private CodingResults.Output output(String id, long offset, int count) throws Exception {
        return fixture.json.decode(
                query("preparation/output", new CodingResults.OutputRead(id, offset, count)),
                CodingResults.Output.class);
    }

    private CanonicalPayload query(String operation, Object value) throws Exception {
        return invoke(
                        request(operation, value, fixture.workspace.id(), Optional.empty(), Optional.empty(), 0),
                        ContributionKind.QUERY)
                .payload();
    }

    private ExtensionResponse invoke(ExtensionRequest request, ContributionKind kind) throws Exception {
        try (var binding = fixture.platform.bindManagement(request, kind, new CancellationSource())) {
            return binding.invoke();
        }
    }

    private ExtensionRequest request(
            String operation,
            Object value,
            WorkspaceId workspace,
            Optional<ThreadId> thread,
            Optional<TurnId> turn,
            long revision) {
        return new ExtensionRequest(
                workspace,
                thread,
                turn,
                operation,
                fixture.json.encode(value),
                Optional.of("management-" + fixture.json.encode(value).sha256()),
                revision,
                Optional.empty());
    }

    private void recordPreparation(String id, String stdout, String stderr) {
        var intent = new CodingOperationRepository.Intent(
                id,
                fixture.turn.id(),
                fixture.workspace.id(),
                id,
                "dependencies_prepare",
                fixture.root,
                fixture.json.parse("{}"));
        operations.prepare(intent);
        operations.start(id);
        var summary = new CodingResults.CommandSummary(
                id, List.of("npm", "install"), ".", Optional.of(0), CodingResults.ProcessState.COMPLETED, 10);
        var output = new CodingResults.Output(stdout, stderr, stdout.length() + stderr.length(), false);
        var preparation = new CodingResults.PreparationResult(
                CodingContracts.PackageManager.NPM, new CodingResults.CommandResult(summary, output), List.of());
        var attachments = new AttachmentService(fixture.database, fixture.json, fixture.clock);
        var scope = AttachmentScope.workspace(fixture.workspace.id());
        byte[] out = stdout.getBytes(StandardCharsets.UTF_8);
        byte[] err = stderr.getBytes(StandardCharsets.UTF_8);
        var outRef = attachments.store(
                scope,
                fixture.identity("fixture/output", id + "-out", java.util.Map.of("stdout", stdout)),
                "application/octet-stream",
                out);
        var errRef = attachments.store(
                scope,
                fixture.identity("fixture/output", id + "-err", java.util.Map.of("stderr", stderr)),
                "application/octet-stream",
                err);
        new CodingCommandOutputRepository(fixture.database, fixture.json)
                .record(
                        fixture.workspace.id(),
                        id,
                        new CodingCommandOutputRepository.Output(
                                outRef.digest(), out.length, errRef.digest(), err.length));
        operations.finish(id, fixture.json.encode(preparation), List.of(), true);
    }

    private record Empty() {}
}
