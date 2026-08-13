package com.javaclaw.framework.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Objects;
import java.util.Base64;

/** Extensible multimodal input. The type is namespaced and the body stays schema driven. */
public record InputBlock(String type, JsonNode data) {
    public InputBlock {
        type = Objects.requireNonNull(type, "type").trim();
        data = Objects.requireNonNull(data, "data").deepCopy();
        if (type.isEmpty()) {
            throw new IllegalArgumentException("input block type must not be blank");
        }
    }

    public static InputBlock text(String text) {
        ObjectNode data = JsonNodeFactory.instance.objectNode();
        data.put("text", Objects.requireNonNull(text, "text"));
        return new InputBlock("core.text", data);
    }

    /** Ordered prior conversation message; the current turn continues to use {@link #text}. */
    public static InputBlock message(String role, String text) {
        String checkedRole = Objects.requireNonNull(role, "role").trim().toLowerCase();
        if (!checkedRole.equals("user") && !checkedRole.equals("assistant")) {
            throw new IllegalArgumentException("conversation message role must be user or assistant");
        }
        ObjectNode data = JsonNodeFactory.instance.objectNode();
        data.put("role", checkedRole);
        data.put("text", Objects.requireNonNull(text, "text"));
        return new InputBlock("core.message", data);
    }

    @Override public JsonNode data() { return data.deepCopy(); }

    public static InputBlock file(String name, String uri, String mediaType) {
        ObjectNode data = JsonNodeFactory.instance.objectNode();
        data.put("name", Objects.requireNonNull(name, "name"));
        data.put("uri", Objects.requireNonNull(uri, "uri"));
        data.put("mediaType", Objects.requireNonNull(mediaType, "mediaType"));
        return new InputBlock("core.file", data);
    }

    /** Serializable inline image input used by run-scoped auxiliary vision tasks. */
    public static InputBlock image(String name, byte[] bytes, String mediaType) {
        ObjectNode data = JsonNodeFactory.instance.objectNode();
        data.put("name", Objects.requireNonNull(name, "name"));
        data.put("base64", Base64.getEncoder().encodeToString(
                Objects.requireNonNull(bytes, "bytes")));
        data.put("mediaType", Objects.requireNonNull(mediaType, "mediaType"));
        return new InputBlock("core.image", data);
    }
}
