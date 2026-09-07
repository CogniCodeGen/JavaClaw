package com.javaclaw.client.cli;

import java.io.ByteArrayOutputStream;
import java.io.PipedReader;
import java.io.PipedWriter;
import java.io.PrintStream;
import java.io.Reader;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.javaclaw.api.ApprovalState;
import com.javaclaw.api.TurnStatus;
import com.javaclaw.protocol.ProtocolErrorCode;
import com.javaclaw.protocol.ProtocolException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(5)
class CliTurnRunnerTest {
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private final PrintStream output = new PrintStream(bytes);

    @Test
    void 前台保持连接至终态并输出最后一轮模型消息() throws Exception {
        CliTestPeer peer = new CliTestPeer();
        peer.onRead = () -> {
            assertFalse(peer.closed);
            peer.current = CliTestPeer.turn(peer.reads < 3 ? TurnStatus.RUNNING : TurnStatus.COMPLETED, 3);
            return peer.current;
        };
        try (var client = peer.connect()) {
            var result = runner(client).run(request(), new CliTerminal(false, Reader.nullReader()));
            assertEquals(0, result.exitCode());
            assertEquals(TurnStatus.COMPLETED, result.turn().orElseThrow().status());
            assertTrue(bytes.toString().contains("任务完成"));
            assertFalse(peer.closed);
        }
        assertTrue(peer.closed);
    }

    @Test
    void 阻塞终端输入不阻止观察到其他客户端完成Turn() throws Exception {
        CliTestPeer peer = new CliTestPeer();
        peer.approvals = List.of(CliTestPeer.approval(1, ApprovalState.PENDING));
        peer.onRead = () -> {
            peer.current = CliTestPeer.turn(peer.reads < 5 ? TurnStatus.WAITING : TurnStatus.COMPLETED, 3);
            return peer.current;
        };
        try (PipedWriter writer = new PipedWriter();
                PipedReader reader = new PipedReader(writer);
                var client = peer.connect()) {
            var result = runner(client).run(request(), new CliTerminal(true, reader));
            assertEquals(0, result.exitCode());
            assertEquals(0, peer.resolutions);
            assertTrue(bytes.toString().contains("等待审批"));
        }
    }

    @Test
    void 终端EOF取消Turn而非TTY必需输入退出二() throws Exception {
        for (boolean interactive : List.of(true, false)) {
            CliTestPeer peer = new CliTestPeer();
            peer.inputs = List.of(CliTestPeer.input(1));
            try (var client = peer.connect()) {
                var result = runner(client).run(request(), new CliTerminal(interactive, Reader.nullReader()));
                assertEquals(interactive ? 130 : 2, result.exitCode());
                assertEquals(1, peer.cancellations);
                assertEquals(TurnStatus.CANCELLED, result.turn().orElseThrow().status());
            }
        }
    }

    @Test
    void 失败Turn返回一并显示稳定错误码() throws Exception {
        CliTestPeer peer = new CliTestPeer();
        peer.current = CliTestPeer.turn(TurnStatus.FAILED, 3);
        try (var client = peer.connect()) {
            assertEquals(
                    1,
                    runner(client)
                            .run(request(), new CliTerminal(false, Reader.nullReader()))
                            .exitCode());
            assertTrue(bytes.toString().contains("TEST_FAILED"));
        }
    }

    @Test
    void 显式非交互模式拒绝审批后继续等待Harness结果() throws Exception {
        CliTestPeer peer = new CliTestPeer();
        peer.approvals = List.of(CliTestPeer.approval(1, ApprovalState.PENDING));
        peer.onApproval = command -> {
            var payload = CliTestPeer.JSON.decode(
                    command.payload(), com.javaclaw.protocol.CoreRpcContracts.ApprovalResolvePayload.class);
            assertEquals(com.javaclaw.api.ApprovalDecision.DENIED, payload.decision());
            peer.approvals = List.of();
            peer.current = CliTestPeer.turn(TurnStatus.COMPLETED, 4);
            return CliTestPeer.approval(2, ApprovalState.DENIED);
        };
        var request =
                CliTurnRequest.parse(List.of("turn-start", CliTestPeer.THREAD.toString(), "测试", "--non-interactive"));
        try (var client = peer.connect()) {
            var result = runner(client).run(request, new CliTerminal(true, new java.io.StringReader("approve\n")));
            assertEquals(0, result.exitCode());
            assertEquals(1, peer.resolutions);
            assertEquals(0, peer.cancellations);
            assertTrue(bytes.toString().contains("任务完成"));
        }
    }

    @Test
    void 取消冲突刷新版本而取消失败不谎报成功() throws Exception {
        CliTestPeer peer = new CliTestPeer();
        peer.onCancel = command -> {
            if (peer.cancellations == 1) {
                peer.current = CliTestPeer.turn(TurnStatus.WAITING, 3);
                throw new ProtocolException(ProtocolErrorCode.REVISION_CONFLICT, "revision 已改变");
            }
            assertEquals(3, command.expectedRevision());
            peer.current = CliTestPeer.turn(TurnStatus.CANCELLED, 4);
            return peer.current;
        };
        try (var client = peer.connect()) {
            var cancellation = new CliTurnCancellation(client, CliTestPeer.TURN, output, Duration.ofSeconds(1));
            assertEquals(
                    TurnStatus.CANCELLED,
                    cancellation.cancel("测试取消").orElseThrow().status());
            assertEquals(2, peer.cancellations);
        }
        bytes.reset();
        CliTestPeer failed = new CliTestPeer();
        failed.onCancel = command -> {
            throw new ProtocolException(ProtocolErrorCode.PERMISSION_DENIED, "无法取消");
        };
        try (var client = failed.connect()) {
            var cancellation = new CliTurnCancellation(client, CliTestPeer.TURN, output, Duration.ofMillis(100));
            assertTrue(cancellation.cancel("测试取消").isEmpty());
            assertTrue(bytes.toString().contains("取消结果未确认"));
            assertFalse(bytes.toString().contains("已进入终态"));
        }
    }

    private CliTurnRunner runner(com.javaclaw.client.sdk.JavaClawClient client) {
        return new CliTurnRunner(
                client,
                output,
                Clock.fixed(CliTestPeer.NOW, ZoneOffset.UTC),
                Duration.ofMillis(2),
                Duration.ofSeconds(1));
    }

    @Test
    void 观察查询失败带Turn标识并有界取消() throws Exception {
        CliTestPeer peer = new CliTestPeer();
        peer.onRead = () -> {
            if (peer.reads == 1) {
                throw new ProtocolException(ProtocolErrorCode.INTERNAL_ERROR, "测试查询失败");
            }
            return peer.current;
        };
        try (var client = peer.connect()) {
            var result = runner(client).run(request(), new CliTerminal(false, Reader.nullReader()));
            assertEquals(1, result.exitCode());
            assertEquals(TurnStatus.CANCELLED, result.turn().orElseThrow().status());
            assertEquals(1, peer.cancellations);
            assertTrue(bytes.toString().contains("Turn " + CliTestPeer.TURN + " 观察失败"));
        }
    }

    @Test
    void 已观察到终态时退出钩子不再发取消RPC() throws Exception {
        CliTestPeer peer = new CliTestPeer();
        try (var client = peer.connect()) {
            var cancellation = new CliTurnCancellation(client, CliTestPeer.TURN, output, Duration.ofMillis(100));
            cancellation.confirm(CliTestPeer.turn(TurnStatus.COMPLETED, 3));
            assertEquals(
                    TurnStatus.COMPLETED,
                    cancellation.cancel("退出钩子").orElseThrow().status());
            assertEquals(0, peer.reads);
            assertEquals(0, peer.cancellations);
        }
    }

    private static CliTurnRequest request() {
        return CliTurnRequest.parse(List.of("turn-start", CliTestPeer.THREAD.toString(), "测试"));
    }
}
