package com.javaclaw.protocol;

import java.util.Objects;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/** Newline framing belongs to transports; this codec handles exactly one JSON-RPC frame. */
public final class JsonRpcCodec {
    public static final int MAX_NESTING_DEPTH = 64;
    public static final int MAX_STRING_LENGTH = 8 * 1024 * 1024;
    public static final int MAX_NUMBER_LENGTH = 128;

    private final ObjectMapper json;

    /** 创建带嵌套深度、字符串和数字长度限制的 JSON-RPC 编解码器。 */
    public JsonRpcCodec() {
        this(defaultMapper());
    }

    /** 使用非空 ObjectMapper；调用方负责配置解析预算，传输层负责单帧长度和换行边界。 */
    public JsonRpcCodec(ObjectMapper json) {
        this.json = Objects.requireNonNull(json, "json");
    }

    /**
     * 解析恰好一个 JSON-RPC 2.0 对象，区分请求、通知与响应；拒绝非法 id 和 result/error 冲突。
     *
     * @throws com.fasterxml.jackson.core.JsonProcessingException JSON 无法解析
     * @throws IllegalArgumentException JSON-RPC 结构不合法
     */
    public JsonRpcFrame decode(String line) throws JsonProcessingException {
        JsonNode root = json.readTree(line);
        if (root == null
                || !root.isObject()
                || !root.path("jsonrpc").isTextual()
                || !"2.0".equals(root.path("jsonrpc").textValue())) {
            throw new IllegalArgumentException("invalid JSON-RPC 2.0 frame");
        }
        JsonNode id = root.get("id");
        if (root.has("method")) {
            JsonNode methodNode = root.get("method");
            if (!methodNode.isTextual()) {
                throw new IllegalArgumentException("method must be a string");
            }
            String method = methodNode.textValue();
            JsonNode params = root.has("params") ? root.get("params") : NullNode.getInstance();
            return id == null
                    ? new JsonRpcNotification("2.0", method, params)
                    : new JsonRpcRequest("2.0", id, method, params);
        }
        if (id == null) {
            throw new IllegalArgumentException("response id is required");
        }
        boolean hasResult = root.has("result");
        boolean hasError = root.has("error");
        if (hasResult == hasError) {
            throw new IllegalArgumentException("response requires exactly one of result or error");
        }
        if (hasError) {
            JsonNode error = root.get("error");
            if (!error.isObject()
                    || !error.path("code").isIntegralNumber()
                    || !error.path("code").canConvertToInt()
                    || !error.path("message").isTextual()) {
                throw new IllegalArgumentException("invalid JSON-RPC error object");
            }
            return JsonRpcResponse.failure(
                    id, error.path("code").intValue(), error.path("message").textValue(), error.get("data"));
        }
        return JsonRpcResponse.success(id, root.get("result"));
    }

    /**
     * 序列化单个 JSON-RPC 帧，不附加换行；传输层决定 JSONL 分帧。
     *
     * @throws com.fasterxml.jackson.core.JsonProcessingException 帧无法序列化
     */
    public String encode(JsonRpcFrame frame) throws JsonProcessingException {
        return json.writeValueAsString(frame);
    }

    /** 返回编解码器内部 mapper，仅供协议适配使用；并发使用后不得重新配置。 */
    public ObjectMapper mapper() {
        return json;
    }

    private static ObjectMapper defaultMapper() {
        StreamReadConstraints constraints = StreamReadConstraints.builder()
                .maxNestingDepth(MAX_NESTING_DEPTH)
                .maxStringLength(MAX_STRING_LENGTH)
                .maxNumberLength(MAX_NUMBER_LENGTH)
                .build();
        JsonFactory factory =
                JsonFactory.builder().streamReadConstraints(constraints).build();
        return new ObjectMapper(factory).registerModules(new Jdk8Module(), new JavaTimeModule());
    }
}
