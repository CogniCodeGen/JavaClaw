package com.javaclaw.sdk;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import com.javaclaw.sdk.model.ArtifactItemContent;
import com.javaclaw.sdk.model.AutomationDefinitionInfo;
import com.javaclaw.sdk.model.AutomationInfo;
import com.javaclaw.sdk.model.CheckpointItemContent;
import com.javaclaw.sdk.model.ItemInfo;
import com.javaclaw.sdk.model.OpenSpecDraftInfo;

/** OpenSpec 的显式文本交换；ZIP 只在内存中读取，不解压、执行脚本或读取旧数据根。 */
final class OpenSpecBundleCodec {
    private static final int ZIP_LIMIT = 1024 * 1024;
    private static final int IMPORT_LIMIT = 65_536;
    private static final String PATH = "(proposal|design|tasks)\\.md|specs/[A-Za-z0-9_-]{1,80}/spec\\.md";

    private OpenSpecBundleCodec() {}

    static List<ItemInfo> currentExecutionArtifacts(AutomationInfo definition, List<ItemInfo> items) throws Exception {
        String fingerprint = java.util.HexFormat.of()
                .formatHex(java.security.MessageDigest.getInstance("SHA-256")
                        .digest((definition.kind() + "\n" + definition.prompt() + "\n"
                                        + definition.definition().canonicalJson())
                                .getBytes(StandardCharsets.UTF_8)));
        var latest = items.stream()
                .filter(item -> "COMPLETED".equals(item.state()))
                .map(ItemInfo::content)
                .filter(CheckpointItemContent.class::isInstance)
                .map(CheckpointItemContent.class::cast)
                .reduce((left, right) -> right);
        if (latest.isEmpty() || !fingerprint.equals(latest.get().definitionHash())) {
            return List.of();
        }
        String executionPrefix = latest.get().executionId() + ":";
        // 同一稳定 Thread 包含旧定义和多次执行；只能导出当前定义最近一次执行的产物，不能借旧审批覆盖新规格。
        return items.stream()
                .filter(item -> item.content() instanceof ArtifactItemContent artifact
                        && artifact.artifactId().startsWith(executionPrefix))
                .toList();
    }

    static OpenSpecDraftInfo read(Path path) throws Exception {
        byte[] bytes;
        try (var input = Files.newInputStream(path)) {
            bytes = input.readNBytes(ZIP_LIMIT + 1);
        }
        if (bytes.length > ZIP_LIMIT) {
            throw new IllegalArgumentException("OpenSpec ZIP 最多 1 MiB");
        }
        var documents = new TreeMap<String, String>();
        int count = 0;
        int total = 0;
        String prefix = null;
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8)) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
                if (++count > 128) {
                    throw new IllegalArgumentException("OpenSpec ZIP 条目数量超限");
                }
                String name = entry.getName();
                validPath(name.endsWith("/") ? name.substring(0, name.length() - 1) : name);
                if (entry.isDirectory()) {
                    continue;
                }
                String relative = name;
                String currentPrefix = "";
                if (name.startsWith("openspec/changes/")) {
                    int slash = name.indexOf('/', "openspec/changes/".length());
                    if (slash < 0) {
                        throw new IllegalArgumentException("OpenSpec change 路径不完整");
                    }
                    currentPrefix = name.substring(0, slash + 1);
                    relative = name.substring(slash + 1);
                }
                if (prefix == null) {
                    prefix = currentPrefix;
                }
                if (!prefix.equals(currentPrefix) || !relative.matches(PATH)) {
                    throw new IllegalArgumentException(
                            "只允许一个 change 的 proposal.md、design.md、tasks.md 和 specs/<capability>/spec.md");
                }
                byte[] content = zip.readNBytes(IMPORT_LIMIT - total + 1);
                total += content.length;
                if (total > IMPORT_LIMIT || documents.size() >= 32) {
                    throw new IllegalArgumentException("单次导入最多 32 份文档、合计 64 KiB；请明确拆分变更，不会截断资料");
                }
                String text = StandardCharsets.UTF_8
                        .newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(content))
                        .toString();
                if (text.length() > 32_768 || documents.putIfAbsent(relative, text) != null) {
                    throw new IllegalArgumentException("OpenSpec 单文档超限或重复路径");
                }
            }
        }
        if (documents.isEmpty() || documents.values().stream().allMatch(String::isBlank)) {
            throw new IllegalArgumentException("OpenSpec ZIP 没有可导入的文档");
        }
        return new OpenSpecDraftInfo(
                documents,
                java.util.HexFormat.of()
                        .formatHex(java.security.MessageDigest.getInstance("SHA-256")
                                .digest(bytes)),
                List.of(
                        "仅生成导入预览；确认并保存 SDD 定义后才写入 H2。",
                        "tasks.md 的 [x] 只保留为原文，不视为验收、审批或已执行凭据。",
                        "后续文件修改不会自动同步，原有执行与审批不自动沿用。"));
    }

    static void write(AutomationDefinitionInfo definition, List<ItemInfo> items, Path destination, boolean replace)
            throws Exception {
        var documents = new TreeMap<>(definition.openSpecDocuments());
        if (!definition.specification().isBlank()) {
            documents.putIfAbsent("specs/javaclaw/spec.md", definition.specification());
        }
        // 按持久 Item 顺序选择最新产物；不解析 Markdown 哨兵，也不把未完成 Item 导出为完成证明。
        for (ItemInfo item : items) {
            if (!"COMPLETED".equals(item.state()) || !(item.content() instanceof ArtifactItemContent artifact)) {
                continue;
            }
            String name =
                    switch (artifact.category()) {
                        case "sdd-proposal" -> "proposal.md";
                        case "sdd-specification" -> "specs/javaclaw/spec.md";
                        case "sdd-design" -> "design.md";
                        case "sdd-tasks" -> "tasks.md";
                        default -> null;
                    };
            if (name != null) {
                documents.put(name, artifact.content());
            }
        }
        if (documents.isEmpty()) {
            throw new IllegalStateException("SDD 尚无可导出的规格或产物");
        }
        var bytes = new ByteArrayOutputStream();
        int total = 0;
        try (var zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            for (Map.Entry<String, String> document : documents.entrySet()) {
                if (!document.getKey().matches(PATH)) {
                    throw new IllegalArgumentException("invalid stored OpenSpec path");
                }
                byte[] value = document.getValue().getBytes(StandardCharsets.UTF_8);
                total += value.length;
                if (total > ZIP_LIMIT) {
                    throw new IllegalArgumentException("OpenSpec 导出超过 1 MiB");
                }
                var entry = new ZipEntry(document.getKey());
                entry.setTime(0);
                zip.putNextEntry(entry);
                zip.write(value);
                zip.closeEntry();
            }
        }
        Path target = destination.toAbsolutePath().normalize();
        Path staging = Files.createTempFile(target.getParent(), ".javaclaw-openspec-", ".zip");
        try {
            Files.write(staging, bytes.toByteArray());
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("OpenSpec export cancelled before publication");
            }
            if (replace) {
                Files.move(
                        staging,
                        target,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.createLink(target, staging);
            }
        } finally {
            Files.deleteIfExists(staging);
        }
    }

    private static void validPath(String name) {
        if (name.isBlank()
                || name.startsWith("/")
                || name.contains("\\")
                || name.contains(":")
                || name.chars().anyMatch(Character::isISOControl)
                || java.util.Arrays.stream(name.split("/", -1))
                        .anyMatch(part -> part.isEmpty() || part.equals(".") || part.equals(".."))) {
            throw new IllegalArgumentException("OpenSpec ZIP 包含非法相对路径");
        }
    }
}
