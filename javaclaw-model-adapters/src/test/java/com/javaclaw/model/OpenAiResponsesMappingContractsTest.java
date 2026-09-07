package com.javaclaw.model;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.openai.models.responses.Response;
import org.junit.jupiter.api.Test;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.MessageRole;
import com.javaclaw.api.ToolDescriptor;
import com.javaclaw.api.ToolIdentity;
import com.javaclaw.api.ToolRisk;
import com.javaclaw.runtime.ModelFinishReason;
import com.javaclaw.runtime.ModelInstructions;
import com.javaclaw.runtime.ModelInvocation;
import com.javaclaw.runtime.ModelMessage;
import com.javaclaw.runtime.ProviderState;

import static com.javaclaw.model.ModelAdapterTestFixtures.TOOL;
import static com.javaclaw.model.ModelAdapterTestFixtures.toolCall;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiResponsesMappingContractsTest {
    private static final JsonMapper JSON = ModelJsonMapper.create();
    private static final String ENDPOINT = "responses-main";
    private static final String INSTRUCTIONS = "仅输出可验证结论";

    @Test
    void 请求映射保留全部消息角色工具与摘要等级() {
        OpenAiResponsesRequestMapper mapper = new OpenAiResponsesRequestMapper(new ProviderStateCodec());
        List<ModelMessage> messages = List.of(
                message(MessageRole.SYSTEM, "局部规则"),
                message(MessageRole.USER, "问题"),
                ModelMessage.assistant("准备调用", List.of(toolCall("call-1"))),
                ModelMessage.tool("call-1", TOOL.identity().name(), "工具结果"));
        ModelInvocation invocation = invocation(INSTRUCTIONS, messages, List.of(TOOL));

        var input = mapper.initial(config(ReasoningSummaryStyle.AUTO), invocation)
                .input()
                .orElseThrow()
                .asResponse();

        assertEquals(5, input.size());
        assertTrue(input.get(0).isEasyInputMessage());
        assertTrue(input.get(1).isEasyInputMessage());
        assertTrue(input.get(2).isEasyInputMessage());
        assertTrue(input.get(3).isFunctionCall());
        assertTrue(input.get(4).isFunctionCallOutput());
        assertEquals(
                TOOL.identity().name(),
                mapper.initial(config(ReasoningSummaryStyle.CONCISE), invocation)
                        .tools()
                        .orElseThrow()
                        .getFirst()
                        .asFunction()
                        .name());
        assertFalse(mapper.initial(config(ReasoningSummaryStyle.DETAILED), invocation)
                .store()
                .orElseThrow());
    }

    @Test
    void 空文本Assistant只映射工具调用且无Assistant历史时完整续接() {
        ProviderStateCodec states = new ProviderStateCodec();
        OpenAiResponsesRequestMapper mapper = new OpenAiResponsesRequestMapper(states);
        ModelInvocation initial = invocation(
                INSTRUCTIONS, List.of(ModelMessage.assistant("", List.of(toolCall("call-2")))), List.of(TOOL));
        assertEquals(
                1,
                mapper.initial(config(ReasoningSummaryStyle.AUTO), initial)
                        .input()
                        .orElseThrow()
                        .asResponse()
                        .size());

        ProviderStateCodec.DecodedState emptyState = new ProviderStateCodec.DecodedState(
                "response", "response-1", new ModelInstructions(INSTRUCTIONS, "", ""), List.of(), 2);
        ModelInvocation continuation = invocation(INSTRUCTIONS, List.of(message(MessageRole.USER, "继续")), List.of());
        assertEquals(
                1,
                mapper.continuing(config(ReasoningSummaryStyle.AUTO), continuation, emptyState)
                        .input()
                        .orElseThrow()
                        .asResponse()
                        .size());

        ModelInvocation changed = invocation("已变更的规则", continuation.messages(), List.of());
        assertThrows(
                IllegalArgumentException.class,
                () -> mapper.continuing(config(ReasoningSummaryStyle.AUTO), changed, emptyState));
    }

    @Test
    void 工具Schema必须是可解析JSON对象() {
        ToolDescriptor malformed = new ToolDescriptor(
                new ToolIdentity("core", "malformed", 1),
                "无效 Schema",
                new CanonicalPayload("{not-json}"),
                new CanonicalPayload("{}"),
                ToolRisk.READ_ONLY,
                Set.of());
        ModelInvocation invocation = invocation(INSTRUCTIONS, List.of(), List.of(malformed));

        assertThrows(
                IllegalArgumentException.class,
                () -> new OpenAiResponsesRequestMapper(new ProviderStateCodec())
                        .initial(config(ReasoningSummaryStyle.AUTO), invocation));
    }

    @Test
    void Provider状态拒绝错误身份缺失字段和非法类型() {
        ProviderStateCodec codec = new ProviderStateCodec();

        assertThrows(NullPointerException.class, () -> codec.decode(null));
        assertThrows(
                IllegalArgumentException.class,
                () -> codec.decode(state("other", ProviderStateCodec.FORMAT, validStateBody())));
        assertThrows(
                IllegalArgumentException.class,
                () -> codec.decode(state(ProviderStateCodec.PROVIDER_ID, "other-format", validStateBody())));
        assertThrows(
                IllegalArgumentException.class,
                () -> codec.decode(state(ProviderStateCodec.PROVIDER_ID, ProviderStateCodec.FORMAT, "{not-json}")));
        assertThrows(
                IllegalArgumentException.class,
                () -> codec.decode(
                        state(ProviderStateCodec.PROVIDER_ID, ProviderStateCodec.FORMAT, "{\"kind\":\"response\"}")));
        assertThrows(
                IllegalArgumentException.class,
                () -> codec.decode(state(
                        ProviderStateCodec.PROVIDER_ID,
                        ProviderStateCodec.FORMAT,
                        "{\"kind\":\"response\",\"responseId\":\"id\",\"instructions\":7,"
                                + "\"inputTokens\":1,\"outputItems\":[]}")));
        assertThrows(
                IllegalArgumentException.class,
                () -> codec.decode(state(
                        ProviderStateCodec.PROVIDER_ID,
                        ProviderStateCodec.FORMAT,
                        "{\"kind\":\"response\",\"responseId\":\"id\",\"instructions\":\"rules\","
                                + "\"inputTokens\":-1,\"outputItems\":{}}")));
    }

    @Test
    void Provider状态可把函数调用输出恢复为下一次输入() throws Exception {
        ProviderStateCodec codec = new ProviderStateCodec();
        ProviderState state = codec.response(functionCallResponse(), new ModelInstructions(INSTRUCTIONS, "", ""));
        ProviderStateCodec.DecodedState decoded = codec.decode(state);

        assertEquals("response", decoded.kind());
        assertEquals(1, decoded.outputItems().size());
        assertTrue(codec.inputItems(decoded).getFirst().isFunctionCall());
    }

    @Test
    void 结果映射校验冻结工具并覆盖结束原因与流式回退() throws Exception {
        OpenAiResponsesResultMapper mapper =
                new OpenAiResponsesResultMapper(new CanonicalJsonCodec(), new ProviderStateCodec());
        ModelInvocation withTool = invocation(INSTRUCTIONS, List.of(), List.of(TOOL));

        var toolResult = mapper.map(withTool, functionCallResponse(), "streamed", "summary");
        assertEquals(ModelFinishReason.TOOL_CALLS, toolResult.finishReason());
        assertEquals(TOOL.identity(), toolResult.toolCalls().getFirst().tool());
        assertEquals("streamed", toolResult.text());
        assertEquals(Optional.of("summary"), toolResult.reasoningSummary());

        assertThrows(
                IllegalStateException.class,
                () -> mapper.map(invocation(INSTRUCTIONS, List.of(), List.of()), functionCallResponse(), "", ""));

        ToolDescriptor duplicate = new ToolDescriptor(
                new ToolIdentity("extension", TOOL.identity().name(), 2),
                "同名工具",
                new CanonicalPayload("{}"),
                new CanonicalPayload("{}"),
                ToolRisk.READ_ONLY,
                Set.of());
        assertThrows(
                IllegalArgumentException.class,
                () -> mapper.map(
                        invocation(INSTRUCTIONS, List.of(), List.of(TOOL, duplicate)),
                        emptyResponse("completed"),
                        "",
                        ""));

        assertEquals(
                ModelFinishReason.CONTENT_FILTER,
                mapper.map(invocation(INSTRUCTIONS, List.of(), List.of()), refusalResponse(), "", "")
                        .finishReason());
        assertEquals(
                ModelFinishReason.LENGTH,
                mapper.map(invocation(INSTRUCTIONS, List.of(), List.of()), emptyResponse("incomplete"), "", "")
                        .finishReason());
        assertEquals(
                ModelFinishReason.OTHER,
                mapper.map(invocation(INSTRUCTIONS, List.of(), List.of()), emptyResponse("failed"), "", "")
                        .finishReason());
    }

    private static ModelInvocation invocation(
            String instructions, List<ModelMessage> messages, List<ToolDescriptor> tools) {
        return new ModelInvocation(ENDPOINT, instructions, messages, tools, 256);
    }

    private static ModelMessage message(MessageRole role, String text) {
        return new ModelMessage(role, text, List.of(), Optional.empty(), Optional.empty());
    }

    private static OpenAiResponsesEndpointConfig config(ReasoningSummaryStyle style) {
        return new OpenAiResponsesEndpointConfig(
                ENDPOINT, "gpt-test", Optional.<URI>empty(), Duration.ofSeconds(5), 0, style);
    }

    private static ProviderState state(String provider, String format, String body) {
        return new ProviderState(provider, format, new CanonicalPayload(body));
    }

    private static String validStateBody() {
        return "{\"kind\":\"response\",\"responseId\":\"id\",\"instructions\":\"rules\","
                + "\"inputTokens\":1,\"outputItems\":[]}";
    }

    private static Response functionCallResponse() throws Exception {
        return response("""
                [{
                  "type": "function_call",
                  "call_id": "call-1",
                  "name": "read_file",
                  "arguments": "{\\"path\\":\\"README.md\\"}",
                  "status": "completed"
                }]
                """, "completed");
    }

    private static Response refusalResponse() throws Exception {
        return response("""
                [{
                  "id": "message-1",
                  "type": "message",
                  "role": "assistant",
                  "status": "completed",
                  "content": [{"type": "refusal", "refusal": "blocked"}]
                }]
                """, "completed");
    }

    private static Response emptyResponse(String status) throws Exception {
        return response("[]", status);
    }

    private static Response response(String output, String status) throws Exception {
        return JSON.readValue("""
                {
                  "id": "response-1",
                  "created_at": 1,
                  "model": "gpt-test",
                  "object": "response",
                  "output": %s,
                  "parallel_tool_calls": true,
                  "status": "%s",
                  "tool_choice": "auto",
                  "tools": []
                }
                """.formatted(output, status), Response.class);
    }
}
