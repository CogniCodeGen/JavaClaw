package com.javaclaw.model;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.MessageRole;
import com.javaclaw.api.ProviderAuthentication;
import com.javaclaw.api.ToolDescriptor;
import com.javaclaw.api.ToolIdentity;
import com.javaclaw.api.ToolRisk;
import com.javaclaw.runtime.ModelFinishReason;
import com.javaclaw.runtime.ModelMessage;
import com.javaclaw.runtime.ModelUsage;

import static com.javaclaw.model.ModelAdapterTestFixtures.TOOL;
import static com.javaclaw.model.ModelAdapterTestFixtures.invocation;
import static com.javaclaw.model.ModelAdapterTestFixtures.response;
import static com.javaclaw.model.ModelAdapterTestFixtures.simpleInvocation;
import static com.javaclaw.model.ModelAdapterTestFixtures.toolCall;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpringAiMapperTest {
    private final SpringAiResultMapper results = new SpringAiResultMapper(new CanonicalJsonCodec());

    @Test
    void mapsAllPromptRolesAndOmitsBlankLeadingSystemInstruction() {
        var invocation = new com.javaclaw.runtime.ModelInvocation(
                "model",
                "  ",
                List.of(
                        new ModelMessage(MessageRole.SYSTEM, "规则", List.of(), Optional.empty(), Optional.empty()),
                        new ModelMessage(MessageRole.USER, "问题", List.of(), Optional.empty(), Optional.empty()),
                        ModelMessage.assistant("调用", List.of(toolCall("call-1"))),
                        ModelMessage.tool("call-1", TOOL.identity().name(), "结果")),
                List.of(TOOL),
                100);

        var prompt = new SpringAiPromptMapper()
                .map(invocation, ChatOptions.builder().model("model").build());

        assertEquals(4, prompt.getInstructions().size());
        assertEquals("model", prompt.getOptions().getModel());
        var assistant = assertInstanceOf(
                AssistantMessage.class, prompt.getInstructions().get(2));
        assertEquals("call-1", assistant.getToolCalls().getFirst().id());
    }

    @Test
    void mapsUsageMetadataAndToolCallsAgainstFrozenDirectory() {
        AssistantMessage output = AssistantMessage.builder()
                .content("原始")
                .properties(Map.of("outputWithoutThoughts", "可见", "thoughts", "摘要"))
                .toolCalls(List.of(new AssistantMessage.ToolCall("call-2", "function", "read_file", "")))
                .build();
        ChatResponse response = response(
                output,
                ChatGenerationMetadata.builder().finishReason("stop").build(),
                new DefaultUsage(-2, 7, 5, Map.of(), 20L, 0L));

        var mapped = results.map(invocation(List.of(), List.of(TOOL)), response);

        assertEquals("{}", mapped.toolCalls().getFirst().arguments().json());
        assertEquals(new ModelUsage(0, 7, 0, 0), mapped.usage());
        assertEquals(ModelFinishReason.TOOL_CALLS, mapped.finishReason());
        assertEquals(Optional.of("摘要"), mapped.reasoningSummary());
        assertEquals("可见", mapped.text());
    }

    @Test
    void classifiesEverySupportedFinishReason() {
        assertEquals(ModelFinishReason.COMPLETE, finish(null, Set.of()));
        assertEquals(ModelFinishReason.COMPLETE, finish("stop", Set.of()));
        assertEquals(ModelFinishReason.COMPLETE, finish("end_turn", Set.of()));
        assertEquals(ModelFinishReason.LENGTH, finish("max_tokens", Set.of()));
        assertEquals(ModelFinishReason.LENGTH, finish("length", Set.of()));
        assertEquals(ModelFinishReason.CONTENT_FILTER, finish("safety", Set.of()));
        assertEquals(ModelFinishReason.CONTENT_FILTER, finish("anything", Set.of("blocked")));
        assertEquals(ModelFinishReason.OTHER, finish("provider_specific", Set.of()));
    }

    @Test
    void rejectsMissingResponseUnknownToolDuplicateDirectoryAndInvalidArguments() {
        assertThrows(IllegalStateException.class, () -> results.map(simpleInvocation(), null));
        assertThrows(IllegalStateException.class, () -> results.map(simpleInvocation(), new ChatResponse(List.of())));

        AssistantMessage unknown = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("id", "function", "unknown", "{}")))
                .build();
        assertThrows(IllegalStateException.class, () -> results.map(simpleInvocation(), response(unknown, null, null)));

        ToolDescriptor duplicate = new ToolDescriptor(
                new ToolIdentity("extension", TOOL.identity().name(), 4),
                "重复名称",
                new CanonicalPayload("{\"type\":\"object\"}"),
                new CanonicalPayload("{\"type\":\"object\"}"),
                ToolRisk.READ_ONLY,
                Set.of());
        assertThrows(
                IllegalArgumentException.class,
                () -> results.map(
                        invocation(List.of(), List.of(TOOL, duplicate)),
                        response(new AssistantMessage("完成"), null, null)));

        AssistantMessage invalid = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("id", "function", "read_file", "[]")))
                .build();
        assertThrows(
                IllegalArgumentException.class,
                () -> results.map(invocation(List.of(), List.of(TOOL)), response(invalid, null, null)));
    }

    @Test
    void normalizesProviderJsonAndExposesDefinitionWithoutExecutingIt() {
        CanonicalJsonCodec json = new CanonicalJsonCodec();
        assertEquals("{\"a\":1,\"b\":2}", json.object("{\"b\":2,\"a\":1}").json());
        assertThrows(IllegalArgumentException.class, () -> json.object("[]"));
        assertThrows(IllegalArgumentException.class, () -> json.object("{"));
        assertThrows(NullPointerException.class, () -> json.object(null));

        DefinitionOnlyToolCallback callback = new DefinitionOnlyToolCallback(TOOL);
        assertEquals("read_file", callback.getToolDefinition().name());
        assertTrue(callback.getToolDefinition().inputSchema().contains("object"));
        assertThrows(IllegalStateException.class, () -> callback.call("{}"));
        assertThrows(NullPointerException.class, () -> new DefinitionOnlyToolCallback(null));
    }

    @Test
    void validatesSpringEndpointConfigurationAndResourceOwnership() {
        var config = new SpringAiEndpointConfig(
                " endpoint ",
                SpringAiProvider.OPENAI_COMPATIBLE,
                " model ",
                Optional.of(URI.create("https://example.invalid")),
                ProviderAuthentication.API_KEY,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Duration.ofSeconds(5),
                10);
        assertEquals("endpoint", config.endpointId());
        assertEquals("model", config.model());

        assertThrows(IllegalArgumentException.class, () -> config(" ", "model", Duration.ofSeconds(1), 0));
        assertThrows(IllegalArgumentException.class, () -> config("endpoint", " ", Duration.ofSeconds(1), 0));
        assertThrows(IllegalArgumentException.class, () -> config("endpoint", "model", Duration.ZERO, 0));
        assertThrows(IllegalArgumentException.class, () -> config("endpoint", "model", Duration.ofSeconds(1), -1));
        assertThrows(IllegalArgumentException.class, () -> config("endpoint", "model", Duration.ofSeconds(1), 11));
        assertThrows(
                NullPointerException.class,
                () -> new SpringAiEndpoint(null, prompt -> null, capabilities(), ignored -> null, () -> {}));
        assertThrows(
                NullPointerException.class,
                () -> new SpringAiEndpoint("id", null, capabilities(), ignored -> null, () -> {}));
        assertThrows(
                NullPointerException.class,
                () -> new SpringAiEndpoint("id", prompt -> null, null, ignored -> null, () -> {}));
    }

    private ModelFinishReason finish(String reason, Set<String> filters) {
        ChatGenerationMetadata metadata = ChatGenerationMetadata.builder()
                .finishReason(reason)
                .contentFilters(filters)
                .build();
        return results.map(simpleInvocation(), response(new AssistantMessage("完成"), metadata, null))
                .finishReason();
    }

    private static SpringAiEndpointConfig config(String endpoint, String model, Duration timeout, int retries) {
        return new SpringAiEndpointConfig(
                endpoint,
                SpringAiProvider.ANTHROPIC,
                model,
                Optional.empty(),
                ProviderAuthentication.API_KEY,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                timeout,
                retries);
    }

    private static com.javaclaw.runtime.ModelCapabilities capabilities() {
        return new com.javaclaw.runtime.ModelCapabilities(false, false, false, false, false, false, false);
    }
}
