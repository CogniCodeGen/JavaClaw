package com.javaclaw.agent.tools;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.agent.collaboration.CollaborationGateway;
import com.javaclaw.agent.runtime.ToolExecutionContext;
import com.javaclaw.agent.tool.GovernedToolRuntime;
import com.javaclaw.agent.tool.ToolRuntimeTestSupport;
import com.javaclaw.core.api.AgentThread;
import com.javaclaw.core.api.AgentTurn;
import com.javaclaw.core.api.ApprovalPolicy;
import com.javaclaw.core.api.AttemptId;
import com.javaclaw.core.api.ModelToolCall;
import com.javaclaw.core.api.ThreadId;
import com.javaclaw.core.api.ThreadItem;
import com.javaclaw.core.api.ThreadSnapshot;
import com.javaclaw.core.api.ThreadStatus;
import com.javaclaw.core.api.TurnConfig;
import com.javaclaw.core.api.TurnId;
import com.javaclaw.core.api.TurnInput;
import com.javaclaw.core.api.TurnStatus;
import com.javaclaw.sandbox.api.SandboxPolicy;
import com.javaclaw.sandbox.api.SandboxResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class CollaborationToolsTest {
    @TempDir
    Path temporary;

    @Test
    void spawnUsesTheCurrentThreadAsParentAndRequiresGovernedApproval() {
        AtomicReference<CollaborationGateway.SpawnRequest> request = new AtomicReference<>();
        AtomicReference<String> approvedTool = new AtomicReference<>();
        FakeGateway gateway = new FakeGateway() {
            @Override
            public AgentThread spawn(SpawnRequest value) {
                request.set(value);
                ThreadId id = ThreadId.random();
                lastChild = id;
                return thread(id, value.parentThreadId());
            }
        };
        try (GovernedToolRuntime runtime = new GovernedToolRuntime(
                ToolRuntimeTestSupport.providers(CollaborationTools.create(gateway, policy())),
                List.of(),
                List.of(),
                (approval, announce) -> {
                    approvedTool.set(approval.call().call().name());
                    announce.run();
                    return true;
                },
                ignored -> success(),
                policy(),
                Duration.ofSeconds(1),
                new ObjectMapper())) {
            ToolExecutionContext call = context("spawn_subagent", """
                    {"task":"review storage","writable":false,
                     "idempotencyKey":"spawn-1"}
                    """);
            var result = ToolRuntimeTestSupport.execute(runtime, call, ignored -> null);

            ThreadItem.SubagentCall item = assertInstanceOf(ThreadItem.SubagentCall.class, result.item());
            assertEquals(call.thread().id(), request.get().parentThreadId());
            assertEquals("profile_subagent", request.get().profileId());
            assertEquals("spawn_subagent", approvedTool.get());
            assertEquals(item.childThreadId(), gateway.lastChild);
        }
    }

    @Test
    void waitIsBoundedAndReportsTerminalChild() {
        AtomicReference<Duration> timeout = new AtomicReference<>();
        FakeGateway gateway = new FakeGateway() {
            @Override
            public Optional<ThreadSnapshot> waitForTerminal(ThreadId childThreadId, Duration value) {
                timeout.set(value);
                AgentThread child = thread(childThreadId, ThreadId.random());
                return Optional.of(new ThreadSnapshot(child, List.of(), List.of()));
            }
        };
        try (GovernedToolRuntime runtime = runtime(gateway)) {
            var result = ToolRuntimeTestSupport.execute(runtime, context("wait_subagent", """
                    {"childThreadId":"thr_child","timeoutMillis":60000}
                    """), ignored -> null);
            ThreadItem.DynamicToolCall item = assertInstanceOf(ThreadItem.DynamicToolCall.class, result.item());
            assertEquals(Duration.ofSeconds(60), timeout.get());
            assertEquals("true", item.result().get("terminal"));
        }
    }

    private GovernedToolRuntime runtime(CollaborationGateway gateway) {
        return new GovernedToolRuntime(
                ToolRuntimeTestSupport.providers(CollaborationTools.create(gateway, policy())),
                List.of(),
                List.of(),
                (request, announce) -> true,
                ignored -> success(),
                policy(),
                Duration.ofSeconds(1),
                new ObjectMapper());
    }

    private ToolExecutionContext context(String tool, String arguments) {
        Instant now = Instant.now();
        AgentThread thread = thread(ThreadId.random(), null);
        TurnId turnId = TurnId.random();
        TurnConfig config = new TurnConfig(
                "model", "provider", "medium", temporary, policy(), ApprovalPolicy.ON_RISK, Set.of(), Map.of());
        AgentTurn turn = new AgentTurn(
                turnId,
                thread.id(),
                AttemptId.random(),
                TurnStatus.IN_PROGRESS,
                List.of(new TurnInput.Text("test")),
                config,
                null,
                now,
                null);
        return new ToolExecutionContext(thread, turn, new ModelToolCall("call", tool, arguments), config);
    }

    private AgentThread thread(ThreadId id, ThreadId parent) {
        Instant now = Instant.now();
        AgentThread value = new AgentThread(
                id, "workspace", parent, null, "child", temporary, ThreadStatus.ACTIVE, 0, 0, 1, now, now);
        return value;
    }

    private SandboxPolicy policy() {
        return SandboxPolicy.workspaceWrite(Set.of(temporary), Set.of(temporary), Set.of(temporary.resolve(".git")));
    }

    private static SandboxResult success() {
        return new SandboxResult(0, "", "", false, false, Duration.ofMillis(1), "fake");
    }

    private static class FakeGateway implements CollaborationGateway {
        protected volatile ThreadId lastChild;

        @Override
        public AgentThread spawn(SpawnRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean steer(TurnId turnId, TurnInput input) {
            return false;
        }

        @Override
        public Optional<ThreadSnapshot> read(ThreadId childThreadId) {
            return Optional.empty();
        }

        @Override
        public Optional<ThreadSnapshot> waitForTerminal(ThreadId childThreadId, Duration timeout) {
            return Optional.empty();
        }

        @Override
        public boolean cancel(ThreadId childThreadId) {
            return false;
        }

        @Override
        public List<AgentThread> children(ThreadId parentThreadId) {
            return List.of();
        }

        @Override
        public PatchResult diff(ThreadId childThreadId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public PatchResult apply(ThreadId parentThreadId, ThreadId childThreadId, String idempotencyKey) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean cleanup(ThreadId childThreadId, boolean discardUnmerged) {
            return false;
        }
    }
}
