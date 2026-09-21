package com.javaclaw.model;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.javaclaw.api.CancellationSource;
import com.javaclaw.api.ProviderAuthentication;
import com.javaclaw.api.TurnId;
import com.javaclaw.runtime.ModelFinishReason;
import com.javaclaw.runtime.ModelInvocationResult;
import com.javaclaw.runtime.ModelStreamEvent;
import com.javaclaw.runtime.ModelToolCall;
import com.javaclaw.runtime.ModelUsage;

import static com.javaclaw.model.LocalOpenAiSseServer.combinedTools;
import static com.javaclaw.model.LocalOpenAiSseServer.completed;
import static com.javaclaw.model.LocalOpenAiSseServer.emptyTools;
import static com.javaclaw.model.LocalOpenAiSseServer.finished;
import static com.javaclaw.model.LocalOpenAiSseServer.text;
import static com.javaclaw.model.LocalOpenAiSseServer.tool;
import static com.javaclaw.model.ModelAdapterTestFixtures.ENDPOINT_ID;
import static com.javaclaw.model.ModelAdapterTestFixtures.TOOL;
import static com.javaclaw.model.ModelAdapterTestFixtures.invocation;
import static com.javaclaw.model.ModelAdapterTestFixtures.simpleInvocation;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 通过真实官方 SDK 和 Spring AI 验证兼容端点的工具 SSE 分片，不连接真实服务。 */
@Timeout(20)
class OpenAiStreamingCompatibilityTest {
    @Test
    void acceptsStandardContinuationWithoutIdOrName() throws Exception {
        assertContinuation(null);
    }

    @Test
    void acceptsContinuationWithEmptyIdAndNoName() throws Exception {
        assertContinuation("");
    }

    @Test
    void acceptsContinuationWithRepeatedIdAndNoName() throws Exception {
        assertContinuation("call-local");
    }

    @Test
    void mergesInterleavedParallelCallsByIndexWithoutMixingArguments() throws Exception {
        List<String> response = completed(
                tool(0, "call-first", "read_file", "{\"path\":\""),
                tool(1, "call-second", "read_file", "{\"path\":\""),
                tool(1, "call-second", null, "SECOND"),
                tool(0, "", null, "FIRST"),
                tool(1, null, null, ".md\"}"),
                tool(0, null, null, ".md\"}"));
        try (var server = new LocalOpenAiSseServer(List.of(response));
                var adapter = adapter(server)) {
            List<ModelStreamEvent> events = new ArrayList<>();

            var result = invoke(adapter, events);

            assertEquals(2, result.toolCalls().size());
            assertCall(result.toolCalls().get(0), "call-first", "FIRST.md");
            assertCall(result.toolCalls().get(1), "call-second", "SECOND.md");
            assertTerminalEvents(result, events);
            assertRequests(server, 1);
        }
    }

    @Test
    void completesFunctionNameThatArrivesAfterIdAndArguments() throws Exception {
        List<String> response = completed(
                tool(0, "call-late-name", null, "{\"path\":\""),
                tool(0, null, "read_file", null),
                tool(0, "call-late-name", null, "LATE.md\"}"));
        try (var server = new LocalOpenAiSseServer(List.of(response));
                var adapter = adapter(server)) {
            List<ModelStreamEvent> events = new ArrayList<>();

            var result = invoke(adapter, events);

            assertEquals(1, result.toolCalls().size());
            assertCall(result.toolCalls().getFirst(), "call-late-name", "LATE.md");
            assertTerminalEvents(result, events);
            assertRequests(server, 1);
        }
    }

    @Test
    void completesIdThatArrivesAfterNameAndIgnoresEmptyNameContinuations() throws Exception {
        List<String> response =
                completed(tool(0, null, "read_file", "{\"path\":\""), tool(0, "call-late-id", "", "LATE.md\"}"));
        try (var server = new LocalOpenAiSseServer(List.of(response));
                var adapter = adapter(server)) {
            List<ModelStreamEvent> events = new ArrayList<>();

            var result = invoke(adapter, events);

            assertCall(result.toolCalls().getFirst(), "call-late-id", "LATE.md");
            assertTerminalEvents(result, events);
            assertRequests(server, 1);
        }
    }

    @Test
    void rejectsDifferentToolIndicesReusingOneCallId() throws Exception {
        assertRejected(
                completed(tool(0, "call-duplicate", "read_file", "{}"), tool(1, "call-duplicate", "read_file", "{}")));
    }

    @Test
    void preservesTextDeltasAndUsageOnlyTailAlongsideToolCalls() throws Exception {
        List<String> response = completed(
                text("正在"),
                tool(0, "call-text", "read_file", "{\"path\":\""),
                text("读取"),
                tool(0, "", null, "README.md\"}"));
        try (var server = new LocalOpenAiSseServer(List.of(response));
                var adapter = adapter(server)) {
            List<ModelStreamEvent> events = new ArrayList<>();

            var result = invoke(adapter, events);

            assertEquals("正在读取", result.text());
            assertEquals(
                    "正在读取",
                    events.stream()
                            .filter(ModelStreamEvent.TextDelta.class::isInstance)
                            .map(ModelStreamEvent.TextDelta.class::cast)
                            .map(ModelStreamEvent.TextDelta::text)
                            .collect(Collectors.joining()));
            assertEquals(new ModelUsage(8, 3, 0, 0), result.usage());
            assertCall(result.toolCalls().getFirst(), "call-text", "README.md");
            assertTerminalEvents(result, events);
            assertRequests(server, 1);
        }
    }

    @Test
    void isolatesToolAccumulationAcrossConsecutiveCallsOnSameAdapter() throws Exception {
        List<String> first =
                completed(tool(0, "call-before", "read_file", "{\"path\":\""), tool(0, "", null, "BEFORE.md\"}"));
        List<String> second = completed(
                tool(0, "call-after", null, "{\"path\":\""), tool(0, "call-after", "read_file", "AFTER.md\"}"));
        try (var server = new LocalOpenAiSseServer(List.of(first, second));
                var adapter = adapter(server)) {
            List<ModelStreamEvent> firstEvents = new ArrayList<>();
            List<ModelStreamEvent> secondEvents = new ArrayList<>();

            var firstResult = invoke(adapter, firstEvents);
            var secondResult = invoke(adapter, secondEvents);

            assertEquals(1, firstResult.toolCalls().size());
            assertEquals(1, secondResult.toolCalls().size());
            assertCall(firstResult.toolCalls().getFirst(), "call-before", "BEFORE.md");
            assertCall(secondResult.toolCalls().getFirst(), "call-after", "AFTER.md");
            assertTerminalEvents(firstResult, firstEvents);
            assertTerminalEvents(secondResult, secondEvents);
            assertRequests(server, 2);
        }
    }

    @Test
    void rejectsMissingNameBeforePublishingAnyToolCall() throws Exception {
        assertRejected(completed(tool(0, "call-missing-name", null, "{\"path\":\"README.md\"}")));
    }

    @Test
    void rejectsMissingIdBeforePublishingAnyToolCall() throws Exception {
        assertRejected(completed(tool(0, "", "read_file", "{\"path\":\"README.md\"}")));
    }

    @Test
    void rejectsIncompleteArgumentsBeforePublishingAnyToolCall() throws Exception {
        assertRejected(completed(tool(0, "call-incomplete", "read_file", "{\"path\":\"README.md")));
    }

    @Test
    void rejectsWholeBatchWhenAnotherParallelCallIsIncomplete() throws Exception {
        assertRejected(completed(
                tool(0, "call-complete", "read_file", "{\"path\":\"README.md\"}"),
                tool(1, "call-incomplete", null, "{}")));
    }

    @Test
    void rejectsToolStreamWithoutFinishReason() throws Exception {
        assertRejected(List.of(tool(0, "call-no-finish", "read_file", "{\"path\":\"README.md\"}")));
    }

    @Test
    void rejectsToolStreamEndingWithStopInsteadOfToolCalls() throws Exception {
        assertRejected(List.of(tool(0, "call-stop", "read_file", "{\"path\":\"README.md\"}"), finished("stop")));
    }

    @Test
    void rejectsToolStreamTruncatedByLengthLimit() throws Exception {
        assertRejected(List.of(tool(0, "call-length", "read_file", "{\"path\":\"README.md"), finished("length")));
    }

    @Test
    void rejectsConflictingIdsForSameToolIndex() throws Exception {
        assertRejected(completed(
                tool(0, "call-first", "read_file", "{\"path\":\""), tool(0, "call-different", null, "README.md\"}")));
    }

    @Test
    void rejectsConflictingNamesForSameToolIndex() throws Exception {
        assertRejected(completed(
                tool(0, "call-name", "read_file", "{\"path\":\""), tool(0, null, "different_tool", "README.md\"}")));
    }

    @Test
    void ignoresEmptyToolCallArraysBetweenArgumentFragments() throws Exception {
        List<String> response = completed(
                emptyTools(),
                tool(0, "call-empty-array", "read_file", "{\"path\":\""),
                emptyTools(),
                tool(0, "", null, "README.md\"}"),
                emptyTools());
        try (var server = new LocalOpenAiSseServer(List.of(response));
                var adapter = adapter(server)) {
            List<ModelStreamEvent> events = new ArrayList<>();

            var result = invoke(adapter, events);

            assertEquals(1, result.toolCalls().size());
            assertCall(result.toolCalls().getFirst(), "call-empty-array", "README.md");
            assertTerminalEvents(result, events);
            assertRequests(server, 1);
        }
    }

    @Test
    void handlesMultipleToolsInOneDeltaAndReversedContinuationOrder() throws Exception {
        List<String> response = completed(
                combinedTools(
                        tool(0, "call-first", "read_file", "{\"path\":\""),
                        tool(1, "call-second", "read_file", "{\"path\":\"")),
                combinedTools(tool(1, "call-second", null, "SECOND.md\"}"), tool(0, "", null, "FIRST.md\"}")));
        try (var server = new LocalOpenAiSseServer(List.of(response));
                var adapter = adapter(server)) {
            List<ModelStreamEvent> events = new ArrayList<>();

            var result = invoke(adapter, events);

            assertEquals(2, result.toolCalls().size());
            assertCall(result.toolCalls().get(0), "call-first", "FIRST.md");
            assertCall(result.toolCalls().get(1), "call-second", "SECOND.md");
            assertTerminalEvents(result, events);
            assertRequests(server, 1);
        }
    }

    private static void assertContinuation(String continuationId) throws Exception {
        List<String> response = completed(
                tool(0, "call-local", "read_file", "{\"path\":\""), tool(0, continuationId, null, "README.md\"}"));
        try (var server = new LocalOpenAiSseServer(List.of(response));
                var adapter = adapter(server)) {
            List<ModelStreamEvent> events = new ArrayList<>();

            var result = invoke(adapter, events);

            assertEquals(1, result.toolCalls().size());
            assertCall(result.toolCalls().getFirst(), "call-local", "README.md");
            assertEquals(ModelFinishReason.TOOL_CALLS, result.finishReason());
            assertTerminalEvents(result, events);
            assertRequests(server, 1);
        }
    }

    private static void assertRejected(List<String> response) throws Exception {
        try (var server = new LocalOpenAiSseServer(List.of(response));
                var adapter = adapter(server)) {
            List<ModelStreamEvent> events = new ArrayList<>();

            RuntimeException failure = assertThrows(RuntimeException.class, () -> invoke(adapter, events));

            assertFalse(events.stream().anyMatch(ModelStreamEvent.ToolCallReady.class::isInstance));
            assertFalse(events.stream().anyMatch(ModelStreamEvent.Usage.class::isInstance));
            // 失败必须来自协议校验，不能依靠上游 Optional.get 崩溃或执行定义回调来阻止工具执行。
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                assertFalse(cause instanceof NoSuchElementException);
                assertFalse(String.valueOf(cause.getMessage()).contains("工具只能由 JavaClaw Turn Harness 执行"));
            }
            assertRequests(server, 1);
        }
    }

    private static ModelInvocationResult invoke(SpringAiModelAdapter adapter, List<ModelStreamEvent> events)
            throws Exception {
        return adapter.invoke(
                TurnId.random(),
                invocation(simpleInvocation().messages(), List.of(TOOL)),
                (turnId, event, cancellation) -> events.add(event),
                new CancellationSource());
    }

    private static SpringAiModelAdapter adapter(LocalOpenAiSseServer server) {
        var config = new SpringAiEndpointConfig(
                ENDPOINT_ID,
                SpringAiProvider.OPENAI_COMPATIBLE,
                "provider-model",
                Optional.of(server.baseUri()),
                ProviderAuthentication.API_KEY,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Duration.ofSeconds(5),
                0);
        // 真实工厂负责 SDK、Spring AI 和工具执行策略，夹具不能替换 ChatModel 或偷偷改写选项。
        SpringAiEndpoint endpoint = new SpringAiModelFactory().create(config, "local-sse-fixture-key".toCharArray());
        return new SpringAiModelAdapter(List.of(endpoint));
    }

    private static void assertCall(ModelToolCall call, String id, String path) {
        assertEquals(id, call.callId());
        assertEquals(TOOL.identity(), call.tool());
        assertEquals("{\"path\":\"" + path + "\"}", call.arguments().json());
    }

    private static void assertTerminalEvents(ModelInvocationResult result, List<ModelStreamEvent> events) {
        assertEquals(
                result.toolCalls(),
                events.stream()
                        .filter(ModelStreamEvent.ToolCallReady.class::isInstance)
                        .map(ModelStreamEvent.ToolCallReady.class::cast)
                        .map(ModelStreamEvent.ToolCallReady::call)
                        .toList());
        assertEquals(
                List.of(result.usage()),
                events.stream()
                        .filter(ModelStreamEvent.Usage.class::isInstance)
                        .map(ModelStreamEvent.Usage.class::cast)
                        .map(ModelStreamEvent.Usage::value)
                        .toList());
    }

    private static void assertRequests(LocalOpenAiSseServer server, int count) {
        assertEquals(count, server.requests().size());
        for (var request : server.requests()) {
            assertEquals("POST", request.method());
            assertEquals("/v1/chat/completions", request.path());
            assertTrue(request.streaming());
            assertEquals(1, request.toolCount());
        }
    }
}
