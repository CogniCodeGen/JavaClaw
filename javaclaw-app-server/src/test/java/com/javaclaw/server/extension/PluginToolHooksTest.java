package com.javaclaw.server.extension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.agent.runtime.ToolExecutionContext;
import com.javaclaw.agent.tool.ToolHooks;
import com.javaclaw.agent.tool.ToolOrigin;
import com.javaclaw.agent.tool.ToolRisk;
import com.javaclaw.core.api.AgentThread;
import com.javaclaw.core.api.AgentTurn;
import com.javaclaw.core.api.ApprovalPolicy;
import com.javaclaw.core.api.AttemptId;
import com.javaclaw.core.api.ModelToolCall;
import com.javaclaw.core.api.ThreadId;
import com.javaclaw.core.api.ThreadItem;
import com.javaclaw.core.api.ThreadStatus;
import com.javaclaw.core.api.ToolExecutionResult;
import com.javaclaw.core.api.TurnConfig;
import com.javaclaw.core.api.TurnId;
import com.javaclaw.core.api.TurnInput;
import com.javaclaw.core.api.TurnStatus;
import com.javaclaw.sandbox.api.SandboxCommand;
import com.javaclaw.sandbox.api.SandboxExecutor;
import com.javaclaw.sandbox.api.SandboxPolicy;
import com.javaclaw.sandbox.api.SandboxResult;
import com.javaclaw.sandbox.api.SandboxSession;
import com.javaclaw.sandbox.api.SandboxSessionFrame;
import com.javaclaw.sandbox.api.SandboxSessionOptions;
import com.javaclaw.sandbox.api.SandboxSignal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginToolHooksTest {
    @TempDir
    Path temporary;

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void adaptsPreAndPostHooksThroughTheSandboxProcessRuntime() throws Exception {
        Path workspace = Files.createDirectories(temporary.resolve("workspace"));
        Path bundle = pluginBundle();
        PluginCatalog catalog = new PluginCatalog(new PluginBundleLoader(PluginSignatureVerifier.REJECT_UNTRUSTED));
        catalog.register(bundle, true);
        List<String> calls = new ArrayList<>();
        PluginProcessRuntime processes = new PluginProcessRuntime(catalog, recordingSandbox(calls, bundle), json);
        PluginToolHooks hooks = new PluginToolHooks(catalog, processes, json);
        ToolHooks.Invocation invocation = invocation(workspace);

        ToolHooks.PreToolDecision decision = hooks.preHooks().getFirst().apply(invocation);
        hooks.postHooks()
                .getFirst()
                .accept(
                        invocation,
                        new ToolExecutionResult(
                                new ThreadItem.DynamicToolCall("inspect", Map.of("status", "ok")), "ok"));

        assertTrue(decision.allowed());
        assertEquals("rewritten", decision.arguments().path("value").asText());
        assertEquals(List.of("initialize", "hook/preTool", "initialize", "hook/postTool"), calls);
    }

    private Path pluginBundle() throws Exception {
        Path bundle = temporary.resolve("hooks");
        Files.createDirectories(bundle.resolve(".javaclaw-plugin"));
        Files.createDirectories(bundle.resolve("bin"));
        Files.writeString(bundle.resolve("bin/pre"), "pre");
        Files.writeString(bundle.resolve("bin/post"), "post");
        Files.writeString(bundle.resolve(".javaclaw-plugin/plugin.json"), """
                {
                  "apiVersion":4,"id":"hooks.plugin","version":"1.0.0","name":"Hooks",
                  "processes":[
                    {"id":"pre","kind":"PRE_TOOL_HOOK","entrypoint":"bin/pre",
                     "arguments":[],"workspaceRead":true,"workspaceWrite":false,
                     "networkAllowlist":[],"timeoutMillis":1000,"outputLimitBytes":4096},
                    {"id":"post","kind":"POST_TOOL_HOOK","entrypoint":"bin/post",
                     "arguments":[],"workspaceRead":false,"workspaceWrite":false,
                     "networkAllowlist":[],"timeoutMillis":1000,"outputLimitBytes":4096}
                  ],"skills":[]
                }
                """);
        return bundle;
    }

    private static ToolHooks.Invocation invocation(Path workspace) {
        Instant now = Instant.now();
        ThreadId threadId = ThreadId.random();
        TurnId turnId = TurnId.random();
        SandboxPolicy policy = SandboxPolicy.readOnly(
                Set.of(workspace), Set.of(workspace.resolve(".git"), workspace.resolve(".javaclaw")));
        TurnConfig config = new TurnConfig(
                "model", "provider", "medium", workspace, policy, ApprovalPolicy.ON_RISK, Set.of(), Map.of());
        AgentThread thread = new AgentThread(
                threadId, "workspace", null, null, "test", workspace, ThreadStatus.ACTIVE, 0, 0, 1, now, now);
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
        ToolExecutionContext call = new ToolExecutionContext(
                thread, turn, new ModelToolCall("call", "inspect", "{\"value\":\"original\"}"), config);
        return new ToolHooks.Invocation(
                call,
                ToolOrigin.PLUGIN,
                ToolRisk.MEDIUM,
                new ObjectMapper().createObjectNode().put("value", "original"),
                policy);
    }

    private SandboxExecutor recordingSandbox(List<String> calls, Path bundle) {
        return new SandboxExecutor() {
            @Override
            public SandboxResult execute(SandboxCommand command) {
                throw new AssertionError("persistent plugins must not use one-shot execution");
            }

            @Override
            public SandboxSession openSession(SandboxCommand command, SandboxSessionOptions options) throws Exception {
                assertTrue(
                        command.policy().readableRoots().contains(bundle.toRealPath()),
                        "the executable bundle is infrastructure-readable inside the sandbox");
                return new SandboxSession() {
                    private final LinkedBlockingQueue<SandboxSessionFrame> output = new LinkedBlockingQueue<>();
                    private boolean alive = true;

                    @Override
                    public String id() {
                        return command.id();
                    }

                    @Override
                    public SandboxSessionFrame read(Duration timeout) throws InterruptedException {
                        return output.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
                    }

                    @Override
                    public void write(byte[] input) throws Exception {
                        JsonNode request = json.readTree(input);
                        String method = request.path("method").asText();
                        calls.add(method);
                        var response = json.createObjectNode().put("jsonrpc", "2.0");
                        response.set("id", request.get("id"));
                        var result = response.putObject("result").put("ok", true);
                        if ("initialize".equals(method)) {
                            result.put("protocolVersion", 1);
                        }
                        if ("hook/preTool".equals(method)) {
                            result.put("allowed", true).putObject("arguments").put("value", "rewritten");
                        }
                        output.add(SandboxSessionFrame.stream(
                                "test",
                                SandboxSessionFrame.Kind.STDOUT,
                                (response + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                    }

                    @Override
                    public void closeInput() {}

                    @Override
                    public void resize(int columns, int rows) {}

                    @Override
                    public void signal(SandboxSignal signal) {}

                    @Override
                    public boolean isAlive() {
                        return alive;
                    }

                    @Override
                    public void terminate() {
                        alive = false;
                    }
                };
            }
        };
    }
}
