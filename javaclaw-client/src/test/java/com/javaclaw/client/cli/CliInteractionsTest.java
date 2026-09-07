package com.javaclaw.client.cli;

import java.io.ByteArrayOutputStream;
import java.io.PipedReader;
import java.io.PipedWriter;
import java.io.PrintStream;
import java.io.Reader;
import java.io.StringReader;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.ApprovalDecision;
import com.javaclaw.api.ApprovalState;
import com.javaclaw.api.InputRequestRecord;
import com.javaclaw.api.InputRequestState;
import com.javaclaw.protocol.CoreRpcContracts;
import com.javaclaw.protocol.InputJobRpcContracts;
import com.javaclaw.protocol.ProtocolErrorCode;
import com.javaclaw.protocol.ProtocolException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CliInteractionsTest {
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private final PrintStream output = new PrintStream(bytes);
    private final Clock clock = Clock.fixed(CliTestPeer.NOW, ZoneOffset.UTC);

    @Test
    void 空行不会批准且人工必须明确选择() throws Exception {
        for (String answer : List.of("approve", "deny")) {
            CliTestPeer peer = new CliTestPeer();
            peer.approvals = List.of(CliTestPeer.approval(1, ApprovalState.PENDING));
            peer.onApproval = command -> {
                var payload = CliTestPeer.JSON.decode(command.payload(), CoreRpcContracts.ApprovalResolvePayload.class);
                assertEquals(
                        answer.equals("approve") ? ApprovalDecision.APPROVED : ApprovalDecision.DENIED,
                        payload.decision());
                assertEquals(1, command.expectedRevision());
                peer.approvals = List.of();
                return CliTestPeer.approval(
                        2, answer.equals("approve") ? ApprovalState.APPROVED : ApprovalState.DENIED);
            };
            try (var client = peer.connect();
                    var interaction = new CliInteractions(
                            client,
                            CliTestPeer.TURN,
                            output,
                            clock,
                            new CliTerminal(true, new StringReader("\n" + answer + "\n")))) {
                drive(interaction, () -> peer.resolutions == 1);
                assertEquals(1, peer.resolutions);
                assertTrue(bytes.toString().contains("空行不会批准"));
            }
        }
    }

    @Test
    void 单行JSON保留类型且非法输入不进入RPC() throws Exception {
        CliTestPeer peer = new CliTestPeer();
        var input = CliTestPeer.input(1);
        peer.inputs = List.of(input);
        peer.onInput = command -> {
            var payload = CliTestPeer.JSON.decode(command.payload(), InputJobRpcContracts.InputResolvePayload.class);
            assertEquals(CliTestPeer.JSON.parse("{\"count\":3,\"ready\":true}"), payload.response());
            assertEquals(1, command.expectedRevision());
            peer.inputs = List.of();
            return new InputRequestRecord(
                    input.request(),
                    InputRequestState.RESOLVED,
                    2,
                    Optional.of(payload.response()),
                    Optional.empty(),
                    CliTestPeer.NOW);
        };
        String answers = "invalid\n{}\n{\"count\":\"3\",\"ready\":true}\n"
                + "{\"count\":3,\"ready\":true,\"extra\":1}\n{\"count\":3,\"ready\":true}\n";
        try (var client = peer.connect();
                var interaction = new CliInteractions(
                        client, CliTestPeer.TURN, output, clock, new CliTerminal(true, new StringReader(answers)))) {
            drive(interaction, () -> peer.resolutions == 1);
            assertEquals(1, peer.resolutions);
            assertTrue(bytes.toString().contains("输入已提交"));
        }
    }

    @Test
    void 请求更新后的迟到批准不能用于新版本() throws Exception {
        CliTestPeer peer = new CliTestPeer();
        peer.approvals = List.of(CliTestPeer.approval(1, ApprovalState.PENDING));
        peer.onApproval = command -> {
            assertEquals(2, command.expectedRevision());
            var payload = CliTestPeer.JSON.decode(command.payload(), CoreRpcContracts.ApprovalResolvePayload.class);
            assertEquals(ApprovalDecision.DENIED, payload.decision());
            peer.approvals = List.of();
            return CliTestPeer.approval(3, ApprovalState.DENIED);
        };
        try (PipedWriter writer = new PipedWriter();
                PipedReader reader = new PipedReader(writer);
                var client = peer.connect();
                var interaction =
                        new CliInteractions(client, CliTestPeer.TURN, output, clock, new CliTerminal(true, reader))) {
            interaction.observe();
            peer.approvals = List.of(CliTestPeer.approval(2, ApprovalState.PENDING));
            interaction.observe();
            writer.write("approve\n");
            writer.flush();
            drive(interaction, () -> bytes.toString().contains("迟到输入"));
            assertEquals(0, peer.resolutions);
            writer.write("deny\n");
            writer.flush();
            drive(interaction, () -> peer.resolutions == 1);
        }
    }

    @Test
    void revision冲突不会自动重投旧批准() throws Exception {
        CliTestPeer peer = new CliTestPeer();
        peer.approvals = List.of(CliTestPeer.approval(1, ApprovalState.PENDING));
        peer.onApproval = command -> {
            peer.approvals = List.of(CliTestPeer.approval(2, ApprovalState.PENDING));
            throw new ProtocolException(ProtocolErrorCode.REVISION_CONFLICT, "revision 已改变");
        };
        try (PipedWriter writer = new PipedWriter();
                PipedReader reader = new PipedReader(writer);
                var client = peer.connect();
                var interaction =
                        new CliInteractions(client, CliTestPeer.TURN, output, clock, new CliTerminal(true, reader))) {
            interaction.observe();
            writer.write("approve\n");
            writer.flush();
            drive(interaction, () -> peer.resolutions == 1);
            interaction.observe();
            interaction.observe();
            assertEquals(1, peer.resolutions);
            assertTrue(bytes.toString().contains("请求未采纳"));
        }
    }

    @Test
    void RPC成功返回过期不能宣称批准成功() throws Exception {
        CliTestPeer peer = new CliTestPeer();
        peer.approvals = List.of(CliTestPeer.approval(1, ApprovalState.PENDING));
        peer.onApproval = command -> {
            peer.approvals = List.of();
            return CliTestPeer.approval(2, ApprovalState.EXPIRED);
        };
        try (var client = peer.connect();
                var interaction = new CliInteractions(
                        client,
                        CliTestPeer.TURN,
                        output,
                        clock,
                        new CliTerminal(true, new StringReader("approve\n")))) {
            drive(interaction, () -> peer.resolutions == 1);
            assertTrue(bytes.toString().contains("决议未采纳"));
            assertFalse(bytes.toString().contains("审批结果：APPROVED"));
        }
    }

    @Test
    void 非交互审批明确拒绝而必需输入要求取消且不读取管道() throws Exception {
        CliTestPeer peer = new CliTestPeer();
        peer.approvals = List.of(CliTestPeer.approval(1, ApprovalState.PENDING));
        peer.onApproval = command -> {
            var payload = CliTestPeer.JSON.decode(command.payload(), CoreRpcContracts.ApprovalResolvePayload.class);
            assertEquals(ApprovalDecision.DENIED, payload.decision());
            assertTrue(payload.reason().contains("无人工交互宿主"));
            peer.approvals = List.of();
            return CliTestPeer.approval(2, ApprovalState.DENIED);
        };
        try (var client = peer.connect();
                var interaction = new CliInteractions(
                        client, CliTestPeer.TURN, output, clock, new CliTerminal(false, forbiddenReader()))) {
            assertTrue(interaction.observe().isEmpty());
            assertEquals(1, peer.resolutions);
            assertEquals(0, peer.cancellations);
            peer.inputs = List.of(CliTestPeer.input(1));
            assertEquals(2, interaction.observe().orElseThrow().code());
        }
    }

    @Test
    void 输入决议返回过期时说明未采纳() throws Exception {
        CliTestPeer peer = new CliTestPeer();
        var input = CliTestPeer.input(1);
        peer.inputs = List.of(input);
        peer.onInput = command -> {
            peer.inputs = List.of();
            return new InputRequestRecord(
                    input.request(),
                    InputRequestState.EXPIRED,
                    2,
                    Optional.empty(),
                    Optional.of("已过期"),
                    CliTestPeer.NOW);
        };
        try (var client = peer.connect();
                var interaction = new CliInteractions(
                        client,
                        CliTestPeer.TURN,
                        output,
                        clock,
                        new CliTerminal(true, new StringReader("{\"count\":3,\"ready\":true}\n")))) {
            drive(interaction, () -> peer.resolutions == 1);
            assertTrue(bytes.toString().contains("输入未采纳：EXPIRED"));
            assertFalse(bytes.toString().contains("输入已提交"));
        }
    }

    @Test
    void 已过期请求不再收集终端输入() throws Exception {
        CliTestPeer peer = new CliTestPeer();
        peer.approvals = List.of(CliTestPeer.approval(1, ApprovalState.PENDING));
        peer.inputs = List.of(CliTestPeer.input(1));
        Clock expired = Clock.fixed(CliTestPeer.NOW.plusSeconds(61), ZoneOffset.UTC);
        try (var client = peer.connect();
                var interaction = new CliInteractions(
                        client, CliTestPeer.TURN, output, expired, new CliTerminal(true, forbiddenReader()))) {
            assertTrue(interaction.observe().isEmpty());
            assertEquals(0, peer.resolutions);
            assertEquals("", bytes.toString());
        }
    }

    private static Reader forbiddenReader() {
        return new Reader() {
            @Override
            public int read(char[] buffer, int offset, int length) {
                throw new AssertionError("非 TTY 不得读取 stdin");
            }

            @Override
            public void close() {}
        };
    }

    private static void drive(CliInteractions interaction, BooleanSupplier completed) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
        while (!completed.getAsBoolean() && System.nanoTime() < deadline) {
            interaction.observe();
            Thread.sleep(1);
        }
        assertTrue(completed.getAsBoolean(), "异步终端交互没有在期限内完成");
    }
}
