package com.javaclaw.client.cli;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.TurnStatus;
import com.javaclaw.builtin.contracts.CodingResults;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CliCodingOutputTest {
    @Test
    void CLI按固定Turn范围持续显示准备增量且终态读取不受时钟节流() throws Exception {
        CliTestPeer peer = new CliTestPeer();
        AtomicInteger lists = new AtomicInteger();
        AtomicLong clock = new AtomicLong();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        peer.onCoding = call -> {
            if (call.operation().equals("execution/list")) {
                assertEquals(Optional.of(CliTestPeer.THREAD), call.threadId());
                assertEquals(Optional.of(CliTestPeer.TURN), call.turnId());
                assertEquals("{}", call.payload().json());
                int count = lists.incrementAndGet();
                return new CodingResults.ExecutionList(List.of(new CodingResults.ExecutionSummary(
                        "prepare-1",
                        "dependencies_prepare",
                        CliTestPeer.TURN,
                        count == 1 ? "RUNNING" : "COMPLETED",
                        count == 1 ? 6 : 11,
                        count == 1 ? Optional.empty() : Optional.of(0))));
            }
            assertEquals("preparation/output", call.operation());
            assertTrue(call.turnId().isEmpty(), "资源 ID 是服务端发现的引用，不替代权限验证");
            var request = CliTestPeer.JSON.decode(call.payload(), CodingResults.OutputRead.class);
            assertEquals(lists.get() == 1 ? 0 : 6, request.offsetBytes());
            return new CodingResults.Output(
                    lists.get() == 1 ? "first\u001b" : "final", "", lists.get() == 1 ? 6 : 11, false);
        };
        try (var client = peer.connect();
                var out = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
            CliCodingOutput observer = new CliCodingOutput(client, out, clock::get);
            observer.read(peer.current, false);
            observer.read(peer.current, false);
            assertEquals(1, lists.get());
            observer.read(CliTestPeer.turn(TurnStatus.COMPLETED, 3), true);
            assertEquals(2, lists.get());
        }
        String printed = bytes.toString(StandardCharsets.UTF_8);
        assertTrue(printed.contains("RUNNING"));
        assertTrue(printed.contains("first"));
        assertTrue(printed.contains("final"));
        assertTrue(printed.contains("退出码：0"));
        assertFalse(printed.contains("\u001b"));
        assertEquals(1, printed.split("first", -1).length - 1);
    }

    @Test
    void PTY运行中的追加输出在终态仍被排空且没有人工输入RPC() throws Exception {
        CliTestPeer peer = new CliTestPeer();
        AtomicInteger lists = new AtomicInteger();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        peer.onCoding = call -> {
            if (call.operation().equals("execution/list")) {
                lists.incrementAndGet();
                return new CodingResults.ExecutionList(List.of(new CodingResults.ExecutionSummary(
                        "session-1", "terminal_open", CliTestPeer.TURN, "COMPLETED", 5, Optional.of(1))));
            }
            assertEquals("terminal/output", call.operation());
            var request = CliTestPeer.JSON.decode(call.payload(), CodingResults.OutputRead.class);
            return new CodingResults.TerminalResult(
                    "session-1",
                    CodingResults.ProcessState.FAILED,
                    new CodingResults.Output(
                            request.offsetBytes() == 0 ? "abc" : "de", "", request.offsetBytes() == 0 ? 3 : 5, false),
                    Optional.of(1));
        };
        try (var client = peer.connect();
                var out = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
            new CliCodingOutput(client, out).read(CliTestPeer.turn(TurnStatus.COMPLETED, 3), true);
        }
        assertEquals(2, lists.get());
        assertTrue(bytes.toString(StandardCharsets.UTF_8).contains("abc"));
        assertTrue(bytes.toString(StandardCharsets.UTF_8).contains("de"));
        assertTrue(bytes.toString(StandardCharsets.UTF_8).contains("退出码：1"));
    }
}
