package com.javaclaw.server.coding;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.ThreadId;
import com.javaclaw.api.TurnId;
import com.javaclaw.builtin.contracts.CodingEnvironmentContracts;
import com.javaclaw.builtin.contracts.CodingResults;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 管理面可在工具返回前发现真实操作和已提交页，并维持 Workspace/Thread/Turn 归属。 */
class CodingExecutionQueriesTest {
    @TempDir
    Path directory;

    @Test
    void 准备态和执行态可发现且不要求最终工具结果() throws Exception {
        try (var fixture = new CodingStreamingFixture(directory)) {
            fixture.prepare("before-spawn", "dependencies_prepare", fixture.base.turn.id());
            var initial = list(fixture, Optional.empty(), Optional.empty());
            assertEquals(1, initial.executions().size());
            assertEquals("before-spawn", initial.executions().getFirst().operationId());
            assertEquals("RUNNING", initial.executions().getFirst().state());
            assertEquals(0, initial.executions().getFirst().outputBytes());
            assertEquals(
                    0,
                    fixture.output("preparation/output", "before-spawn", 0, 100).nextOffsetBytes());
            var command = fixture.start("command", "command_run", 100);
            fixture.streams.append(command, "stdout", "building".getBytes(StandardCharsets.UTF_8));
            var running = list(fixture, Optional.of(fixture.base.turn.threadId()), Optional.of(fixture.base.turn.id()));
            var summary = running.executions().stream()
                    .filter(value -> value.operationId().equals("command"))
                    .findFirst()
                    .orElseThrow();
            assertEquals("RUNNING", summary.state());
            assertEquals(8, summary.outputBytes());
            assertTrue(summary.exitCode().isEmpty());
            assertEquals(
                    "building",
                    fixture.output("command/output", "command", 0, 100).stdout());
            assertThrows(SecurityException.class, () -> fixture.output("preparation/output", "command", 0, 100));
        }
    }

    @Test
    void 同Workspace其他Thread不能枚举或读取当前Thread的执行() throws Exception {
        try (var fixture = new CodingStreamingFixture(directory)) {
            fixture.start("first", "command_run", 100);
            var other = fixture.base.createTurn("other-thread");
            fixture.prepare("second", "dependencies_prepare", other.id());
            assertEquals(
                    2,
                    list(fixture, Optional.empty(), Optional.empty())
                            .executions()
                            .size());
            var selected = list(fixture, Optional.of(other.threadId()), Optional.of(other.id()));
            assertEquals(
                    List.of("second"),
                    selected.executions().stream()
                            .map(CodingResults.ExecutionSummary::operationId)
                            .toList());
            assertTrue(list(fixture, Optional.of(ThreadId.random()), Optional.empty())
                    .executions()
                    .isEmpty());
            assertTrue(list(fixture, Optional.empty(), Optional.of(TurnId.random()))
                    .executions()
                    .isEmpty());
            assertTrue(list(fixture, Optional.of(other.threadId()), Optional.of(fixture.base.turn.id()))
                    .executions()
                    .isEmpty());
            assertThrows(
                    SecurityException.class,
                    () -> fixture.query(
                            "command/output",
                            new CodingResults.OutputRead("first", 0, 100),
                            Optional.of(other.threadId()),
                            Optional.empty()));
            assertThrows(
                    SecurityException.class,
                    () -> fixture.query(
                            "command/output",
                            new CodingResults.OutputRead("first", 0, 100),
                            Optional.empty(),
                            Optional.of(other.id())));
        }
    }

    @Test
    void 结果未知保留已读游标而完成状态使用真实退出码() throws Exception {
        try (var fixture = new CodingStreamingFixture(directory)) {
            var unknown = fixture.start("unknown", "command_run", 100);
            fixture.streams.append(unknown, "stderr", "partial".getBytes(StandardCharsets.UTF_8));
            fixture.operations.unknown("unknown");
            var completed = fixture.start("done", "command_run", 100);
            completed = fixture.streams.append(completed, "stdout", new byte[] {1});
            fixture.streams.complete(completed, "FAILED", 17);
            var summary = new CodingResults.CommandSummary(
                    "done", List.of("java", "Main"), ".", Optional.of(17), CodingResults.ProcessState.FAILED, 10);
            var result = new CodingResults.CommandResult(summary, new CodingResults.Output("\u0001", "", 1, false));
            fixture.operations.finish("done", fixture.base.json.encode(result), List.of(), false);
            var rows = list(fixture, Optional.empty(), Optional.empty()).executions();
            var failed = rows.stream()
                    .filter(value -> value.operationId().equals("done"))
                    .findFirst()
                    .orElseThrow();
            assertEquals("FAILED", failed.state());
            assertEquals(Optional.of(17), failed.exitCode());
            var interrupted = rows.stream()
                    .filter(value -> value.operationId().equals("unknown"))
                    .findFirst()
                    .orElseThrow();
            assertEquals("UNKNOWN_OUTCOME", interrupted.state());
            assertTrue(interrupted.exitCode().isEmpty());
            assertEquals(7, interrupted.outputBytes());
            assertEquals(
                    "partial",
                    fixture.output("command/output", "unknown", 0, 100).stderr());
            assertEquals(7, fixture.output("command/output", "unknown", 7, 100).nextOffsetBytes());
        }
    }

    @Test
    void 最近一百条上限在范围过滤之后执行且不夹带文件工具() throws Exception {
        try (var fixture = new CodingStreamingFixture(directory)) {
            for (int index = 0; index < 101; index++) {
                fixture.prepare("operation-" + index, "command_run", fixture.base.turn.id());
            }
            fixture.prepare("file", "file_read", fixture.base.turn.id());
            var rows = list(fixture, Optional.empty(), Optional.empty()).executions();
            assertEquals(100, rows.size());
            assertEquals("operation-100", rows.getFirst().operationId());
            assertFalse(rows.stream().anyMatch(value -> value.operationId().equals("operation-0")));
            assertFalse(rows.stream().anyMatch(value -> value.operationId().equals("file")));
            var other = fixture.base.createTurn("filtered");
            fixture.prepare("filtered", "dependencies_prepare", other.id());
            assertEquals(
                    1,
                    list(fixture, Optional.of(other.threadId()), Optional.empty())
                            .executions()
                            .size());
        }
    }

    @Test
    void 权限撤销后列表和已有输出标识都不能继续提供证据() throws Exception {
        try (var fixture = new CodingStreamingFixture(directory)) {
            var owner = fixture.start("revoked", "command_run", 100);
            fixture.streams.append(owner, "stdout", new byte[] {1});
            assertEquals(
                    1,
                    list(fixture, Optional.empty(), Optional.empty())
                            .executions()
                            .size());
            var old = fixture.base.permission;
            var revoked = new com.javaclaw.api.PermissionProfile(
                    old.id(),
                    old.version() + 1,
                    new com.javaclaw.api.FilePermission(List.of(), List.of(), false, false),
                    old.network(),
                    old.processes(),
                    old.tools(),
                    old.resources());
            fixture.base.profiles.update(
                    new com.javaclaw.server.persistence.CommandIdentity(
                            "permissionProfile/update",
                            "revoke-output",
                            old.version(),
                            fixture.base.json.encode(revoked).sha256()),
                    revoked);
            assertThrows(SecurityException.class, () -> list(fixture, Optional.empty(), Optional.empty()));
            assertThrows(SecurityException.class, () -> fixture.output("command/output", "revoked", 0, 100));
        }
    }

    @Test
    void 终端使用真实会话状态与同一标识且启动前失败不会伪造退出码() throws Exception {
        try (var fixture = new CodingStreamingFixture(directory)) {
            fixture.prepare("terminal", "terminal_open", fixture.base.turn.id());
            var records = new com.javaclaw.server.persistence.CodingTerminalRepository(
                    fixture.base.database, fixture.attachments, fixture.base.json, fixture.base.clock);
            var operation = fixture.operations
                    .find(fixture.base.workspace.id(), "terminal")
                    .orElseThrow();
            records.create(
                    operation.intent(),
                    new com.javaclaw.api.CorePayloads.Command(
                            "terminal", List.of("java", "Main"), fixture.base.root, Optional.empty()));
            records.state("terminal", "RUNNING", Optional.empty());
            assertEquals(
                    "RUNNING",
                    list(fixture, Optional.empty(), Optional.empty())
                            .executions()
                            .getFirst()
                            .state());
            records.state("terminal", "CANCELLED", Optional.of(-1));
            var terminal = list(fixture, Optional.empty(), Optional.empty())
                    .executions()
                    .getFirst();
            assertEquals("terminal", terminal.operationId());
            assertEquals("CANCELLED", terminal.state());
            assertEquals(Optional.of(-1), terminal.exitCode());
            fixture.prepare("preflight", "command_run", fixture.base.turn.id());
            fixture.operations.finish(
                    "preflight",
                    fixture.base.json.encode(
                            new CodingResults.Failure("TOOLCHAIN_MISSING", "missing", "preflight", true)),
                    List.of(),
                    false);
            var failed = list(fixture, Optional.empty(), Optional.empty())
                    .executions()
                    .getFirst();
            assertEquals("FAILED", failed.state());
            assertTrue(failed.exitCode().isEmpty());
            assertEquals(
                    0, fixture.output("command/output", "preflight", 0, 100).nextOffsetBytes());
        }
    }

    @Test
    void 旧版最终工具结果未提交时仍可读取已保存CAS且不重建流或重启() throws Exception {
        try (var fixture = new CodingStreamingFixture(directory)) {
            for (String kind : List.of("command_run", "dependencies_prepare")) {
                fixture.prepare(kind, kind, fixture.base.turn.id());
                fixture.operations.start(kind);
                String stdout = legacyBlob(fixture, kind + "-out", "旧");
                String stderr = legacyBlob(fixture, kind + "-err", "档");
                new com.javaclaw.server.persistence.CodingCommandOutputRepository(
                                fixture.base.database, fixture.base.json)
                        .record(
                                fixture.base.workspace.id(),
                                kind,
                                new com.javaclaw.server.persistence.CodingCommandOutputRepository.Output(
                                        stdout, 3, stderr, 3));
                fixture.operations.unknown(kind);
                String query = kind.equals("command_run") ? "command/output" : "preparation/output";
                var out = new StringBuilder();
                var err = new StringBuilder();
                for (int offset = 0; offset < 6; offset++) {
                    var page = fixture.output(query, kind, offset, 1);
                    assertEquals(offset + 1, page.nextOffsetBytes());
                    out.append(page.stdout());
                    err.append(page.stderr());
                }
                assertEquals("旧", out.toString());
                assertEquals("档", err.toString());
                assertTrue(fixture.streams
                        .find(fixture.base.workspace.id(), fixture.base.turn.id(), kind)
                        .isEmpty());
            }
            assertTrue(list(fixture, Optional.empty(), Optional.empty()).executions().stream()
                    .allMatch(value -> value.state().equals("UNKNOWN_OUTCOME") && value.outputBytes() == 6));
        }
    }

    private static String legacyBlob(CodingStreamingFixture fixture, String key, String content) {
        var payload = fixture.base.json.encode(java.util.Map.of("key", key));
        return fixture.attachments
                .store(
                        com.javaclaw.api.AttachmentScope.workspace(fixture.base.workspace.id()),
                        new com.javaclaw.server.persistence.CommandIdentity(
                                "test/legacy-output", key, 0, payload.sha256()),
                        "application/octet-stream",
                        content.getBytes(StandardCharsets.UTF_8))
                .digest();
    }

    private static CodingResults.ExecutionList list(
            CodingStreamingFixture fixture, Optional<ThreadId> thread, Optional<TurnId> turn) throws Exception {
        return fixture.base.json.decode(
                fixture.query("execution/list", new CodingEnvironmentContracts.Empty(), thread, turn),
                CodingResults.ExecutionList.class);
    }
}
