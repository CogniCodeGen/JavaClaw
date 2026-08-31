package com.javaclaw.sdk;

import java.util.ArrayList;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.javaclaw.sdk.model.JsonDocument;
import com.javaclaw.sdk.model.SkillContentInfo;
import com.javaclaw.sdk.model.SkillInfo;
import com.javaclaw.sdk.model.SkillResourceInfo;

/** Skill 声明的客户端映射边界；UI 不需要解析 JSON，也不能将资源当作本机执行入口。 */
final class SkillDocuments {
    private static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private SkillDocuments() {}

    static SkillContentInfo read(SkillInfo skill) {
        var value = parse(skill.manifest());
        var resources = new ArrayList<SkillResourceInfo>();
        for (var resource : value.path("resources")) {
            resources.add(new SkillResourceInfo(
                    resource.path("path").asText(),
                    resource.path("mediaType").asText("text/plain"),
                    resource.path("content").asText(),
                    resource.path("executable").asBoolean()));
        }
        return new SkillContentInfo(
                skill,
                value.path("instructions").asText(value.path("description").asText()),
                resources);
    }

    static JsonDocument write(SkillContentInfo value) {
        var result = parse(value.skill().manifest());
        result.put("instructions", value.instructions());
        var resources = result.putArray("resources");
        for (var resource : value.resources()) {
            resources
                    .addObject()
                    .put("path", resource.path())
                    .put("mediaType", resource.mediaType())
                    .put("content", resource.content())
                    .put("executable", resource.executable());
        }
        return new JsonDocument(result.toString());
    }

    private static ObjectNode parse(JsonDocument document) {
        try {
            if (document.canonicalJson().length() > 2_000_000) {
                throw new IllegalArgumentException("Skill 声明超过大小上限");
            }
            var value = JSON.readTree(document.canonicalJson());
            if (!value.isObject()) {
                throw new IllegalArgumentException("Skill 声明必须是对象");
            }
            return (ObjectNode) value;
        } catch (java.io.IOException failure) {
            throw new IllegalArgumentException("Skill 声明不是有效 JSON", failure);
        }
    }
}
