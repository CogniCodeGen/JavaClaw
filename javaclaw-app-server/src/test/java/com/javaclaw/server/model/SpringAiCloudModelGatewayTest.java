package com.javaclaw.server.model;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

import com.javaclaw.core.api.ApprovalPolicy;
import com.javaclaw.core.api.ModelMessage;
import com.javaclaw.core.api.ModelRequest;
import com.javaclaw.core.api.ModelStreamSink;
import com.javaclaw.core.api.ModelToolCall;
import com.javaclaw.core.api.ModelUsage;
import com.javaclaw.core.api.ThreadId;
import com.javaclaw.core.api.ToolDescriptor;
import com.javaclaw.core.api.TurnConfig;
import com.javaclaw.core.api.TurnId;
import com.javaclaw.sandbox.api.SandboxPolicy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SpringAiCloudModelGatewayTest {
    @TempDir
    Path temporary;

    @Test
    void carriesRealImageBytesToAllThreeProviderAdaptersWithoutAClientPath() throws Exception {
        byte[] png = java.util.Base64.getDecoder()
                .decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aKhEAAAAASUVORK5CYII=");
        String sha = java.util.HexFormat.of()
                .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(png));
        var image = new com.javaclaw.core.api.ModelImage(sha, "image/png", png);
        for (String provider : List.of("openai", "anthropic", "google")) {
            ChatModel fake = prompt -> {
                var user = assertInstanceOf(
                        org.springframework.ai.chat.messages.UserMessage.class,
                        prompt.getInstructions().getLast());
                assertEquals(1, user.getMedia().size());
                org.junit.jupiter.api.Assertions.assertArrayEquals(
                        png, user.getMedia().getFirst().getDataAsByteArray());
                return new ChatResponse(List.of(new Generation(
                        AssistantMessage.builder().content("a pixel").build())));
            };
            try (var gateway = new SpringAiCloudModelGateway(Map.of(provider, fake))) {
                var base = config();
                var config = new TurnConfig(
                        base.model(),
                        provider,
                        base.reasoningEffort(),
                        base.workingDirectory(),
                        base.sandboxPolicy(),
                        base.approvalPolicy(),
                        base.enabledTools(),
                        base.attributes());
                gateway.complete(new ModelRequest(
                        new ThreadId("image-thread"),
                        new TurnId("image-turn"),
                        List.of(new ModelMessage(
                                ModelMessage.Role.USER, "解释图片", null, null, List.of(), List.of(image))),
                        List.of(),
                        config));
            }
        }
        byte[] copied = image.bytes();
        copied[0] = 0;
        assertEquals((byte) 137, image.bytes()[0]);
    }

    @Test
    void mapsConversationToolsUsageAndOnlyExplicitReasoningSummary() throws Exception {
        AtomicReference<Prompt> captured = new AtomicReference<>();
        AssistantMessage output = AssistantMessage.builder()
                .content("working")
                .properties(Map.of("reasoning_summary", "short summary", "reasoning_content", "must not be exposed"))
                .toolCalls(List.of(
                        new AssistantMessage.ToolCall("call-2", "function", "read_file", "{\"path\":\"README.md\"}")))
                .build();
        ChatResponse response = new ChatResponse(
                List.of(new Generation(
                        output,
                        ChatGenerationMetadata.builder()
                                .finishReason("tool_calls")
                                .build())),
                ChatResponseMetadata.builder()
                        .model("test-model")
                        .usage(new DefaultUsage(11, 7, 18))
                        .build());
        ChatModel fake = prompt -> {
            captured.set(prompt);
            return response;
        };
        try (SpringAiCloudModelGateway gateway = new SpringAiCloudModelGateway(Map.of("openai", fake))) {
            ModelRequest request = new ModelRequest(
                    new ThreadId("thread-1"),
                    new TurnId("turn-1"),
                    List.of(
                            new ModelMessage(ModelMessage.Role.SYSTEM, "system", null),
                            new ModelMessage(ModelMessage.Role.USER, "hello", null),
                            new ModelMessage(
                                    ModelMessage.Role.ASSISTANT,
                                    "",
                                    null,
                                    null,
                                    List.of(new ModelToolCall("call-1", "read_file", "{\"path\":\"a\"}"))),
                            new ModelMessage(ModelMessage.Role.TOOL, "contents", "call-1", "read_file", List.of())),
                    List.of(new ToolDescriptor("read_file", "Read a file", """
                            {"type":"object","properties":{"path":{"type":"string"}},
                             "required":["path"],"additionalProperties":false}
                            """)),
                    config());

            var result = gateway.complete(request);
            assertEquals("working", result.text());
            assertEquals("short summary", result.reasoningSummary());
            assertEquals(11, result.usage().inputTokens());
            assertEquals(7, result.usage().outputTokens());
            assertEquals("read_file", result.toolCalls().getFirst().name());

            Prompt prompt = captured.get();
            assertInstanceOf(ToolResponseMessage.class, prompt.getInstructions().getLast());
            ToolCallingChatOptions options = assertInstanceOf(ToolCallingChatOptions.class, prompt.getOptions());
            assertEquals("test-model", options.getModel());
            assertEquals(
                    "read_file",
                    options.getToolCallbacks().getFirst().getToolDefinition().name());
            assertThrows(
                    IllegalStateException.class,
                    () -> options.getToolCallbacks().getFirst().call("{}"));
        }
    }

    @Test
    void advertisesButDoesNotEnableProvidersWithoutCredentials() {
        try (SpringAiCloudModelGateway gateway = SpringAiCloudModelGateway.fromEnvironment(Map.of())) {
            assertEquals(
                    List.of("openai", "anthropic", "google"),
                    gateway.descriptors().stream()
                            .map(CloudModelDescriptor::provider)
                            .toList());
            assertEquals(
                    0,
                    gateway.descriptors().stream()
                            .filter(CloudModelDescriptor::configured)
                            .count());
            assertThrows(
                    IllegalStateException.class,
                    () -> gateway.complete(new ModelRequest(
                            new ThreadId("thread"),
                            new TurnId("turn"),
                            List.of(new ModelMessage(ModelMessage.Role.USER, "hi", null)),
                            List.of(),
                            config())));
        }
    }

    @Test
    void explicitOfficialOpenAiEndpointStillRequiresARealCredential() {
        try (SpringAiCloudModelGateway gateway =
                SpringAiCloudModelGateway.fromEnvironment(Map.of("OPENAI_BASE_URL", "https://api.openai.com"))) {
            assertFalse(gateway.descriptors().stream()
                    .filter(value -> "openai".equals(value.provider()))
                    .findFirst()
                    .orElseThrow()
                    .configured());
        }
    }

    @Test
    void streamsProviderFragmentsBeforeReturningTheAssembledResponse() throws Exception {
        ChatResponse first = new ChatResponse(
                List.of(new Generation(AssistantMessage.builder().content("hel").build())));
        ChatResponse second = new ChatResponse(
                List.of(new Generation(AssistantMessage.builder().content("lo").build())),
                ChatResponseMetadata.builder()
                        .model("test-model")
                        .usage(new DefaultUsage(3, 2, 5))
                        .build());
        ChatModel fake = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                return second;
            }

            @Override
            public reactor.core.publisher.Flux<ChatResponse> stream(Prompt prompt) {
                return reactor.core.publisher.Flux.just(first, second);
            }
        };
        ArrayList<String> fragments = new ArrayList<>();
        AtomicReference<ModelUsage> usage = new AtomicReference<>();
        try (SpringAiCloudModelGateway gateway = new SpringAiCloudModelGateway(Map.of("openai", fake))) {
            var result = gateway.stream(
                    new ModelRequest(
                            new ThreadId("thread"),
                            new TurnId("turn"),
                            List.of(new ModelMessage(ModelMessage.Role.USER, "hi", null)),
                            List.of(),
                            config()),
                    new ModelStreamSink() {
                        @Override
                        public void text(String fragment) {
                            fragments.add(fragment);
                        }

                        @Override
                        public void usage(ModelUsage value) {
                            usage.set(value);
                        }
                    });
            assertEquals(List.of("hel", "lo"), fragments);
            assertEquals("hello", result.text());
            assertEquals(3, usage.get().inputTokens());
        }
    }

    private TurnConfig config() {
        return new TurnConfig(
                "test-model",
                "openai",
                "medium",
                temporary,
                SandboxPolicy.readOnly(Set.of(temporary), Set.of()),
                ApprovalPolicy.ON_RISK,
                Set.of(),
                Map.of());
    }
}
