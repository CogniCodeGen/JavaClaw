package com.javaclaw.client.extension;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.TurnId;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.builtin.contracts.CodingContracts;
import com.javaclaw.builtin.contracts.CodingEnvironmentContracts;
import com.javaclaw.builtin.contracts.CodingResults;
import com.javaclaw.client.RpcClientConnection;
import com.javaclaw.protocol.CanonicalJson;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodingExecutionPollerTest {
    private static final WorkspaceId WORKSPACE = WorkspaceId.random();
    private static final TurnId TURN = TurnId.random();
    private final ScriptedExtensionConnection script = new ScriptedExtensionConnection(WORKSPACE);
    private final CodingExecutionPoller poller =
            new CodingExecutionPoller(new CodingExecutionPoller.Scope(WORKSPACE, Optional.empty(), Optional.empty()));

    @Test
    void 运行中分离通道与终态尾页共用稳定字节游标且逐页净化控制串() throws IOException {
        list(summary("RUNNING", 5));
        page(0, new CodingResults.Output("A\u001b[", "E", 4, false));
        list(summary("COMPLETED", 9));
        page(4, new CodingResults.Output("31m中", "", 9, false));
        list(summary("COMPLETED", 9));
        try (var connection = connect()) {
            var coding = new CodingExtensionClient(new ExtensionClient(connection));
            var first = poller.poll(coding).snapshots().getFirst();
            assertTrue(first.pending());
            assertEquals(4, first.nextOffsetBytes());
            assertFalse(first.appendedText().contains("\u001b"));
            var second = poller.poll(coding).snapshots().getFirst();
            assertEquals(
                    first.appendedText() + second.appendedText() + "\n退出码：0",
                    second.fact().body());
            assertTrue(second.fact().body().contains("中"));
            assertFalse(second.pending());
            assertFalse(poller.poll(coding).snapshots().getFirst().changed());
        }
        script.assertExhausted();
    }

    @Test
    void 有界尾页仍可继续读取而不重复或无限追页() throws IOException {
        list(summary("COMPLETED", 32768));
        page(0, new CodingResults.Output("a".repeat(16384), "", 16384, false));
        list(summary("COMPLETED", 32768));
        page(16384, new CodingResults.Output("b".repeat(16384), "", 32768, true));
        try (var connection = connect()) {
            var coding = new CodingExtensionClient(new ExtensionClient(connection));
            assertTrue(poller.poll(coding).pending());
            var finalPage = poller.poll(coding).snapshots().getFirst();
            assertFalse(finalPage.pending());
            assertTrue(finalPage.fact().body().contains("仅显示最近输出"));
            assertFalse(finalPage.fact().body().contains("aaa"));
            assertTrue(finalPage.appendedText().startsWith("b".repeat(16384)));
            assertTrue(finalPage.appendedText().contains("输出已达到本页或保留边界"));
        }
        script.assertExhausted();
    }

    @Test
    void 不推进的非空页和反向目录游标被拒绝() throws IOException {
        list(summary("RUNNING", 2));
        page(0, new CodingResults.Output("x", "", 0, false));
        try (var connection = connect()) {
            var coding = new CodingExtensionClient(new ExtensionClient(connection));
            assertThrows(IllegalStateException.class, () -> poller.poll(coding));
        }
        script.assertExhausted();
    }

    @Test
    void 空UNKNOWN终端显示未知状态且后续轮询不重复取空页() throws IOException {
        var unknown = new CodingResults.ExecutionSummary(
                "session-unknown", "terminal_open", TURN, "UNKNOWN_OUTCOME", 0, Optional.empty());
        list(unknown);
        terminalPage(0, new CodingResults.Output("", "", 0, false));
        list(unknown);
        try (var connection = connect()) {
            var coding = new CodingExtensionClient(new ExtensionClient(connection));
            var first = poller.poll(coding);
            var snapshot = first.snapshots().getFirst();
            assertEquals(
                    "终端 · UNKNOWN_OUTCOME · session-unknown", snapshot.fact().title());
            assertEquals("", snapshot.fact().body());
            assertEquals(0, snapshot.nextOffsetBytes());
            assertFalse(first.pending());
            assertFalse(snapshot.pending());
            assertTrue(snapshot.changed());
            // 第二次仅允许读取目录；未知结局没有原始字节，不制造提示文字页或无限重试。
            var second = poller.poll(coding);
            assertFalse(second.pending());
            assertFalse(second.snapshots().getFirst().changed());
        }
        script.assertExhausted();
    }

    @Test
    void UNKNOWN终端已有原始尾部仍按游标排空且不伪造退出码() throws IOException {
        var unknown = new CodingResults.ExecutionSummary(
                "session-unknown", "terminal_open", TURN, "UNKNOWN_OUTCOME", 6, Optional.empty());
        list(unknown);
        terminalPage(0, new CodingResults.Output("old", "", 3, false));
        list(unknown);
        terminalPage(3, new CodingResults.Output("end", "", 6, false));
        list(unknown);
        try (var connection = connect()) {
            var coding = new CodingExtensionClient(new ExtensionClient(connection));
            assertTrue(poller.poll(coding).pending());
            var last = poller.poll(coding);
            var snapshot = last.snapshots().getFirst();
            assertEquals(
                    "终端 · UNKNOWN_OUTCOME · session-unknown", snapshot.fact().title());
            assertEquals("oldend", snapshot.fact().body());
            assertEquals("end", snapshot.appendedText());
            assertEquals(6, snapshot.nextOffsetBytes());
            assertTrue(snapshot.summary().exitCode().isEmpty());
            assertFalse(last.pending());
            assertFalse(poller.poll(coding).snapshots().getFirst().changed());
        }
        script.assertExhausted();
    }

    private void terminalPage(long offset, CodingResults.Output output) {
        script.expectQuery(
                CodingContracts.EXTENSION_ID,
                "terminal/output",
                new CodingResults.OutputRead("session-unknown", offset, 16384),
                new CodingResults.TerminalResult(
                        "session-unknown", CodingResults.ProcessState.FAILED, output, Optional.empty()),
                0);
    }

    private void list(CodingResults.ExecutionSummary summary) {
        script.expectQuery(
                CodingContracts.EXTENSION_ID,
                "execution/list",
                new CodingEnvironmentContracts.Empty(),
                new CodingResults.ExecutionList(List.of(summary)),
                0);
    }

    private void page(long offset, CodingResults.Output output) {
        script.expectQuery(
                CodingContracts.EXTENSION_ID,
                "command/output",
                new CodingResults.OutputRead("run-1", offset, 16384),
                output,
                0);
    }

    private static CodingResults.ExecutionSummary summary(String state, long bytes) {
        return new CodingResults.ExecutionSummary(
                "run-1",
                "command_run",
                TURN,
                state,
                bytes,
                state.equals("COMPLETED") ? Optional.of(0) : Optional.empty());
    }

    private RpcClientConnection connect() {
        return new RpcClientConnection(script, new CanonicalJson(), ignored -> {});
    }
}
