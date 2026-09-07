package com.javaclaw.server.coding;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import com.javaclaw.api.AgentTurn;
import com.javaclaw.api.AttachmentScope;
import com.javaclaw.api.CorePayloads;
import com.javaclaw.api.CoreSchemas;
import com.javaclaw.api.ItemEnvelope;
import com.javaclaw.api.TurnId;
import com.javaclaw.builtin.contracts.CodingContracts;
import com.javaclaw.builtin.contracts.CodingResults;
import com.javaclaw.builtin.contracts.DependencyEvidence;
import com.javaclaw.protocol.CoreRpcContracts;
import com.javaclaw.runtime.TurnExecutionPhase;
import com.javaclaw.server.persistence.AttachmentService;
import com.javaclaw.server.persistence.CodingCommandOutputRepository;
import com.javaclaw.server.persistence.CodingOperationRepository;
import com.javaclaw.server.persistence.DependencyEvidenceRepository;
import com.javaclaw.server.persistence.H2Transactions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 用真实 H2 和 Workspace CAS 校验完整链路，不通过测试替身伪造命令、网络或 Diff 证据。 */
final class PublishedCodingHarnessAssertions {
    private PublishedCodingHarnessAssertions() {}

    static void revokedPreparation(CodingTestFixture base, TurnId turn, CodingResults.PreparationResult preparation)
            throws Exception {
        assertEquals(CodingContracts.PackageManager.NPM, preparation.manager());
        String operation = preparation.command().command().operationId();
        new H2Transactions(base.database).execute(connection -> {
            try (var statement = connection.prepareStatement("""
                SELECT STATE,TRANSFERRED_BYTES FROM CORE.CODING_NETWORK_GRANT
                WHERE TURN_ID=? AND OPERATION_ID=?
                """)) {
                statement.setString(1, turn.toString());
                statement.setString(2, operation);
                try (var rows = statement.executeQuery()) {
                    assertTrue(rows.next());
                    assertEquals("REVOKED", rows.getString(1));
                    assertTrue(rows.getLong(2) > 0, "隔离空缓存的 NPM 准备必须实际通过 Broker 传输公开依赖");
                    assertFalse(rows.next());
                }
            }
            try (var statement = connection.prepareStatement(
                    "SELECT COUNT(*) FROM CORE.CODING_NETWORK_GRANT WHERE TURN_ID=? AND STATE='ACTIVE'")) {
                statement.setString(1, turn.toString());
                try (var rows = statement.executeQuery()) {
                    assertTrue(rows.next());
                    assertEquals(0, rows.getInt(1));
                }
            }
            return null;
        });
    }

    static void completed(CodingHarnessFixture fixture, PublishedCodingHarnessModel model, TurnId coding)
            throws Exception {
        var items = fixture.base.core.listItems(fixture.thread.id());
        assertEquals(9, count(items, CoreSchemas.TOOL_CALL));
        assertEquals(9, count(items, CoreSchemas.TOOL_RESULT));
        assertEquals(3, count(items, CoreSchemas.COMMAND));
        assertEquals(5, count(items, CoreSchemas.EFFECT_RECEIPT));
        toolResults(fixture.base, items, model);
        patches(fixture.base, items, model);
        commandEvidence(fixture.base, coding, model.preparation.command(), "PROXY_ONLY");
        commandEvidence(fixture.base, coding, model.failedCommand, "OFFLINE");
        commandEvidence(fixture.base, coding, model.successfulCommand, "OFFLINE");
        dependencyEvidence(fixture.base, model.preparation.command().command().operationId());
        checkpoint(fixture, coding);
        legacyWire(fixture.base, items, coding);
        revokedPreparation(fixture.base, coding, model.preparation);
    }

    private static void toolResults(
            CodingTestFixture base, List<ItemEnvelope> items, PublishedCodingHarnessModel model) {
        var results = items.stream()
                .filter(item -> item.schemaId().equals(CoreSchemas.TOOL_RESULT))
                .map(item -> base.json.decode(item.payload(), CorePayloads.ToolResult.class))
                .toList();
        for (var result : results) {
            assertEquals(!result.callId().equals("failure"), result.success());
            result.receipt().ifPresent(receipt -> assertEquals(result.output().sha256(), receipt.resultDigest()));
        }
        assertEquals(
                model.preparation, base.json.decode(output(results, "prepare"), CodingResults.PreparationResult.class));
        assertEquals(model.brokenPatch, base.json.decode(output(results, "break"), CodingResults.PatchResult.class));
        assertEquals(model.repairedPatch, base.json.decode(output(results, "repair"), CodingResults.PatchResult.class));
        assertEquals(
                model.failedCommand, base.json.decode(output(results, "failure"), CodingResults.CommandResult.class));
        assertEquals(
                model.successfulCommand,
                base.json.decode(output(results, "success"), CodingResults.CommandResult.class));
    }

    private static com.javaclaw.api.CanonicalPayload output(List<CorePayloads.ToolResult> results, String id) {
        return results.stream()
                .filter(result -> result.callId().equals(id))
                .findFirst()
                .orElseThrow()
                .output();
    }

    private static void patches(CodingTestFixture base, List<ItemEnvelope> items, PublishedCodingHarnessModel model)
            throws Exception {
        var changes = items.stream()
                .filter(item -> item.schemaId().equals(CoreSchemas.FILE_CHANGE))
                .map(item -> base.json.decode(item.payload(), CorePayloads.FileChange.class))
                .filter(change -> change.relativePath().toString().equals(PublishedCodingHarnessModel.SOURCE))
                .toList();
        assertEquals(2, changes.size());
        assertTrue(changes.stream().allMatch(change -> change.operation().equals("update")));
        assertEquals(changes.getFirst().afterDigest(), changes.getLast().beforeDigest());
        assertEquals(changes.getFirst().beforeDigest(), changes.getLast().afterDigest());
        assertEquals(
                model.brokenPatch.changes().getFirst().beforeSha256(),
                changes.getFirst().beforeDigest());
        assertEquals(
                model.repairedPatch.changes().getFirst().afterSha256(),
                changes.getLast().afterDigest());
        // 成功补丁也保留旧 inode；持有旧文件句柄的外部编辑器稍后写入时仍有恢复来源。
        assertEquals(1, model.brokenPatch.recoveryPaths().size());
        assertEquals(1, model.repairedPatch.recoveryPaths().size());
        var original = base.root.resolve(model.brokenPatch.recoveryPaths().getFirst());
        var broken = base.root.resolve(model.repairedPatch.recoveryPaths().getFirst());
        assertEquals(PublishedCodingHarnessModel.GOOD_SOURCE, Files.readString(original.resolve("original-0")));
        assertEquals(
                PublishedCodingHarnessModel.GOOD_SOURCE.replace("expected = true", "expected = false"),
                Files.readString(broken.resolve("original-0")));
    }

    private static void commandEvidence(
            CodingTestFixture base, TurnId turn, CodingResults.CommandResult result, String mode) {
        var repository = new CodingOperationRepository(base.database, base.json, base.clock);
        var operation = repository
                .find(base.workspace.id(), result.command().operationId())
                .orElseThrow();
        assertEquals(turn, operation.intent().turnId());
        assertEquals("JOURNALED", operation.state());
        var evidence = base.json.decode(operation.preparation().orElseThrow(), CodingCommandEvidence.class);
        assertEquals(mode, evidence.networkMode());
        assertEquals("npm", result.command().argv().getFirst());
        assertTrue(Path.of(evidence.argv().getFirst()).isAbsolute(), "账本必须记录托管工具链的真实绝对入口");
        var fact = operation.facts().stream()
                .filter(value -> value.payload() instanceof CorePayloads.Command)
                .map(value -> (CorePayloads.Command) value.payload())
                .findFirst()
                .orElseThrow();
        assertEquals(evidence.argv(), fact.argv());
        assertEquals(result.command().exitCode(), fact.exitCode());
        var outputs = new CodingCommandOutputRepository(base.database, base.json)
                .read(base.workspace.id(), result.command().operationId());
        var attachments = new AttachmentService(base.database, base.json, base.clock);
        var scope = AttachmentScope.workspace(base.workspace.id());
        assertEquals(
                outputs.stdoutBytes(),
                attachments.read(scope, outputs.stdoutDigest()).content().length);
        assertEquals(
                outputs.stderrBytes(),
                attachments.read(scope, outputs.stderrDigest()).content().length);
        assertEquals(
                result.output().stdout(),
                new String(attachments.read(scope, outputs.stdoutDigest()).content(), StandardCharsets.UTF_8));
        assertEquals(
                result.output().stderr(),
                new String(attachments.read(scope, outputs.stderrDigest()).content(), StandardCharsets.UTF_8));
    }

    private static void dependencyEvidence(CodingTestFixture base, String operation) throws Exception {
        var evidence = new DependencyEvidenceRepository(base.database, base.json).read(base.workspace.id(), operation);
        assertEquals(CodingContracts.PackageManager.NPM, evidence.manager());
        assertTrue(evidence.after().isPresent());
        var manifest = artifact(evidence, "package.json", "before");
        assertEquals("manifest", manifest.kind());
        assertEquals(PublishedCodingHarnessModel.MANIFEST, content(base, manifest));
        var lock = artifact(evidence, "package-lock.json", "after");
        assertEquals("lock", lock.kind());
        assertEquals(Files.readString(base.root.resolve("package-lock.json")), content(base, lock));
        var packages = base.json
                .objectField(base.json.parse(content(base, lock)), "packages")
                .orElseThrow();
        var resolved = base.json.objectField(packages, "node_modules/is-number").orElseThrow();
        assertEquals(Optional.of("7.0.0"), base.json.textField(resolved, "version"));
        assertTrue(base.json.textField(resolved, "integrity").orElseThrow().startsWith("sha512-"));
        assertTrue(evidence.artifacts().stream().anyMatch(value -> value.kind().equals("native-output")));
        assertTrue(evidence.observedChanges().stream()
                .anyMatch(change -> change.path().equals("package-lock.json")
                        && change.beforeSha256().isEmpty()
                        && change.afterSha256().isPresent()));
        // node_modules 被有界观察显式排除；成功准备不能被误写成完整依赖图或完整项目快照。
        assertFalse(evidence.complete());
        assertTrue(
                evidence.after().orElseThrow().omissions().stream().anyMatch(value -> value.contains("node_modules")));
    }

    private static DependencyEvidence.Artifact artifact(DependencyEvidence evidence, String source, String phase) {
        var artifact = evidence.artifacts().stream()
                .filter(value -> value.source().equals(source) && value.phase().equals(phase))
                .findFirst()
                .orElseThrow();
        assertTrue(artifact.complete());
        return artifact;
    }

    private static String content(CodingTestFixture base, DependencyEvidence.Artifact artifact) {
        byte[] content = new AttachmentService(base.database, base.json, base.clock)
                .read(AttachmentScope.workspace(base.workspace.id()), artifact.sha256())
                .content();
        assertEquals(artifact.sizeBytes(), content.length);
        return new String(content, StandardCharsets.UTF_8);
    }

    private static void checkpoint(CodingHarnessFixture fixture, TurnId turn) throws Exception {
        var checkpoint = fixture.journal.readRecovery(turn);
        assertEquals(TurnExecutionPhase.MODEL_COMMITTED, checkpoint.phase());
        assertEquals(9, checkpoint.toolCalls());
        assertEquals(10, checkpoint.modelInvocations());
        assertEquals(9, checkpoint.seenCallIds().size());
        assertTrue(checkpoint.activeIntentDigest().isEmpty());
        assertEquals(PublishedCodingHarnessModel.FINAL_CHAT, checkpoint.assistantText());
        new H2Transactions(fixture.base.database).execute(connection -> {
            try (var statement = connection.prepareStatement(
                    "SELECT STATE,COUNT(*) FROM CORE.CODING_OPERATION WHERE TURN_ID=? GROUP BY STATE")) {
                statement.setString(1, turn.toString());
                try (var rows = statement.executeQuery()) {
                    assertTrue(rows.next());
                    assertEquals("JOURNALED", rows.getString(1));
                    assertEquals(8, rows.getInt(2));
                    assertFalse(rows.next());
                }
            }
            return null;
        });
    }

    private static void legacyWire(CodingTestFixture base, List<ItemEnvelope> items, TurnId id) {
        var page = new CoreRpcContracts.ItemListResult(items, items.getLast().sequence());
        assertEquals(page, base.json.decode(base.json.encode(page), CoreRpcContracts.ItemListResult.class));
        var turn = base.core.findTurn(id).orElseThrow();
        assertEquals(turn, base.json.decode(base.json.encode(turn), AgentTurn.class));
    }

    private static long count(List<ItemEnvelope> items, String schema) {
        return items.stream().filter(item -> item.schemaId().equals(schema)).count();
    }
}
