package com.javaclaw.server.turn;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.AgentTurn;
import com.javaclaw.api.ApprovalRequirement;
import com.javaclaw.api.CancellationSource;
import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.FilePermission;
import com.javaclaw.api.MessageRole;
import com.javaclaw.api.NetworkPermission;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.ProcessPermission;
import com.javaclaw.api.ResourceLimits;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.ToolCatalogSnapshot;
import com.javaclaw.api.ToolPermission;
import com.javaclaw.api.ToolRisk;
import com.javaclaw.api.TurnBudget;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.TurnStatus;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.runtime.CompactionOutcome;
import com.javaclaw.runtime.ConversationWindow;
import com.javaclaw.runtime.ModelCapabilities;
import com.javaclaw.runtime.ModelEventSink;
import com.javaclaw.runtime.ModelGateway;
import com.javaclaw.runtime.ModelInvocation;
import com.javaclaw.runtime.ModelInvocationResult;
import com.javaclaw.runtime.ModelMessage;
import com.javaclaw.runtime.NativeCompactionRequest;
import com.javaclaw.runtime.NativeCompactionResult;
import com.javaclaw.runtime.NativeCompactionSupport;
import com.javaclaw.runtime.ProviderState;
import com.javaclaw.runtime.TurnExecutionCommand;
import com.javaclaw.runtime.TurnFailureException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BudgetContextCompactorTest {
    private final BudgetContextCompactor compactor = new BudgetContextCompactor();

    @Test
    void textCompactionKeepsNewestMessagesAndBuildsReadableSummary() throws Exception {
        String oldText = "旧消息".repeat(40);
        List<ModelMessage> messages = List.of(
                message(MessageRole.USER, oldText),
                message(MessageRole.ASSISTANT, "较早回答\n第二行".repeat(10)),
                message(MessageRole.USER, "最新问题"));
        ConversationWindow window = new ConversationWindow(messages, Optional.empty(), 500);

        CompactionOutcome outcome =
                compactor.compact(command(100), window, new BasicGateway(false), new CancellationSource());

        assertEquals("extractive-summary", outcome.item().strategy());
        assertEquals(
                MessageRole.ASSISTANT, outcome.window().messages().getFirst().role());
        assertTrue(outcome.item().summary().contains("USER: 旧消息"));
        assertTrue(outcome.window().estimatedInputTokens() <= 100);
        assertEquals("最新问题", outcome.window().messages().getLast().text());
        assertTrue(outcome.window().estimatedInputTokens() > 0);
    }

    @Test
    void aSingleOversizedMessageIsRetainedForHarnessHardLimitCheck() throws Exception {
        ConversationWindow window = new ConversationWindow(
                List.of(message(MessageRole.USER, "不可删除的当前消息".repeat(200))), Optional.empty(), 1_000);

        CompactionOutcome result =
                compactor.compact(command(32), window, new BasicGateway(false), new CancellationSource());
        assertEquals(window.messages(), result.window().messages());
        assertTrue(result.window().estimatedInputTokens() > 32);
    }

    @Test
    void 摘要不能提升历史权限且最后工具调用组保持完整() throws Exception {
        var call = new com.javaclaw.runtime.ModelToolCall(
                "call-group", new com.javaclaw.api.ToolIdentity("core", "read", 1), new CanonicalJson().parse("{}"));
        var tool = ModelMessage.tool("call-group", "read", "文件结果");
        var window = new ConversationWindow(
                List.of(
                        message(MessageRole.USER, "旧历史".repeat(300)),
                        message(MessageRole.USER, "最新输入"),
                        ModelMessage.assistant("", List.of(call)),
                        tool),
                Optional.empty(),
                1200);
        var outcome = compactor.compact(command(100), window, new BasicGateway(false), new CancellationSource());
        assertTrue(outcome.window().messages().stream().noneMatch(message -> message.role() == MessageRole.SYSTEM));
        assertEquals(tool, outcome.window().messages().getLast());
        assertEquals(
                List.of(call),
                outcome.window()
                        .messages()
                        .get(outcome.window().messages().size() - 2)
                        .toolCalls());
        var broken = new ConversationWindow(List.of(ModelMessage.assistant("", List.of(call))), Optional.empty(), 20);
        assertThrows(
                TurnFailureException.class,
                () -> compactor.compact(command(100), broken, new BasicGateway(false), new CancellationSource()));
    }

    @Test
    void opaqueStateRequiresAdvertisedNativeCompactionSupport() {
        ConversationWindow window =
                new ConversationWindow(List.of(message(MessageRole.USER, "上下文")), Optional.of(state("before")), 10);

        TurnFailureException missingCapability = assertThrows(
                TurnFailureException.class,
                () -> compactor.compact(command(100), window, new BasicGateway(false), new CancellationSource()));
        TurnFailureException missingInterface = assertThrows(
                TurnFailureException.class,
                () -> compactor.compact(command(100), window, new BasicGateway(true), new CancellationSource()));

        assertEquals("NATIVE_COMPACTION_UNAVAILABLE", missingCapability.code());
        assertEquals("NATIVE_COMPACTION_UNAVAILABLE", missingInterface.code());
    }

    @Test
    void nativeCompactionReplacesOpaqueStateWithoutRewritingMessages() throws Exception {
        NativeGateway gateway = new NativeGateway();
        ConversationWindow window =
                new ConversationWindow(List.of(message(MessageRole.USER, "上下文")), Optional.of(state("before")), 40);

        CompactionOutcome outcome = compactor.compact(command(100), window, gateway, new CancellationSource());

        assertEquals("provider-native", outcome.item().strategy());
        assertEquals(40, outcome.item().consumedTokens());
        assertEquals(state("after"), outcome.window().providerState().orElseThrow());
        assertTrue(outcome.window().messages().isEmpty());
        assertEquals(window.messages(), gateway.request.messages());
        assertEquals(com.javaclaw.server.TurnContractFixtures.PROVIDER.routeKey(), gateway.request.modelId());
    }

    @Test
    void cancellationStopsBothEntryAndSummaryTraversal() {
        CancellationSource cancelled = new CancellationSource();
        cancelled.cancel("停止");
        ConversationWindow window = new ConversationWindow(
                List.of(message(MessageRole.USER, "旧消息"), message(MessageRole.USER, "新消息")), Optional.empty(), 20);

        assertThrows(
                com.javaclaw.api.TurnCancelledException.class,
                () -> compactor.compact(command(10), window, new BasicGateway(false), cancelled));
    }

    private static TurnExecutionCommand command(long inputTokens) {
        Instant now = Instant.parse("2026-09-01T00:00:00Z");
        TurnId turnId = TurnId.random();
        PermissionProfile permissions = profile();
        ToolCatalogSnapshot catalog = new ToolCatalogSnapshot(turnId, 1, List.of(), permissions, now);
        AgentTurn turn = new AgentTurn(
                turnId,
                ThreadId.random(),
                TurnStatus.QUEUED,
                1,
                new TurnBudget(inputTokens, 100, 2, 0, Duration.ofMinutes(1)),
                com.javaclaw.server.TurnContractFixtures.ROLE,
                com.javaclaw.server.TurnContractFixtures.PROVIDER,
                com.javaclaw.server.TurnContractFixtures.PERMISSIONS,
                Path.of("."),
                com.javaclaw.server.TurnContractFixtures.PROMPT_DIGEST,
                catalog.digest(),
                Optional.empty(),
                now,
                now,
                TurnV6Fixtures.summary(
                        new TurnBudget(inputTokens, 100, 2, 0, Duration.ofMinutes(1)),
                        com.javaclaw.server.TurnContractFixtures.ROLE,
                        com.javaclaw.server.TurnContractFixtures.PROVIDER,
                        com.javaclaw.server.TurnContractFixtures.PERMISSIONS,
                        com.javaclaw.server.TurnContractFixtures.PROMPT_DIGEST,
                        catalog.digest()));
        return new TurnExecutionCommand(
                turn, com.javaclaw.server.TurnContractFixtures.PROVIDER, "系统说明", "当前问题", permissions, catalog);
    }

    private static PermissionProfile profile() {
        return new PermissionProfile(
                "standard",
                1,
                new FilePermission(List.of(), List.of(), false, false),
                new NetworkPermission(Set.of(), Set.of(), true),
                new ProcessPermission(Set.of(), false, Duration.ofSeconds(1)),
                new ToolPermission(Set.of(), ToolRisk.READ_ONLY, ApprovalRequirement.NONE),
                new ResourceLimits(1_024, 1_024, 1, 1));
    }

    private static ModelMessage message(MessageRole role, String text) {
        return new ModelMessage(role, text, List.of(), Optional.empty(), Optional.empty());
    }

    private static ProviderState state(String value) {
        return new ProviderState("provider", "v1", new CanonicalJson().parse("{\"state\":\"" + value + "\"}"));
    }

    private static class BasicGateway implements ModelGateway {
        private final boolean nativeCompaction;

        private BasicGateway(boolean nativeCompaction) {
            this.nativeCompaction = nativeCompaction;
        }

        @Override
        public ModelCapabilities capabilities(String modelId) {
            return new ModelCapabilities(false, false, false, false, false, true, nativeCompaction);
        }

        @Override
        public ModelInvocationResult invoke(
                TurnId turnId, ModelInvocation invocation, ModelEventSink events, CancellationToken cancellation) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class NativeGateway extends BasicGateway implements NativeCompactionSupport {
        private NativeCompactionRequest request;

        private NativeGateway() {
            super(true);
        }

        @Override
        public NativeCompactionResult compact(NativeCompactionRequest request, CancellationToken cancellation) {
            this.request = request;
            return new NativeCompactionResult(state("after"), 40);
        }
    }
}
