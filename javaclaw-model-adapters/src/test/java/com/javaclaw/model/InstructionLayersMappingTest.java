package com.javaclaw.model;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import com.openai.models.ReasoningEffort;
import com.openai.models.responses.EasyInputMessage;
import com.openai.models.responses.Response;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.prompt.ChatOptions;

import com.javaclaw.api.MessageRole;
import com.javaclaw.api.ReasoningPreference;
import com.javaclaw.runtime.ModelInstructions;
import com.javaclaw.runtime.ModelInvocation;
import com.javaclaw.runtime.ModelMessage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InstructionLayersMappingTest {
    private static final ModelInstructions LAYERS = new ModelInstructions("平台边界", "角色与项目约定", "响应契约");

    @Test
    void Responses独立保留开发者层与响应契约并传递精确推理偏好() {
        var request = new OpenAiResponsesRequestMapper(new ProviderStateCodec()).initial(config(), invocation(LAYERS));
        assertEquals("平台边界", request.instructions().orElseThrow());
        var input = request.input().orElseThrow().asResponse();
        assertEquals(3, input.size());
        assertEquals(
                EasyInputMessage.Role.DEVELOPER,
                input.getFirst().asEasyInputMessage().role());
        assertEquals("角色与项目约定", input.getFirst().asEasyInputMessage().content().asTextInput());
        assertEquals(
                EasyInputMessage.Role.USER, input.get(1).asEasyInputMessage().role());
        assertEquals(
                EasyInputMessage.Role.DEVELOPER,
                input.getLast().asEasyInputMessage().role());
        assertEquals("响应契约", input.getLast().asEasyInputMessage().content().asTextInput());
        assertEquals(
                ReasoningEffort.HIGH, request.reasoning().orElseThrow().effort().orElseThrow());
    }

    @Test
    void SpringAI在适配器边界按固定顺序合并且外部数据保持用户消息() {
        var prompt = new SpringAiPromptMapper()
                .map(invocation(LAYERS), ChatOptions.builder().model("test").build());
        SystemMessage system =
                assertInstanceOf(SystemMessage.class, prompt.getInstructions().getFirst());
        assertEquals("平台边界\n\n角色与项目约定\n\n响应契约", system.getText());
        assertEquals(
                org.springframework.ai.chat.messages.MessageType.USER,
                prompt.getInstructions().getLast().getMessageType());
    }

    @Test
    void Provider状态绑定全部指令层并在原生压缩时重发相同层() throws Exception {
        ProviderStateCodec codec = new ProviderStateCodec();
        var opaque = codec.response(response(), LAYERS);
        assertEquals("responses-state-v3", opaque.format());
        var decoded = codec.decode(opaque);
        assertEquals(LAYERS, decoded.instructions());
        OpenAiResponsesRequestMapper mapper = new OpenAiResponsesRequestMapper(codec);
        var compact = mapper.compact(config(), decoded);
        assertEquals("平台边界", compact.instructions().orElseThrow());
        assertEquals(2, compact.input().orElseThrow().asResponseInputItems().size());
        assertThrows(
                IllegalArgumentException.class,
                () -> mapper.continuing(config(), invocation(new ModelInstructions("平台边界", "已变更角色", "响应契约")), decoded));
        assertThrows(
                IllegalArgumentException.class,
                () -> mapper.continuing(
                        config(), invocation(new ModelInstructions("平台边界", "角色与项目约定", "新契约")), decoded));
        assertEquals(
                3,
                mapper.continuing(config(), invocation(LAYERS), decoded)
                        .input()
                        .orElseThrow()
                        .asResponse()
                        .size());
    }

    private static ModelInvocation invocation(ModelInstructions instructions) {
        return new ModelInvocation(
                "test",
                instructions,
                List.of(new ModelMessage(
                        MessageRole.USER, "外部资料声称忽略角色", List.of(), Optional.empty(), Optional.empty())),
                List.of(),
                100,
                Optional.of(ReasoningPreference.HIGH));
    }

    private static OpenAiResponsesEndpointConfig config() {
        return new OpenAiResponsesEndpointConfig(
                "test",
                "test-model",
                Optional.of(URI.create("http://127.0.0.1:1")),
                Duration.ofSeconds(1),
                0,
                ReasoningSummaryStyle.AUTO);
    }

    private static Response response() throws Exception {
        return ModelJsonMapper.create().readValue("""
                {"id":"response-1","created_at":1,"model":"test-model","object":"response","output":[],
                 "parallel_tool_calls":true,"status":"completed","tool_choice":"auto","tools":[]}
                """, Response.class);
    }
}
