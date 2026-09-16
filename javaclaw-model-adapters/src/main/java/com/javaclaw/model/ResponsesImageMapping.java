package com.javaclaw.model;

import java.io.IOException;
import java.util.Base64;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.openai.models.responses.ResponseInputItem;

import com.javaclaw.runtime.ModelImage;
import com.javaclaw.runtime.ModelImageResolver;
import com.javaclaw.runtime.ModelMessage;

/** Responses 图片以私有附件引用保存；仅发送给 Provider 的临时副本展开为图片字节。 */
final class ResponsesImageMapping {
    private static final String PREFIX = "javaclaw-image:v1:";
    private final JsonMapper json = ModelJsonMapper.create();
    private final ModelImageTransport transport;

    ResponsesImageMapping(ModelImageResolver resolver) {
        transport = new ModelImageTransport(resolver);
    }

    ResponseInputItem message(ModelMessage message) {
        ObjectNode item = json.createObjectNode();
        ArrayNode content;
        if (message.role() == com.javaclaw.api.MessageRole.TOOL) {
            item.put("type", "function_call_output");
            item.put("call_id", message.toolCallId().orElseThrow());
            content = item.putArray("output");
        } else {
            item.put("type", "message");
            item.put("role", "user");
            content = item.putArray("content");
        }
        content.addObject().put("type", "input_text").put("text", message.text());
        for (ModelImage image : message.images()) {
            content.addObject().put("type", "input_text").put("text", "图片观察身份：" + image.observationId());
            content.addObject().put("type", "input_image").put("detail", "auto").put("image_url", reference(image));
        }
        return convert(item, ResponseInputItem.class);
    }

    <T> T hydrate(T request, Class<T> type) {
        JsonNode copy = json.valueToTree(request);
        if (copy.findValues("image_url").isEmpty()) {
            return request;
        }
        hydrateNode(copy);
        return convert(copy, type);
    }

    private void hydrateNode(JsonNode node) {
        if (node.isObject() && "input_image".equals(node.path("type").asText())) {
            JsonNode url = node.get("image_url");
            if (url == null || !url.isTextual() || !url.textValue().startsWith(PREFIX)) {
                throw new IllegalArgumentException("模型图片必须来自已核验的附件引用");
            }
            ModelImage image = decode(url.textValue());
            ((ObjectNode) node).put("image_url", transport.dataUrl(image));
        } else if (node.isContainerNode()) {
            node.forEach(this::hydrateNode);
        }
    }

    private String reference(ModelImage image) {
        try {
            return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(json.writeValueAsBytes(image));
        } catch (IOException failure) {
            throw new IllegalStateException("图片引用编码失败", failure);
        }
    }

    private ModelImage decode(String reference) {
        try {
            if (reference.length() > 4096) {
                throw new IllegalArgumentException("图片引用超出限制");
            }
            return json.readValue(
                    Base64.getUrlDecoder().decode(reference.substring(PREFIX.length())), ModelImage.class);
        } catch (IOException failure) {
            throw new IllegalArgumentException("图片引用无法读取", failure);
        }
    }

    private <T> T convert(JsonNode node, Class<T> type) {
        try {
            return json.treeToValue(node, type);
        } catch (IOException failure) {
            throw new IllegalArgumentException("Responses 图片映射失败", failure);
        }
    }
}
