package com.javaclaw.model;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.openai.models.responses.CompactedResponse;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCompactionItem;
import com.openai.models.responses.ResponseCompactionItemParam;
import com.openai.models.responses.ResponseInputItem;
import com.openai.models.responses.ResponseOutputItem;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.runtime.ProviderState;

/**
 * OpenAI opaque output item 的唯一解析边界。
 *
 * <p>Harness 和持久化层只看到带格式版本的规范 JSON。未知 output item 会原样保留；只有实际续接时才要求当前适配器能够把它转换为 input item。
 */
final class ProviderStateCodec {
    static final String PROVIDER_ID = "openai-responses";
    static final String FORMAT = "responses-state-v1";

    private final JsonMapper mapper = ModelJsonMapper.create();

    ProviderState response(Response response, String instructions) {
        long inputTokens = response.usage().map(usage -> usage.inputTokens()).orElse(0L);
        return encode("response", response.id(), instructions, response.output(), inputTokens);
    }

    ProviderState compacted(CompactedResponse response, String instructions) {
        return encode(
                "compacted",
                response.id(),
                instructions,
                response.output(),
                response.usage().inputTokens());
    }

    DecodedState decode(ProviderState state) {
        Objects.requireNonNull(state, "state");
        if (!PROVIDER_ID.equals(state.providerId()) || !FORMAT.equals(state.format())) {
            throw new IllegalArgumentException("Provider state identity does not match OpenAI Responses v1");
        }
        try {
            JsonNode root = mapper.readTree(state.payload().json());
            String kind = requiredText(root, "kind");
            String responseId = requiredText(root, "responseId");
            String instructions = requiredNode(root, "instructions").textValue();
            long inputTokens = requiredNode(root, "inputTokens").longValue();
            JsonNode outputs = requiredNode(root, "outputItems");
            if (!outputs.isArray() || instructions == null || inputTokens < 0) {
                throw new IllegalArgumentException("Provider state has invalid field types");
            }
            List<JsonNode> items = new ArrayList<>();
            outputs.forEach(item -> items.add(item.deepCopy()));
            return new DecodedState(kind, responseId, instructions, items, inputTokens);
        } catch (IOException failure) {
            throw new IllegalArgumentException("Provider state is not valid JSON", failure);
        }
    }

    List<ResponseInputItem> inputItems(DecodedState state) {
        List<ResponseInputItem> result = new ArrayList<>();
        for (JsonNode raw : state.outputItems()) {
            try {
                result.add(toInput(mapper.treeToValue(raw, ResponseOutputItem.class)));
            } catch (IOException failure) {
                throw new IllegalArgumentException("Provider output item cannot be decoded", failure);
            }
        }
        return result;
    }

    private ProviderState encode(
            String kind, String responseId, String instructions, List<ResponseOutputItem> output, long inputTokens) {
        ObjectNode root = mapper.createObjectNode();
        root.put("formatVersion", 1);
        root.put("kind", kind);
        root.put("responseId", responseId);
        root.put("instructions", Objects.requireNonNull(instructions, "instructions"));
        root.put("inputTokens", inputTokens);
        ArrayNode items = root.putArray("outputItems");
        output.forEach(item -> items.add(mapper.valueToTree(item)));
        try {
            return new ProviderState(PROVIDER_ID, FORMAT, new CanonicalPayload(mapper.writeValueAsString(root)));
        } catch (IOException failure) {
            throw new IllegalStateException("Provider output items cannot be encoded", failure);
        }
    }

    private ResponseInputItem toInput(ResponseOutputItem item) {
        if (item.isMessage()) {
            return ResponseInputItem.ofResponseOutputMessage(item.asMessage());
        }
        if (item.isFunctionCall()) {
            return ResponseInputItem.ofFunctionCall(item.asFunctionCall());
        }
        if (item.isReasoning()) {
            return ResponseInputItem.ofReasoning(item.asReasoning());
        }
        if (item.isCompaction()) {
            return ResponseInputItem.ofCompaction(compaction(item.asCompaction()));
        }
        throw new IllegalArgumentException("Provider output item is preserved but cannot be resumed by this endpoint");
    }

    private ResponseCompactionItemParam compaction(ResponseCompactionItem item) {
        ResponseCompactionItemParam.Builder builder = ResponseCompactionItemParam.builder()
                .encryptedContent(item.encryptedContent())
                .id(item.id());
        return builder.build();
    }

    private JsonNode requiredNode(JsonNode root, String name) {
        JsonNode value = root.get(name);
        if (value == null || value.isNull()) {
            throw new IllegalArgumentException("Provider state is missing field: " + name);
        }
        return value;
    }

    private String requiredText(JsonNode root, String name) {
        String value = requiredNode(root, name).textValue();
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Provider state field must be text: " + name);
        }
        return value;
    }

    /** 已验证并与 Provider 输出边界对齐的私有状态。 */
    record DecodedState(
            String kind, String responseId, String instructions, List<JsonNode> outputItems, long inputTokens) {
        DecodedState {
            outputItems = List.copyOf(outputItems);
        }
    }
}
