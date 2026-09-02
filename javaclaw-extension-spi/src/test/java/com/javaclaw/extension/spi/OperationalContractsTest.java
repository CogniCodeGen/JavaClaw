package com.javaclaw.extension.spi;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.AgentProfileRef;
import com.javaclaw.api.CancellationSource;
import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.CredentialRef;
import com.javaclaw.api.McpAuthType;
import com.javaclaw.api.McpEndpoint;
import com.javaclaw.api.McpEndpointSpec;
import com.javaclaw.api.McpEndpointState;
import com.javaclaw.api.McpTransport;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.WorkspaceId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OperationalContractsTest {
    private static final String DIGEST = "a".repeat(64);

    @Test
    void oauth浏览器请求只接受精确HTTPS授权目标和Origin() {
        URI authorization = URI.create(
                "https://auth.example.test/authorize?client_id=public&state=opaque&code_challenge=challenge");
        McpOAuthAuthorizationRequest request = new McpOAuthAuthorizationRequest(
                authorization,
                Set.of(URI.create("https://auth.example.test"), URI.create("https://login.example.test:8443/")));

        assertEquals(authorization, request.authorizationUri());
        assertTrue(request.allowedOrigins().contains(URI.create("https://auth.example.test")));
        assertTrue(request.allowedOrigins().contains(URI.create("https://login.example.test:8443")));
        assertInvalidAuthorization("http://auth.example.test/authorize", "https://auth.example.test");
        assertInvalidAuthorization("/authorize", "https://auth.example.test");
        assertInvalidAuthorization("https://user@auth.example.test/authorize", "https://auth.example.test");
        assertInvalidAuthorization("https://auth.example.test/authorize#fragment", "https://auth.example.test");
        assertInvalidAuthorization("https://auth.example.test:0/authorize", "https://auth.example.test:0");
        assertInvalidAuthorization(authorization.toString(), "https://auth.example.test/path");
        assertInvalidAuthorization(authorization.toString(), "https://auth.example.test?query=value");
        assertInvalidAuthorization(authorization.toString(), "http://auth.example.test");
        assertThrows(
                IllegalArgumentException.class,
                () -> new McpOAuthAuthorizationRequest(
                        authorization, Set.of(URI.create("https://other.example.test"))));
    }

    @Test
    void oauth交换冻结端点回调PKCE和取消信号() {
        CancellationSource cancellation = new CancellationSource();
        McpOAuthExchange exchange = new McpOAuthExchange(
                endpoint(),
                URI.create("https://auth.example.test/authorize?client_id=public"),
                URI.create("https://app.example.test/oauth/callback?code=code&state=state"),
                "  verifier  ",
                "  state  ",
                URI.create("https://app.example.test/oauth/callback"),
                cancellation);

        assertEquals("verifier", exchange.codeVerifier());
        assertEquals("state", exchange.expectedState());
        assertThrows(
                IllegalArgumentException.class,
                () -> new McpOAuthExchange(
                        endpoint(),
                        exchange.authorizationUri(),
                        exchange.callbackUri(),
                        " ",
                        "state",
                        exchange.redirectUri(),
                        cancellation));
        assertThrows(
                IllegalArgumentException.class,
                () -> new McpOAuthExchange(
                        endpoint(),
                        exchange.authorizationUri(),
                        exchange.callbackUri(),
                        "verifier",
                        " ",
                        exchange.redirectUri(),
                        cancellation));
    }

    @Test
    void 登录启动项状态要求可用性和原因一致() {
        LoginStartupPort port = required -> {};
        LoginStartupPort.Status defaultStatus = port.status(true);
        LoginStartupPort.Status unavailable =
                new LoginStartupPort.Status(false, false, Optional.of("  IDEA_NO_LAUNCHER  "));

        assertTrue(defaultStatus.repairAvailable());
        assertFalse(defaultStatus.installed());
        assertEquals("IDEA_NO_LAUNCHER", unavailable.unavailableReason().orElseThrow());
        assertThrows(
                IllegalArgumentException.class,
                () -> new LoginStartupPort.Status(true, false, Optional.of("UNAVAILABLE")));
        assertThrows(IllegalArgumentException.class, () -> new LoginStartupPort.Status(false, false, Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new LoginStartupPort.Status(false, false, Optional.of(" ")));
    }

    @Test
    void embedding结果要求有限向量精确维度和模型指纹() {
        EmbeddingVector first = new EmbeddingVector(List.of(1.0, -2.0));
        EmbeddingVector second = new EmbeddingVector(List.of(0.0, 4.5));
        EmbeddingBatch batch = new EmbeddingBatch(DIGEST.toUpperCase(), 2, List.of(first, second));

        assertEquals(DIGEST, batch.fingerprint());
        assertEquals(2, batch.vectors().size());
        assertThrows(IllegalArgumentException.class, () -> new EmbeddingVector(List.of()));
        assertThrows(IllegalArgumentException.class, () -> new EmbeddingVector(List.of(Double.NaN)));
        assertThrows(IllegalArgumentException.class, () -> new EmbeddingVector(List.of(Double.POSITIVE_INFINITY)));
        assertThrows(
                IllegalArgumentException.class, () -> new EmbeddingVector(java.util.Collections.nCopies(65_537, 1.0)));
        assertThrows(IllegalArgumentException.class, () -> new EmbeddingBatch("bad", 2, List.of(first)));
        assertThrows(IllegalArgumentException.class, () -> new EmbeddingBatch(DIGEST, 0, List.of(first)));
        assertThrows(IllegalArgumentException.class, () -> new EmbeddingBatch(DIGEST, 65_537, List.of(first)));
        assertThrows(IllegalArgumentException.class, () -> new EmbeddingBatch(DIGEST, 2, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new EmbeddingBatch(DIGEST, 1, List.of(first)));
    }

    @Test
    void 未配置embedding端口安全拒绝并保留稳定失败类型() {
        CancellationSource cancellation = new CancellationSource();
        EmbeddingUnavailableException failure = assertThrows(
                EmbeddingUnavailableException.class,
                () -> EmbeddingPort.unavailable().embed(List.of("text"), EmbeddingPurpose.DOCUMENT, cancellation));
        IllegalStateException cause = new IllegalStateException("offline");
        EmbeddingUnavailableException withCause = new EmbeddingUnavailableException("不可用", cause);

        assertTrue(failure.getMessage().contains("Embedding Provider"));
        assertEquals(cause, withCause.getCause());
        assertEquals("不可用", withCause.getMessage());
    }

    @Test
    void 隔离服务调用固定Workspace权限服务和取消信号() {
        CancellationSource cancellation = new CancellationSource();
        IsolatedServiceInvocation invocation = new IsolatedServiceInvocation(
                new ExtensionId("com.javaclaw.knowledge"),
                WorkspaceId.random(),
                SpiFixtures.permissions(),
                "  knowledge.extract  ",
                SpiFixtures.payload(),
                cancellation);

        assertEquals("knowledge.extract", invocation.serviceId());
        assertThrows(
                IllegalArgumentException.class,
                () -> new IsolatedServiceInvocation(
                        invocation.caller(),
                        invocation.workspaceId(),
                        invocation.effectivePermissions(),
                        " ",
                        invocation.request(),
                        cancellation));
    }

    @Test
    void job工作单元注册与文档历史实施稳定身份() {
        ExtensionJobWorkUnit unit = new ExtensionJobWorkUnit("  unit-1  ", SpiFixtures.payload());
        ExtensionJobRegistration registration = new ExtensionJobRegistration("  knowledge.index  ", emptyExecutor());
        DocumentRevision revision =
                new DocumentRevision("  document-1  ", 2, SpiFixtures.payload(), false, SpiFixtures.NOW);

        assertEquals("unit-1", unit.unitId());
        assertEquals("knowledge.index", registration.jobType());
        assertEquals("document-1", revision.key());
        assertThrows(IllegalArgumentException.class, () -> new ExtensionJobWorkUnit(" ", SpiFixtures.payload()));
        assertThrows(
                IllegalArgumentException.class, () -> new ExtensionJobWorkUnit("x".repeat(241), SpiFixtures.payload()));
        assertThrows(IllegalArgumentException.class, () -> new ExtensionJobRegistration("bad type", emptyExecutor()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new DocumentRevision(" ", 1, SpiFixtures.payload(), false, SpiFixtures.NOW));
        assertThrows(
                IllegalArgumentException.class,
                () -> new DocumentRevision("document", 0, SpiFixtures.payload(), false, SpiFixtures.NOW));
    }

    @Test
    void 编排失败携带Turn错误码和最后副作用凭据() {
        TurnId turnId = TurnId.random();
        OrchestratedTurnFailureException failure =
                new OrchestratedTurnFailureException(turnId, "  TOOL_FAILED  ", Optional.of("  receipt-1  "));
        OrchestratedToolEvidence evidence = new OrchestratedToolEvidence("  verify  ", true, SpiFixtures.payload());
        ExtensionAccessDeniedException denied = new ExtensionAccessDeniedException("extension disabled");

        assertEquals(turnId, failure.turnId());
        assertEquals("TOOL_FAILED", failure.errorCode());
        assertEquals("receipt-1", failure.effectReceiptKey().orElseThrow());
        assertTrue(failure.getMessage().contains("TOOL_FAILED"));
        assertEquals("verify", evidence.toolName());
        assertEquals("extension disabled", denied.getMessage());
        assertThrows(
                IllegalArgumentException.class,
                () -> new OrchestratedTurnFailureException(turnId, "bad-code", Optional.empty()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new OrchestratedTurnFailureException(turnId, "TOOL_FAILED", Optional.of(" ")));
        assertThrows(
                IllegalArgumentException.class, () -> new OrchestratedToolEvidence(" ", true, SpiFixtures.payload()));
    }

    @Test
    void automation步骤对象固定执行快照工具参数和Input身份() {
        WorkspaceId workspaceId = WorkspaceId.random();
        AutomationStepPort.StepContext context = new AutomationStepPort.StepContext(
                workspaceId,
                Optional.of(ThreadId.random()),
                "  Workflow step  ",
                SpiFixtures.executionSnapshot(new AgentProfileRef("profile", 2)),
                "  job:unit  ");
        AutomationStepPort.ToolCommand tool =
                new AutomationStepPort.ToolCommand(context, "  read  ", SpiFixtures.payload());
        AutomationStepPort.ToolResult toolResult = new AutomationStepPort.ToolResult(
                ThreadId.random(), TurnId.random(), true, SpiFixtures.payload(), Optional.of("  receipt  "));
        AutomationStepPort.InputCommand input = new AutomationStepPort.InputCommand(
                context, "  请选择范围  ", SpiFixtures.payload(), SpiFixtures.NOW.plusSeconds(60));
        AutomationStepPort.InputResult inputResult =
                new AutomationStepPort.InputResult(ThreadId.random(), TurnId.random(), "  request-1  ");

        assertEquals("Workflow step", context.title());
        assertEquals("job:unit", context.idempotencyKey());
        assertEquals("read", tool.toolName());
        assertEquals("receipt", toolResult.effectReceiptKey().orElseThrow());
        assertEquals("请选择范围", input.prompt());
        assertEquals("request-1", inputResult.requestId());
        assertThrows(
                IllegalArgumentException.class,
                () -> new AutomationStepPort.ToolCommand(context, " ", SpiFixtures.payload()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new AutomationStepPort.InputCommand(
                        context, " ", SpiFixtures.payload(), SpiFixtures.NOW.plusSeconds(1)));
    }

    @Test
    void 未装配automation与schedule端口全部失败关闭() {
        CancellationSource cancellation = new CancellationSource();
        AutomationStepPort.StepContext context = new AutomationStepPort.StepContext(
                WorkspaceId.random(),
                Optional.empty(),
                "step",
                SpiFixtures.executionSnapshot(new AgentProfileRef("profile", 2)),
                "unit");
        AutomationStepPort.ToolCommand tool =
                new AutomationStepPort.ToolCommand(context, "read", SpiFixtures.payload());
        AutomationStepPort.InputCommand input = new AutomationStepPort.InputCommand(
                context, "input", SpiFixtures.payload(), SpiFixtures.NOW.plusSeconds(1));

        assertThrows(
                IllegalStateException.class,
                () -> AutomationStepPort.unavailable().executeTool(tool, cancellation));
        assertThrows(
                IllegalStateException.class,
                () -> AutomationStepPort.unavailable().openInput(input, cancellation));
        assertThrows(
                IllegalStateException.class,
                () -> ScheduledCommandPort.unavailable()
                        .execute(
                                new ScheduledCommand(
                                        context.workspaceId(),
                                        "com.javaclaw.plan",
                                        "definition/start",
                                        SpiFixtures.payload(),
                                        "occurrence",
                                        1,
                                        Optional.empty(),
                                        Optional.empty()),
                                cancellation));
        assertThrows(
                IllegalStateException.class,
                () -> ScheduleLifecyclePort.unavailable().synchronize(context.workspaceId(), true));
    }

    private static McpEndpoint endpoint() {
        McpEndpointSpec spec = new McpEndpointSpec(
                WorkspaceId.random(),
                "MCP",
                McpTransport.STREAMABLE_HTTPS,
                Optional.of(URI.create("https://mcp.example.test/rpc")),
                Optional.empty(),
                McpAuthType.OAUTH_2_1_PKCE,
                Optional.of(new CredentialRef("oauth", "token")),
                Optional.empty(),
                Optional.empty(),
                Duration.ofSeconds(30));
        return new McpEndpoint("mcp_endpoint", 2, McpEndpointState.ENABLED, 3, spec, SpiFixtures.NOW, SpiFixtures.NOW);
    }

    private static void assertInvalidAuthorization(String authorization, String allowedOrigin) {
        assertThrows(
                IllegalArgumentException.class,
                () -> new McpOAuthAuthorizationRequest(URI.create(authorization), Set.of(URI.create(allowedOrigin))));
    }

    private static ExtensionJobExecutor emptyExecutor() {
        return new ExtensionJobExecutor() {
            @Override
            public Optional<ExtensionJobWorkUnit> plan(ExtensionJob job) {
                return Optional.empty();
            }

            @Override
            public ExtensionJobStepResult execute(
                    ExtensionJobExecution execution, com.javaclaw.api.CancellationToken cancellation) {
                return new ExtensionJobStepResult(
                        new CanonicalPayload("{}"),
                        new CanonicalPayload("{}"),
                        com.javaclaw.api.ExecutionState.COMPLETED,
                        Optional.empty(),
                        Optional.empty());
            }
        };
    }
}
