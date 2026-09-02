package com.javaclaw.protocol;

import java.io.IOException;
import java.util.Iterator;
import java.util.Optional;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.javaclaw.api.CanonicalPayload;

/** 严格 JSON-RPC 2.0 codec；拒绝未知信封字段和非对象 params/result。 */
public final class JsonRpcCodec {
    private static final Set<String> REQUEST_FIELDS = Set.of("jsonrpc", "id", "method", "params");
    private static final Set<String> RESPONSE_FIELDS = Set.of("jsonrpc", "id", "result", "error");
    private static final Set<String> ERROR_FIELDS = Set.of("code", "message", "data");

    private final CanonicalJson json;
    private final ObjectMapper mapper;

    /** 创建使用共享规范 JSON codec 的实例。 */
    public JsonRpcCodec() {
        this(new CanonicalJson());
    }

    /**
     * 创建 codec。
     *
     * @param json 共享 JSON codec
     */
    public JsonRpcCodec(CanonicalJson json) {
        this.json = java.util.Objects.requireNonNull(json, "json");
        mapper = json.mapper();
    }

    /**
     * 解码一个完整 JSON-RPC message。
     *
     * @param wire UTF-8 JSON 文本
     * @return request、notification 或 response
     */
    public JsonRpcMessage decode(String wire) {
        ObjectNode root = readRoot(wire);
        requireVersion(root);
        if (root.has("method")) {
            return decodeCall(root);
        }
        return decodeResponse(root);
    }

    /**
     * 编码一个 JSON-RPC message。
     *
     * @param message 消息
     * @return 紧凑 JSON
     */
    public String encode(JsonRpcMessage message) {
        ObjectNode root = mapper.createObjectNode();
        root.put("jsonrpc", ProtocolVersion.JSON_RPC);
        switch (message) {
            case JsonRpcRequest request -> encodeRequest(root, request);
            case JsonRpcNotification notification -> encodeNotification(root, notification);
            case JsonRpcResponse response -> encodeResponse(root, response);
        }
        try {
            return mapper.writeValueAsString(root);
        } catch (IOException failure) {
            throw new IllegalStateException("cannot encode JSON-RPC message", failure);
        }
    }

    private ObjectNode readRoot(String wire) {
        try {
            JsonNode root = mapper.readTree(wire);
            if (root instanceof ObjectNode object) {
                return object;
            }
            throw invalid("JSON-RPC message must be an object");
        } catch (IOException failure) {
            throw new ProtocolException(ProtocolErrorCode.PARSE_ERROR, "invalid JSON-RPC JSON");
        }
    }

    private static void requireVersion(ObjectNode root) {
        JsonNode version = root.get("jsonrpc");
        if (version == null || !version.isTextual() || !ProtocolVersion.JSON_RPC.equals(version.textValue())) {
            throw new ProtocolException(ProtocolErrorCode.UNSUPPORTED_PROTOCOL, "only JSON-RPC 2.0 is supported");
        }
    }

    private JsonRpcMessage decodeCall(ObjectNode root) {
        rejectUnknown(root, REQUEST_FIELDS);
        String method = requiredText(root, "method");
        CanonicalPayload params = objectPayload(root, "params", true);
        JsonNode id = root.get("id");
        if (id == null) {
            return new JsonRpcNotification(method, params);
        }
        return new JsonRpcRequest(rpcId(id), method, params);
    }

    private JsonRpcResponse decodeResponse(ObjectNode root) {
        rejectUnknown(root, RESPONSE_FIELDS);
        RpcId id = rpcId(root.get("id"));
        boolean hasResult = root.has("result");
        boolean hasError = root.has("error");
        if (hasResult == hasError) {
            throw invalid("response requires exactly one of result and error");
        }
        if (hasResult) {
            return JsonRpcResponse.success(id, objectPayload(root, "result", false));
        }
        return JsonRpcResponse.failure(id, decodeError(root.get("error")));
    }

    private JsonRpcError decodeError(JsonNode node) {
        if (!(node instanceof ObjectNode error)) {
            throw invalid("error must be an object");
        }
        rejectUnknown(error, ERROR_FIELDS);
        JsonNode code = error.get("code");
        if (code == null || !code.canConvertToInt()) {
            throw invalid("error.code must be an integer");
        }
        String message = requiredText(error, "message");
        Optional<CanonicalPayload> data =
                error.has("data") ? Optional.of(objectPayload(error, "data", false)) : Optional.empty();
        return new JsonRpcError(code.intValue(), message, data);
    }

    private void encodeRequest(ObjectNode root, JsonRpcRequest request) {
        root.put("id", request.id().value());
        root.put("method", request.method());
        root.set("params", json.tree(request.params()));
    }

    private void encodeNotification(ObjectNode root, JsonRpcNotification notification) {
        root.put("method", notification.method());
        root.set("params", json.tree(notification.params()));
    }

    private void encodeResponse(ObjectNode root, JsonRpcResponse response) {
        root.put("id", response.id().value());
        response.result().ifPresent(result -> root.set("result", json.tree(result)));
        response.error().ifPresent(error -> root.set("error", encodeError(error)));
    }

    private ObjectNode encodeError(JsonRpcError error) {
        ObjectNode node = mapper.createObjectNode();
        node.put("code", error.code());
        node.put("message", error.message());
        error.data().ifPresent(data -> node.set("data", json.tree(data)));
        return node;
    }

    private CanonicalPayload objectPayload(ObjectNode parent, String field, boolean defaultEmpty) {
        JsonNode value = parent.get(field);
        if (value == null && defaultEmpty) {
            return new CanonicalPayload("{}");
        }
        if (!(value instanceof ObjectNode object)) {
            throw invalid(field + " must be an object");
        }
        return json.payload(object);
    }

    private static RpcId rpcId(JsonNode value) {
        if (value == null || !value.isTextual()) {
            throw invalid("id must be a string");
        }
        return new RpcId(value.textValue());
    }

    private static String requiredText(ObjectNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw invalid(field + " must be a non-blank string");
        }
        return value.textValue();
    }

    private static void rejectUnknown(ObjectNode root, Set<String> allowed) {
        Iterator<String> names = root.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (!allowed.contains(name)) {
                throw invalid("unknown JSON-RPC field: " + name);
            }
        }
    }

    private static ProtocolException invalid(String message) {
        return new ProtocolException(ProtocolErrorCode.INVALID_REQUEST, message);
    }
}
