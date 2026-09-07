package com.javaclaw.model;

import java.util.ArrayList;
import java.util.List;

import com.javaclaw.api.MessageRole;
import com.javaclaw.runtime.ModelMessage;
import com.javaclaw.runtime.ProviderState;

/** 旧 v2 仅保存最近响应输出；使用 Core 历史补齐输入，绝不丢弃其 opaque reasoning。 */
final class ResponsesLegacyStateRestorer {
    private final ProviderStateCodec states;
    private final OpenAiResponsesRequestMapper requests;

    ResponsesLegacyStateRestorer(ProviderStateCodec states, OpenAiResponsesRequestMapper requests) {
        this.states = states;
        this.requests = requests;
    }

    ProviderState restore(ProviderState state, List<ModelMessage> covered) {
        if (!"responses-state-v2".equals(state.format())) {
            return state;
        }
        var decoded = states.decode(state);
        if ("compacted".equals(decoded.kind())) {
            return states.withInputs(state, List.of());
        }
        List<ModelMessage> inputs = new ArrayList<>(covered);
        List<String> calls = decoded.outputItems().stream()
                .filter(item -> "function_call".equals(item.path("type").asText()))
                .map(item -> item.path("call_id").asText())
                .toList();
        if (!calls.isEmpty()) {
            ModelMessage last = removeAssistant(inputs);
            if (!last.toolCalls().stream().map(call -> call.callId()).toList().equals(calls)) {
                throw new IllegalArgumentException("旧 Provider state 的工具调用与 Core 历史不一致");
            }
        }
        StringBuilder text = new StringBuilder();
        decoded.outputItems().stream()
                .filter(item -> "message".equals(item.path("type").asText()))
                .forEach(item -> item.path("content").forEach(content -> {
                    if ("output_text".equals(content.path("type").asText())) {
                        text.append(content.path("text").asText());
                    }
                }));
        if (!text.isEmpty() && !removeAssistant(inputs).text().equals(text.toString())) {
            throw new IllegalArgumentException("旧 Provider state 的文本与 Core 历史不一致");
        }
        return states.withInputs(state, requests.messages(inputs));
    }

    private ModelMessage removeAssistant(List<ModelMessage> inputs) {
        if (inputs.isEmpty() || inputs.getLast().role() != MessageRole.ASSISTANT) {
            throw new IllegalArgumentException("旧 Provider state 缺少持久 Assistant 输出边界");
        }
        return inputs.removeLast();
    }
}
