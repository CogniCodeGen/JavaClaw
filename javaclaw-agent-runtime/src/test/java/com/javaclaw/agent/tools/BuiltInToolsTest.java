package com.javaclaw.agent.tools;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.agent.runtime.ToolExecutionContext;
import com.javaclaw.agent.tool.GovernedToolRuntime;
import com.javaclaw.agent.tool.PendingUserInputGateway;
import com.javaclaw.agent.tool.ToolRuntimeTestSupport;
import com.javaclaw.core.api.AgentThread;
import com.javaclaw.core.api.AgentTurn;
import com.javaclaw.core.api.ApprovalPolicy;
import com.javaclaw.core.api.AttemptId;
import com.javaclaw.core.api.ModelToolCall;
import com.javaclaw.core.api.ThreadId;
import com.javaclaw.core.api.ThreadItem;
import com.javaclaw.core.api.ThreadStatus;
import com.javaclaw.core.api.TurnConfig;
import com.javaclaw.core.api.TurnId;
import com.javaclaw.core.api.TurnInput;
import com.javaclaw.core.api.TurnStatus;
import com.javaclaw.sandbox.api.SandboxCommand;
import com.javaclaw.sandbox.api.SandboxPolicy;
import com.javaclaw.sandbox.api.SandboxResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuiltInToolsTest {
    @TempDir
    Path temporary;

    @Test
    void commandFeatureDelegatesOnlyToTheSandboxExecutor() {
        AtomicReference<SandboxCommand> executed = new AtomicReference<>();
        try (GovernedToolRuntime runtime = runtime(List.of(SandboxedCommandTool.create(policy())), command -> {
            executed.set(command);
            return success();
        })) {
            var result = ToolRuntimeTestSupport.execute(
                    runtime, context("command", """
                    {"argv":["echo","hello"]}
                    """, ApprovalPolicy.ALWAYS), item -> null);

            assertEquals(List.of("echo", "hello"), executed.get().argv());
            assertInstanceOf(ThreadItem.CommandExecution.class, result.item());
        }
    }

    @Test
    void askUserRegistersBeforeAnnouncingAndReturnsAValidatedResponse() throws Exception {
        CountDownLatch announced = new CountDownLatch(1);
        AtomicReference<ThreadItem.UserInputRequest> request = new AtomicReference<>();
        try (PendingUserInputGateway gateway = new PendingUserInputGateway(Duration.ofSeconds(2), 4);
                GovernedToolRuntime runtime =
                        runtime(List.of(UserInputTool.create(gateway, policy())), command -> success());
                var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var future = executor.submit(() ->
                    ToolRuntimeTestSupport.execute(runtime, context("ask_user", """
                    {"prompt":"Proceed?","choices":["yes","no"]}
                    """, ApprovalPolicy.ON_RISK), item -> {
                        request.set((ThreadItem.UserInputRequest) item);
                        announced.countDown();
                        return null;
                    }));

            assertTrue(announced.await(1, TimeUnit.SECONDS));
            assertTrue(gateway.respond(request.get().requestId(), "yes", false));
            ThreadItem.UserInputResponse response = assertInstanceOf(
                    ThreadItem.UserInputResponse.class,
                    future.get(1, TimeUnit.SECONDS).item());
            assertEquals("yes", response.value());
        }
    }

    private GovernedToolRuntime runtime(
            List<com.javaclaw.agent.tool.RegisteredTool> tools, com.javaclaw.sandbox.api.SandboxExecutor sandbox) {
        return new GovernedToolRuntime(
                ToolRuntimeTestSupport.providers(tools),
                List.of(),
                List.of(),
                (request, announce) -> {
                    announce.run();
                    return true;
                },
                sandbox,
                policy(),
                Duration.ofSeconds(1),
                new ObjectMapper());
    }

    private ToolExecutionContext context(String tool, String arguments, ApprovalPolicy approvalPolicy) {
        Instant now = Instant.now();
        ThreadId threadId = ThreadId.random();
        TurnId turnId = TurnId.random();
        TurnConfig config =
                new TurnConfig("model", "provider", "medium", temporary, policy(), approvalPolicy, Set.of(), Map.of());
        AgentThread thread = new AgentThread(
                threadId, "workspace", null, null, "", temporary, ThreadStatus.ACTIVE, 0, 0, 1, now, now);
        AgentTurn turn = new AgentTurn(
                turnId,
                threadId,
                AttemptId.random(),
                TurnStatus.IN_PROGRESS,
                List.of(new TurnInput.Text("test")),
                config,
                null,
                now,
                null);
        return new ToolExecutionContext(thread, turn, new ModelToolCall("call", tool, arguments), config);
    }

    private SandboxPolicy policy() {
        return SandboxPolicy.workspaceWrite(Set.of(temporary), Set.of(temporary), Set.of(temporary.resolve(".git")));
    }

    private static SandboxResult success() {
        return new SandboxResult(0, "ok", "", false, false, Duration.ofMillis(1), "fake");
    }
}
