package com.javaclaw.agent.tool;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.agent.runtime.ItemSink;
import com.javaclaw.agent.runtime.TurnExecutionContext;
import com.javaclaw.core.api.AgentThread;
import com.javaclaw.core.api.AgentTurn;
import com.javaclaw.core.api.ApprovalPolicy;
import com.javaclaw.core.api.AttemptId;
import com.javaclaw.core.api.ItemDelta;
import com.javaclaw.core.api.ItemId;
import com.javaclaw.core.api.ModelToolCall;
import com.javaclaw.core.api.StoredItem;
import com.javaclaw.core.api.ThreadId;
import com.javaclaw.core.api.ThreadItem;
import com.javaclaw.core.api.ThreadStatus;
import com.javaclaw.core.api.TurnConfig;
import com.javaclaw.core.api.TurnId;
import com.javaclaw.core.api.TurnInput;
import com.javaclaw.core.api.TurnStatus;
import com.javaclaw.core.api.TurnSteering;
import com.javaclaw.sandbox.api.SandboxCommand;
import com.javaclaw.sandbox.api.SandboxExecutor;
import com.javaclaw.sandbox.api.SandboxPolicy;
import com.javaclaw.sandbox.api.SandboxResult;
import com.javaclaw.sandbox.api.SandboxSession;
import com.javaclaw.sandbox.api.SandboxSessionFrame;
import com.javaclaw.sandbox.api.SandboxSessionOptions;
import com.javaclaw.sandbox.api.SandboxSignal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerminalToolProviderTest {
    @TempDir
    Path temporary;

    @Test
    void metadataCatalogMatchesSnapshotWithoutOpeningAProcessOrAllocatingATurn() throws Exception {
        var provider = new TerminalToolProvider(policy());
        var catalog = provider.catalog();
        assertEquals(6, catalog.size());
        try (var snapshot = provider.snapshot(turn("CHAT"))) {
            assertEquals(
                    catalog,
                    snapshot.tools().stream().map(RegisteredTool::descriptor).toList());
        }
    }

    @Test
    void inputIsApprovedAndSplitOutputHasOneDurableCommandLifecycle() throws Exception {
        var approvals = new AtomicInteger();
        var process = new Process();
        var sink = new RecordingSink();
        try (var runtime = runtime(process, approvals, true);
                var tools = runtime.open(turn("CHAT"), sink)) {
            var opened = tools.execute(new ModelToolCall("open", "terminal_open", "{\"argv\":[\"sh\"]}"));
            String id = assertInstanceOf(ThreadItem.DynamicToolCall.class, opened.item())
                    .result()
                    .get("sessionId");
            assertTrue(id.startsWith("pty_"));
            assertEquals(1, approvals.get());
            assertTrue(sink.deltas.isEmpty(), "未结束的凭据行不能提前变成 delta");
            var completed = tools.execute(new ModelToolCall(
                    "input", "terminal_input", "{\"sessionId\":\"" + id + "\",\"text\":\"continue\\n\"}"));
            assertTrue(completed.modelContent().contains("EXITED:0"));
            assertEquals(2, approvals.get(), "后续输入必须再次经过治理，而非沿用 PTY 启动许可");
            assertEquals(List.of("中文 password=[REDACTED]\n"), sink.deltas);
            var commands = sink.items.stream()
                    .filter(ThreadItem.CommandExecution.class::isInstance)
                    .toList();
            assertEquals(1, commands.size());
            assertEquals("中文 password=[REDACTED]\n", ((ThreadItem.CommandExecution) commands.getFirst()).stdout());
            assertTrue(process.terminated);
            assertFalse(sink.items.stream().anyMatch(ThreadItem.ErrorItem.class::isInstance));
        }
    }

    @Test
    void denialPlanAndForeignTurnCannotAcquireOrBorrowTerminal() throws Exception {
        var process = new Process();
        try (var runtime = runtime(process, new AtomicInteger(), false);
                var tools = runtime.open(turn("CHAT"), ignored -> null);
                var plan = runtime.open(turn("PLAN"), ignored -> null)) {
            assertTrue(tools.execute(new ModelToolCall("denied", "terminal_open", "{\"argv\":[\"sh\"]}"))
                    .modelContent()
                    .contains("denied"));
            assertEquals(0, process.opens);
            assertTrue(
                    plan.availableTools().stream().noneMatch(tool -> tool.name().equals("terminal_open")));
            assertTrue(
                    tools.execute(new ModelToolCall("foreign", "terminal_read", "{\"sessionId\":\"pty_other_turn\"}"))
                            .modelContent()
                            .contains("does not belong"));
        }
    }

    @Test
    void closingTurnTerminatesOwnedProcessAndFailsUnfinishedItemOnce() throws Exception {
        var process = new Process();
        var sink = new RecordingSink();
        try (var runtime = runtime(process, new AtomicInteger(), true)) {
            var tools = runtime.open(turn("CHAT"), sink);
            tools.execute(new ModelToolCall("open", "terminal_open", "{\"argv\":[\"sh\"]}"));
            tools.close();
            tools.close();
            assertTrue(process.terminated);
            assertEquals(
                    1,
                    sink.items.stream()
                            .filter(ThreadItem.ErrorItem.class::isInstance)
                            .count());
            assertTrue(sink.items.stream().noneMatch(ThreadItem.CommandExecution.class::isInstance));
        }
    }

    private GovernedToolRuntime runtime(Process process, AtomicInteger approvals, boolean allow) {
        return new GovernedToolRuntime(
                List.of(new TerminalToolProvider(policy())),
                List.of(),
                List.of(),
                (request, announce) -> {
                    approvals.incrementAndGet();
                    return allow;
                },
                process,
                policy(),
                Duration.ofSeconds(1),
                new ObjectMapper());
    }

    private SandboxPolicy policy() {
        return SandboxPolicy.workspaceWrite(Set.of(temporary), Set.of(temporary), Set.of(temporary.resolve(".git")));
    }

    private TurnExecutionContext turn(String kind) {
        var now = Instant.now();
        var threadId = ThreadId.random();
        var config = new TurnConfig(
                "model",
                "provider",
                "medium",
                temporary,
                policy(),
                ApprovalPolicy.ON_REQUEST,
                Set.of(),
                Map.of("profileKind", kind));
        var thread = new AgentThread(
                threadId, "workspace", null, null, "", temporary, ThreadStatus.ACTIVE, 0, 0, 1, now, now);
        var turn = new AgentTurn(
                TurnId.random(),
                threadId,
                AttemptId.random(),
                TurnStatus.IN_PROGRESS,
                List.of(new TurnInput.Text("test")),
                config,
                null,
                now,
                null);
        return new TurnExecutionContext(thread, turn, List.of(), new AtomicBoolean(), TurnSteering.NONE);
    }

    private static final class RecordingSink implements ItemSink {
        private final List<ThreadItem> items = new ArrayList<>();
        private final List<String> deltas = new ArrayList<>();

        @Override
        public StoredItem append(ThreadItem item) {
            items.add(item);
            return null;
        }

        @Override
        public ItemEmitter start(String kind) {
            ItemEmitter delegate = ItemSink.super.start(kind);
            return new ItemEmitter() {
                @Override
                public ItemId id() {
                    return delegate.id();
                }

                @Override
                public void delta(ItemDelta value) {
                    delegate.delta(value);
                    deltas.add(value.text());
                }

                @Override
                public StoredItem complete(ThreadItem item) {
                    return delegate.complete(item);
                }

                @Override
                public StoredItem fail(String code, String message, boolean retryable) {
                    return delegate.fail(code, message, retryable);
                }
            };
        }
    }

    private static final class Process implements SandboxExecutor, SandboxSession {
        private final ArrayDeque<SandboxSessionFrame> frames = new ArrayDeque<>();
        private int opens;
        private boolean terminated;

        @Override
        public SandboxResult execute(SandboxCommand command) {
            throw new AssertionError("PTY 不能退化为短命令或主进程执行");
        }

        @Override
        public SandboxSession openSession(SandboxCommand command, SandboxSessionOptions options) {
            assertTrue(options.pseudoTerminal());
            assertEquals(SandboxCommand.AuxiliaryRole.NONE, command.auxiliaryRole());
            opens++;
            frames.add(SandboxSessionFrame.stream(
                    "test", SandboxSessionFrame.Kind.STDOUT, "中文 password=hid".getBytes(StandardCharsets.UTF_8)));
            return this;
        }

        @Override
        public String id() {
            return "test";
        }

        @Override
        public SandboxSessionFrame read(Duration timeout) {
            return frames.poll();
        }

        @Override
        public void write(byte[] input) {
            frames.add(SandboxSessionFrame.stream(
                    "test", SandboxSessionFrame.Kind.STDOUT, "den\n".getBytes(StandardCharsets.UTF_8)));
            frames.add(SandboxSessionFrame.exit("test", 0, false, ""));
        }

        @Override
        public void closeInput() {}

        @Override
        public void resize(int columns, int rows) {}

        @Override
        public void signal(SandboxSignal signal) {}

        @Override
        public boolean isAlive() {
            return !terminated;
        }

        @Override
        public void terminate() {
            terminated = true;
        }
    }
}
