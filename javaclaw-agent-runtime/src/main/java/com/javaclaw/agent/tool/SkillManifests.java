package com.javaclaw.agent.tool;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.javaclaw.agent.knowledge.SkillResource;

/** Skill 声明的 JSON 边界；解析后的资源仅作为数据，不能加载第三方类或直接运行脚本。 */
public final class SkillManifests {
    private static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private SkillManifests() {}

    /** 完整读取指令；旧声明没有 instructions 时保留原文，不擅自改写用户内容。 */
    public static String instructions(String manifest) {
        JsonNode root = parse(manifest);
        JsonNode instructions = root.path("instructions");
        if (instructions.isMissingNode()) {
            return manifest;
        }
        if (!instructions.isTextual() || instructions.asText().isBlank()) {
            throw new IllegalArgumentException("Skill instructions must be non-empty text");
        }
        return instructions.asText();
    }

    /** 读取有限资源清单；重复或可穿越资源名会拒绝整个 Bundle。 */
    public static List<SkillResource> resources(String manifest) {
        JsonNode root = parse(manifest);
        JsonNode values = root.path("resources");
        if (values.isMissingNode()) {
            return List.of();
        }
        if (!values.isArray() || values.size() > 128) {
            throw new IllegalArgumentException("Skill Bundle can contain at most 128 resources");
        }
        var result = new ArrayList<SkillResource>();
        var paths = new HashSet<String>();
        for (JsonNode resource : values) {
            if (!resource.isObject()
                    || !resource.path("path").isTextual()
                    || !resource.path("content").isTextual()) {
                throw new IllegalArgumentException("invalid Skill resource");
            }
            var entry = new SkillResource(
                    resource.path("path").asText(),
                    resource.path("mediaType").asText("text/plain"),
                    resource.path("content").asText(),
                    resource.path("executable").asBoolean(false));
            if (!paths.add(entry.path())) {
                throw new IllegalArgumentException("duplicate Skill resource path");
            }
            result.add(entry);
        }
        return List.copyOf(result);
    }

    /** 验证整体大小、指令和资源；不因格式正确而提升信任或权限。 */
    public static void validate(String manifest) {
        instructions(manifest);
        resources(manifest);
    }

    private static JsonNode parse(String manifest) {
        if (manifest == null || manifest.length() > 2_000_000) {
            throw new IllegalArgumentException("Skill manifest exceeds size limit");
        }
        try {
            JsonNode result = JSON.readTree(manifest);
            if (result == null || !result.isObject()) {
                throw new IllegalArgumentException("Skill manifest must be an object");
            }
            return result;
        } catch (java.io.IOException invalid) {
            throw new IllegalArgumentException("invalid Skill manifest JSON", invalid);
        }
    }
}
