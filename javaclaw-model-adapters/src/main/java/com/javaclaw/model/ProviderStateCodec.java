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
import com.javaclaw.runtime.ModelInstructions;
import com.javaclaw.runtime.ProviderState;

/**
 * OpenAI opaque output item 的唯一解析边界。
 *
 * <p>Harness 和持久化层只看到带格式版本的规范 JSON。未知 output item 会原样保留；只有实际续接时才要求当前适配器能够把它转换为 input item。
 */
final class ProviderStateCodec {
    static final String PROVIDER_ID = "openai-responses";
    static final String FORMAT = "responses-state-v3";

    private final JsonMapper mapper = ModelJsonMapper.create();

    ProviderState response(Response response, ModelInstructions instructions) {
        long inputTokens = response.usage().map(usage -> usage.inputTokens()).orElse(0L);
        return encode("response", response.id(), instructions, response.output(), inputTokens);
    }

    ProviderState compacted(CompactedResponse response, ModelInstructions instructions) {
        return encode(
                "compacted",
                response.id(),
                instructions,
                response.output(),
                response.usage().inputTokens());
    }

    DecodedState decode(ProviderState state) {
        Objects.requireNonNull(state, "state");
        if (!PROVIDER_ID.equals(state.providerId())
                || !(FORMAT.equals(state.format()) || "responses-state-v2".equals(state.format()))) {
            throw new IllegalArgumentException("Provider state identity does not match OpenAI Responses");
        }
        try {
            JsonNode root = mapper.readTree(state.payload().json());
            String kind = requiredText(root, "kind");
            String responseId = requiredText(root, "responseId");
            int version = requiredNode(root, "formatVersion").intValue();
            if (version != (FORMAT.equals(state.format()) ? 3 : 2)) {
                throw new IllegalArgumentException("Provider state formatVersion does not match its envelope");
            }
            ModelInstructions instructions = decodeInstructions(requiredNode(root, "instructions"));
            long inputTokens = requiredNode(root, "inputTokens").longValue();
            JsonNode outputs = requiredNode(root, "outputItems");
            if (!outputs.isArray() || inputTokens < 0) {
                throw new IllegalArgumentException("Provider state has invalid field types");
            }
            List<JsonNode> items = new ArrayList<>();
            outputs.forEach(item -> items.add(item.deepCopy()));
            List<JsonNode> inputs = new ArrayList<>();
            if (version == 3) {
                JsonNode replay = requiredNode(root, "inputItems");
                if (!replay.isArray()) {
                    throw new IllegalArgumentException("Provider state replay inputs must be an array");
                }
                replay.forEach(item -> inputs.add(item.deepCopy()));
            }
            return new DecodedState(kind, responseId, instructions, items, inputTokens, inputs);
        } catch (IOException failure) {
            throw new IllegalArgumentException("Provider state is not valid JSON", failure);
        }
    }

    List<ResponseInputItem> inputItems(DecodedState state) {
        List<ResponseInputItem> result = new ArrayList<>();
        for (JsonNode raw : state.inputItems()) {
            try {
                result.add(mapper.treeToValue(raw, ResponseInputItem.class));
            } catch (IOException failure) {
                throw new IllegalArgumentException("Provider replay input cannot be decoded", failure);
            }
        }
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
            String kind,
            String responseId,
            ModelInstructions instructions,
            List<ResponseOutputItem> output,
            long inputTokens) {
        ObjectNode root = mapper.createObjectNode();
        root.put("formatVersion", 3);
        root.putArray("inputItems");
        root.put("kind", kind);
        root.put("responseId", responseId);
        Objects.requireNonNull(instructions, "instructions");
        ObjectNode layers = root.putObject("instructions");
        layers.put("systemInstruction", instructions.systemInstruction());
        layers.put("developerInstructions", instructions.developerInstructions());
        layers.put("responseContract", instructions.responseContract());
        root.put("inputTokens", inputTokens);
        ArrayNode items = root.putArray("outputItems");
        output.forEach(item -> items.add(mapper.valueToTree(item)));
        try {
            return new ProviderState(PROVIDER_ID, FORMAT, new CanonicalPayload(mapper.writeValueAsString(root)));
        } catch (IOException failure) {
            throw new IllegalStateException("Provider output items cannot be encoded", failure);
        }
    }

    ProviderState withInputs(ProviderState state, List<ResponseInputItem> inputs) {
        try {
            ObjectNode root = (ObjectNode) mapper.readTree(state.payload().json());
            root.put("formatVersion", 3);
            ArrayNode stored = root.putArray("inputItems");
            for (ResponseInputItem input : inputs) {
                if (input.isEasyInputMessage()
                        && input.asEasyInputMessage().role()
                                == com.openai.models.responses.EasyInputMessage.Role.DEVELOPER) {
                    continue;
                }
                stored.add(mapper.valueToTree(input));
            }
            return new ProviderState(PROVIDER_ID, FORMAT, new CanonicalPayload(mapper.writeValueAsString(root)));
        } catch (IOException failure) {
            throw new IllegalStateException("Provider replay input cannot be persisted", failure);
        }
    }

    private ModelInstructions decodeInstructions(JsonNode layers) {
        return new ModelInstructions(
                instructionText(layers, "systemInstruction"),
                instructionText(layers, "developerInstructions"),
                instructionText(layers, "responseContract"));
    }

    private String instructionText(JsonNode layers, String field) {
        String value = requiredNode(layers, field).textValue();
        if (value == null) {
            throw new IllegalArgumentException("Provider instruction layer must be text: " + field);
        }
        return value;
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
            String kind,
            String responseId,
            ModelInstructions instructions,
            List<JsonNode> outputItems,
            long inputTokens,
            List<JsonNode> inputItems) {
        DecodedState(
                String kind,
                String responseId,
                ModelInstructions instructions,
                List<JsonNode> outputItems,
                long inputTokens) {
            this(kind, responseId, instructions, outputItems, inputTokens, List.of());
        }

        DecodedState {
            outputItems = List.copyOf(outputItems);
            inputItems = List.copyOf(inputItems);
        }
    }
}
