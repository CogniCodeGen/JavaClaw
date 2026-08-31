package com.javaclaw.sdk;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.javaclaw.sdk.model.JsonDocument;
import com.javaclaw.sdk.model.SkillContentInfo;
import com.javaclaw.sdk.model.SkillInfo;
import com.javaclaw.sdk.model.SkillResourceInfo;

/** 显式导入导出的有界 Skill ZIP；只在内存解析，不将归档条目解压到宿主文件系统。 */
final class SkillBundleCodec {
    private static final int LIMIT = 4 * 1024 * 1024;
    private static final String METADATA = "javaclaw-skill.json";
    private static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private SkillBundleCodec() {}

    static SkillContentInfo read(Path path) throws java.io.IOException {
        if (Files.size(path) > LIMIT) {
            throw new IllegalArgumentException("Skill 导入文件最多 4 MiB");
        }
        byte[] bytes;
        try (var input = Files.newInputStream(path)) {
            bytes = input.readNBytes(LIMIT + 1);
        }
        if (bytes.length > LIMIT) {
            throw new IllegalArgumentException("Skill 导入文件已变大");
        }
        String id = "skill_" + UUID.randomUUID();
        String name = path.getFileName().toString().replaceFirst("(?i)\\.(zip|md)$", "");
        if (path.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".md")) {
            return new SkillContentInfo(
                    new SkillInfo(id, name, "1.0", JsonDocument.EMPTY_OBJECT, false, 0, null, null),
                    utf8(bytes),
                    List.of());
        }
        Map<String, byte[]> files = new LinkedHashMap<>();
        int size = 0;
        int entries = 0;
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8)) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
                if (++entries > 256) {
                    throw new IllegalArgumentException("Skill 归档条目数量超限");
                }
                String entryName = entry.getName();
                validPath(entryName.endsWith("/") ? entryName.substring(0, entryName.length() - 1) : entryName);
                if (entry.isDirectory()) {
                    continue;
                }
                if (files.size() >= 130) {
                    throw new IllegalArgumentException("Skill 归档最多包含 130 个文件");
                }
                byte[] content = zip.readNBytes(LIMIT - size + 1);
                size += content.length;
                if (size > LIMIT || files.putIfAbsent(entryName, content) != null) {
                    throw new IllegalArgumentException("Skill 归档超限或含重复路径");
                }
            }
        }
        // ZIP 不落盘，因此符号链接没有文件系统语义；任何脚本都仍需用户显式启用和服务端沙箱。
        if (!files.containsKey("SKILL.md")) {
            throw new IllegalArgumentException("Skill ZIP 必须包含根目录 SKILL.md");
        }
        JsonDocument manifest = JsonDocument.EMPTY_OBJECT;
        String version = "1.0";
        if (files.containsKey(METADATA)) {
            var metadata = JSON.readTree(utf8(files.get(METADATA)));
            if (!"javaclaw.skill.bundle/4".equals(metadata.path("format").asText())
                    || !metadata.path("manifest").isObject()) {
                throw new IllegalArgumentException("Skill Bundle 格式不受支持");
            }
            name = metadata.path("name").asText(name);
            version = metadata.path("version").asText(version);
            manifest = new JsonDocument(metadata.path("manifest").toString());
        }
        var draft = new SkillInfo(id, name, version, manifest, false, 0, null, null);
        var declared = SkillDocuments.read(draft).resources().stream()
                .collect(java.util.stream.Collectors.toMap(SkillResourceInfo::path, value -> value));
        var resources = new ArrayList<SkillResourceInfo>();
        for (var entry : files.entrySet()) {
            if (entry.getKey().equals("SKILL.md") || entry.getKey().equals(METADATA)) {
                continue;
            }
            var previous = declared.get(entry.getKey());
            resources.add(new SkillResourceInfo(
                    entry.getKey(),
                    previous == null ? "text/plain" : previous.mediaType(),
                    utf8(entry.getValue()),
                    previous != null && previous.executable()));
        }
        if (!files.keySet().containsAll(declared.keySet())) {
            throw new IllegalArgumentException("Skill 声明引用缺失的资源");
        }
        return new SkillContentInfo(draft, utf8(files.get("SKILL.md")), resources);
    }

    static void write(SkillContentInfo value, Path target, boolean replace) throws java.io.IOException {
        var bytes = new ByteArrayOutputStream();
        var metadata = JSON.createObjectNode()
                .put("format", "javaclaw.skill.bundle/4")
                .put("name", value.skill().name())
                .put("version", value.skill().version());
        var manifest = JSON.readTree(value.skill().manifest().canonicalJson()).deepCopy();
        // 资源正文单独保存，声明仅记录类型与可执行标记；不会夹带 H2 内部标识或凭据。
        var resourceMetadata = ((com.fasterxml.jackson.databind.node.ObjectNode) manifest).putArray("resources");
        ((com.fasterxml.jackson.databind.node.ObjectNode) manifest).remove("instructions");
        int total = value.instructions().getBytes(StandardCharsets.UTF_8).length;
        var seen = new java.util.HashSet<String>(List.of("SKILL.md", METADATA));
        if (value.resources().size() > 128) {
            throw new IllegalArgumentException("Skill 资源数量超限");
        }
        try (var zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            add(zip, "SKILL.md", value.instructions());
            for (var resource : value.resources()) {
                validPath(resource.path());
                if (!seen.add(resource.path())) {
                    throw new IllegalArgumentException("Skill 资源路径重复或保留");
                }
                total += resource.content().getBytes(StandardCharsets.UTF_8).length;
                if (total > LIMIT) {
                    throw new IllegalArgumentException("Skill 导出内容超过 4 MiB");
                }
                resourceMetadata
                        .addObject()
                        .put("path", resource.path())
                        .put("mediaType", resource.mediaType())
                        .put("executable", resource.executable());
                add(zip, resource.path(), resource.content());
            }
            metadata.set("manifest", manifest);
            if (total + metadata.toString().getBytes(StandardCharsets.UTF_8).length > LIMIT) {
                throw new IllegalArgumentException("Skill 导出声明和内容超过 4 MiB");
            }
            add(zip, METADATA, metadata.toString());
        }
        Path destination = target.toAbsolutePath().normalize();
        Path temporary = Files.createTempFile(destination.getParent(), ".javaclaw-skill-", ".zip");
        try {
            Files.write(temporary, bytes.toByteArray());
            if (replace) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.move(temporary, destination);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void add(ZipOutputStream zip, String name, String text) throws java.io.IOException {
        var entry = new ZipEntry(name);
        entry.setTime(0);
        zip.putNextEntry(entry);
        zip.write(text.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static String utf8(byte[] bytes) throws java.io.IOException {
        return StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
    }

    private static void validPath(String name) {
        if (name.isBlank()
                || name.startsWith("/")
                || name.contains("\\")
                || name.contains(":")
                || name.indexOf('\0') >= 0
                || java.util.Arrays.stream(name.split("/", -1))
                        .anyMatch(part -> part.isEmpty() || part.equals(".") || part.equals(".."))) {
            throw new IllegalArgumentException("Skill 归档包含非法相对路径");
        }
    }
}
