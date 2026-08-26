package com.javaclaw.framework.springai;

import com.javaclaw.api.conversation.ConversationRequest;
import com.javaclaw.application.agent.RunRequestFactory;
import com.javaclaw.application.agent.ToolIntentRouter;
import com.javaclaw.browser.PlaywrightBrowserManager;
import com.javaclaw.config.AgentConfig;
import com.javaclaw.framework.api.AgentClient;
import com.javaclaw.framework.api.CapabilityId;
import com.javaclaw.framework.api.InvocationSource;
import com.javaclaw.framework.api.ModelPolicyRefs;
import com.javaclaw.framework.api.PermissionSet;
import com.javaclaw.framework.api.RunBudget;
import com.javaclaw.framework.api.RunOutcome;
import com.javaclaw.framework.api.RunProfileDraft;
import com.javaclaw.framework.api.RunRequest;
import com.javaclaw.framework.api.RunState;
import com.javaclaw.platform.data.DataRoot;
import com.javaclaw.platform.spring.ApplicationContexts;
import com.javaclaw.platform.spring.WorkspaceContextHandle;
import com.javaclaw.platform.spring.WorkspaceRuntimeOptions;
import com.javaclaw.platform.spring.WorkspaceSpringContextFactory;
import com.javaclaw.runtime.WorkspaceContext;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

import java.nio.file.Path;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Records complete product prompts with a fake model; no provider call or router model is used. */
class TokenOptimizationPromptBenchmarkTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void routedPromptsStayInsideNoToolSingleDomainAndFourStepBudgets() throws Exception {
        Path rootPath = temporaryDirectory.resolve("root");
        Path workspacePath = temporaryDirectory.resolve("workspace");
        try (var root = ApplicationContexts.createRoot(new DataRoot(rootPath))) {
            PlaywrightBrowserManager browsers = fakeBrowser(
                    workspacePath.resolve("browser"), workspacePath.resolve("shots"));
            WorkspaceRuntimeOptions options = new WorkspaceRuntimeOptions(
                    browsers, () -> { }, () -> { }, () -> { }, Set.of());
            WorkspaceContext workspace = new WorkspaceContext(
                    "token-benchmark", workspacePath, workspacePath.resolve("data"),
                    workspacePath.resolve("browser"), workspacePath.resolve("shots"),
                    workspacePath.resolve("logs"));
            try (WorkspaceContextHandle child = root.getBean(WorkspaceSpringContextFactory.class)
                    .create(workspace, options)) {
                RecordingModel fake = new RecordingModel();
                ModelPolicyRefs policies = child.bean(ModelPolicyRefs.class);
                root.getBean(SpringAiModelRegistry.class).registerOrReplace(policies.high(), fake);
                installBenchmarkProfile(child);

                RunRequestFactory requests = new RunRequestFactory(
                        workspace, new ToolIntentRouter(child.bean(AgentConfig.class)));
                AgentClient agents = child.bean(AgentClient.class);

                fake.reset(0);
                run(agents, requests, "什么是 CAP 定理？", "ordinary");
                assertEquals(1, fake.prompts.size());
                PromptSample ordinary = fake.prompts.getFirst();
                assertEquals(2, ordinary.toolCount());
                assertTrue(ordinary.estimatedInputTokens() <= 5_000,
                        () -> "ordinary prompt tokens=" + ordinary.estimatedInputTokens());

                fake.reset(0);
                run(agents, requests, "帮我查最新新闻", "single-web");
                assertEquals(1, fake.prompts.size());
                PromptSample web = fake.prompts.getFirst();
                assertTrue(web.toolCount() <= 24);
                assertTrue(web.estimatedInputTokens() <= 6_000,
                        () -> "single-domain prompt tokens=" + web.estimatedInputTokens());

                fake.reset(4);
                RunOutcome fourStep = run(agents, requests,
                        "打开网页并依次点击四个步骤，读取每一步结果", "four-step-web");
                assertEquals(5, fake.prompts.size());
                assertTrue(fake.prompts.stream().skip(1).allMatch(PromptSample::hasWebToolCall));
                assertTrue(fake.prompts.stream().skip(1).allMatch(PromptSample::hasToolResponse));
                assertTrue(fake.prompts.stream().skip(1)
                        .allMatch(sample -> sample.toolResultTokens() > 0));
                long totalInput = fake.prompts.stream()
                        .mapToLong(PromptSample::estimatedInputTokens).sum();
                assertTrue(totalInput <= 35_000,
                        () -> "four-step cumulative input=" + totalInput);
                assertTrue(fourStep.output().path("usage").path("inputTokens").asLong()
                        <= 35_000);
            } finally {
                browsers.shutdown();
            }
        }
    }

    private static PlaywrightBrowserManager fakeBrowser(Path browser, Path shots) {
        java.util.concurrent.atomic.AtomicReference<com.microsoft.playwright.Locator> locatorRef =
                new java.util.concurrent.atomic.AtomicReference<>();
        com.microsoft.playwright.Locator locator = (com.microsoft.playwright.Locator)
                Proxy.newProxyInstance(
                        TokenOptimizationPromptBenchmarkTest.class.getClassLoader(),
                        new Class<?>[]{com.microsoft.playwright.Locator.class},
                        (proxy, method, arguments) -> switch (method.getName()) {
                            case "count" -> 1;
                            case "first" -> locatorRef.get();
                            case "innerText" -> "deterministic local element result ".repeat(80);
                            case "toString" -> "FakeBenchmarkLocator";
                            case "hashCode" -> System.identityHashCode(proxy);
                            case "equals" -> proxy == arguments[0];
                            default -> defaultValue(method.getReturnType());
                        });
        locatorRef.set(locator);
        com.microsoft.playwright.Page page = (com.microsoft.playwright.Page) Proxy.newProxyInstance(
                TokenOptimizationPromptBenchmarkTest.class.getClassLoader(),
                new Class<?>[]{com.microsoft.playwright.Page.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "title" -> "deterministic local web result ".repeat(120);
                    case "url" -> "https://benchmark.invalid/local";
                    case "locator" -> locator;
                    case "toString" -> "FakeBenchmarkPage";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == arguments[0];
                    default -> defaultValue(method.getReturnType());
                });
        return new PlaywrightBrowserManager(true, browser, shots) {
            @Override public synchronized com.microsoft.playwright.Page getActivePage() {
                return page;
            }
        };
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == char.class) return '\0';
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0F;
        if (type == double.class) return 0D;
        return null;
    }

    private static RunOutcome run(
            AgentClient agents, RunRequestFactory requests,
            String prompt, String session) throws Exception {
        RunRequest request = requests.conversation(
                new ConversationRequest(prompt, List.of(), session), "token-benchmark-profile",
                InvocationSource.chat(), PermissionSet.UNRESTRICTED);
        RunOutcome outcome = agents.start(request).completion().toCompletableFuture()
                .get(30, TimeUnit.SECONDS);
        assertEquals(RunState.COMPLETED, outcome.state(), outcome.error());
        return outcome;
    }

    private static void installBenchmarkProfile(WorkspaceContextHandle child) {
        var disabled = JsonNodeFactory.instance.objectNode().put("enabled", false);
        RunProfileDraft profile = new RunProfileDraft(
                "token-benchmark-profile", "Token benchmark", PermissionSet.UNRESTRICTED,
                new RunBudget(Duration.ofMinutes(5), 120_000, 32_000, 16,
                        new BigDecimal("100")),
                Map.of(
                        new CapabilityId("memory.distillation"), disabled,
                        new CapabilityId("memory.habit"), disabled,
                        new CapabilityId("gepa.evaluate"), disabled,
                        new CapabilityId("gepa.revise"), disabled),
                JsonNodeFactory.instance.objectNode());
        var definitions = child.bean(
                com.javaclaw.framework.store.JdbcAgentDefinitionStore.class);
        definitions.saveProfileDraft("token-benchmark", profile, false);
        definitions.publishProfile("token-benchmark", profile.id());
    }

    private static final class RecordingModel implements ChatModel {
        private final List<PromptSample> prompts = new ArrayList<>();
        private final AtomicInteger remainingToolCalls = new AtomicInteger();
        private final AtomicInteger sequence = new AtomicInteger();

        void reset(int toolCalls) {
            prompts.clear();
            remainingToolCalls.set(toolCalls);
            sequence.set(0);
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            PromptSample sample = sample(prompt);
            prompts.add(sample);
            AssistantMessage output;
            if (remainingToolCalls.getAndDecrement() > 0) {
                int call = sequence.incrementAndGet();
                String[] names = {
                        "web_get_title", "web_get_url", "web_get_text", "web_get_text"};
                String[] arguments = {
                        "{}", "{}", "{\"target\":\"body\"}", "{\"target\":\"main\"}"};
                String toolName = names[(call - 1) % names.length];
                boolean exposed = prompt.getOptions() instanceof ToolCallingChatOptions options
                        && options.getToolCallbacks().stream().anyMatch(callback ->
                        callback.getToolDefinition().name().equals(toolName));
                if (!exposed) throw new AssertionError(toolName + " schema was not exposed");
                output = AssistantMessage.builder().content("")
                        .toolCalls(List.of(new AssistantMessage.ToolCall(
                                "web-step-" + call, "function", toolName,
                                arguments[(call - 1) % arguments.length])))
                        .build();
            } else {
                output = new AssistantMessage("done");
            }
            return new ChatResponse(List.of(new Generation(output)),
                    ChatResponseMetadata.builder().model("fake-token-benchmark")
                            .usage(new DefaultUsage(sample.estimatedInputTokens(), 4)).build());
        }

        @Override
        public org.springframework.ai.chat.prompt.ChatOptions getOptions() {
            return ToolCallingChatOptions.builder().build();
        }

        private static PromptSample sample(Prompt prompt) {
            PromptTokenEstimator.Breakdown estimate = PromptTokenEstimator.estimate(prompt);
            boolean webToolCall = prompt.getInstructions().stream()
                    .filter(AssistantMessage.class::isInstance)
                    .map(AssistantMessage.class::cast)
                    .flatMap(message -> message.getToolCalls().stream())
                    .anyMatch(call -> call.name().startsWith("web_"));
            boolean toolResponse = prompt.getInstructions().stream()
                    .anyMatch(ToolResponseMessage.class::isInstance);
            return new PromptSample(estimate.totalInputTokens(), estimate.toolCount(),
                    estimate.toolResultTokens(), webToolCall, toolResponse);
        }
    }

    private record PromptSample(
            int estimatedInputTokens,
            int toolCount,
            int toolResultTokens,
            boolean hasWebToolCall,
            boolean hasToolResponse) { }
}
