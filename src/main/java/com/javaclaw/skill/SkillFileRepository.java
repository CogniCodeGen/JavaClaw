package com.javaclaw.skill;

import com.javaclaw.util.PathGuard;
import com.javaclaw.util.SensitiveDataRedactor;
import io.agentscope.core.skill.util.MarkdownSkillParser;
import io.agentscope.core.skill.util.SkillFileSystemHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Workspace-scoped persistence boundary for skill documents and their version history.
 *
 * <p>All model-provided relative paths are confined to one normalized skill directory. A skill
 * document is generated and parsed in memory, written through a sibling temporary file, moved
 * atomically when supported, then read and parsed again before the write is considered complete.</p>
 */
final class SkillFileRepository {

    private static final Logger log = LoggerFactory.getLogger(SkillFileRepository.class);

    private final Path root;

    SkillFileRepository(Path root) {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.root);
            log.info("技能目录已初始化: {}", this.root);
        } catch (IOException failure) {
            log.error("创建技能目录失败", failure);
        }
    }

    Path root() {
        return root;
    }

    List<Skill> loadAll() {
        List<Skill> loaded = new ArrayList<>();
        try (DirectoryStream<Path> directories = Files.newDirectoryStream(root, Files::isDirectory)) {
            for (Path directory : directories) {
                if (directory.getFileName().toString().startsWith(".")
                        || !PathGuard.isInside(root, directory)) {
                    continue;
                }
                Path document = directory.resolve(Skill.SKILL_FILE);
                if (!Files.exists(document) || !PathGuard.isInside(directory, document)) {
                    continue;
                }
                try {
                    loaded.add(parse(directory));
                } catch (IOException failure) {
                    log.warn("加载技能失败: {}", directory.getFileName(), failure);
                }
            }
            log.info("已加载 {} 个技能", loaded.size());
        } catch (IOException failure) {
            log.error("读取技能目录失败", failure);
        }
        return loaded;
    }

    Skill parse(Path directory) throws IOException {
        String raw = Files.readString(directory.resolve(Skill.SKILL_FILE), StandardCharsets.UTF_8);
        MarkdownSkillParser.ParsedMarkdown parsed = MarkdownSkillParser.parse(raw);
        Map<String, Object> metadata = parsed.getMetadata();

        Skill skill = new Skill();
        skill.setId(directory.getFileName().toString());
        skill.setDirectory(directory);
        skill.setName(stringValue(metadata, "name", skill.getId()));
        skill.setDescription(stringValue(metadata, "description", ""));
        skill.setEnabled(Boolean.parseBoolean(stringValue(metadata, "enabled", "true")));
        skill.setContent(parsed.getContent());
        skill.setVersion(stringValue(metadata, "version", "1.0.0"));
        skill.setCategory(stringValue(metadata, "category", ""));
        skill.setTags(listValue(metadata, "tags"));
        skill.setSource(SkillSource.fromKey(stringValue(metadata, "source", "user")));
        skill.setUserModified(Boolean.parseBoolean(
                stringValue(metadata, "user-modified", "false")));
        skill.setPlatforms(listValue(metadata, "platforms"));
        skill.setRequiresToolGroups(listValue(metadata, "requires_toolsets"));
        skill.setFallbackForToolGroups(listValue(metadata, "fallback_for_toolsets"));
        return skill;
    }

    Skill saveAndReadBack(Skill skill, String expectedName) {
        save(skill);
        return readBack(root.resolve(skill.getId()), expectedName);
    }

    void save(Skill skill) {
        requireCredentialFree(skill == null ? null : skill.getDescription());
        requireCredentialFree(skill == null ? null : skill.getContent());
        if (skill == null || skill.getId() == null || skill.getId().isBlank()) {
            throw new IllegalArgumentException("技能及其 ID 不能为空");
        }
        Path directory = root.resolve(skill.getId());
        Path temporary = null;
        try {
            Files.createDirectories(directory);
            String content = generateDocument(skill);
            MarkdownSkillParser.parse(content);

            Path document = directory.resolve(Skill.SKILL_FILE);
            temporary = Files.createTempFile(directory, ".SKILL-", ".tmp");
            Files.writeString(temporary, content, StandardCharsets.UTF_8);
            moveIntoPlace(temporary, document);
            temporary = null;
            if (!content.equals(Files.readString(document, StandardCharsets.UTF_8))) {
                throw new IOException("SKILL.md 回读内容与写入内容不一致");
            }
            parse(directory);
        } catch (IOException | RuntimeException failure) {
            log.error("保存技能失败: {}", skill.getId(), failure);
            throw new IllegalStateException("技能未能确认落盘: " + skill.getId(), failure);
        } finally {
            deleteTemporaryFile(temporary);
        }
    }

    String writeSupportFile(Skill skill, String relativePath, String content) {
        Path target = resolveSupportFile(skill, relativePath);
        if (target == null) {
            return "rel_path 非法：必须位于技能目录内，且不得指向 SKILL.md";
        }
        if (SensitiveDataRedactor.containsLikelyCredential(content)) {
            return SensitiveDataRedactor.credentialStorageDeniedReason();
        }
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, content != null ? content : "", StandardCharsets.UTF_8);
            log.info("已写入技能支持文件: {}/{}", skill.getId(), relativePath);
            return null;
        } catch (IOException failure) {
            log.warn("写入技能支持文件失败: {}/{}", skill.getId(), relativePath, failure);
            return "写入失败：" + failure.getMessage();
        }
    }

    String removeSupportFile(Skill skill, String relativePath) {
        Path target = resolveSupportFile(skill, relativePath);
        if (target == null || !Files.isRegularFile(target)) {
            return "文件不存在或路径非法：" + relativePath;
        }
        try {
            Files.delete(target);
            log.info("已删除技能支持文件: {}/{}", skill.getId(), relativePath);
            return null;
        } catch (IOException failure) {
            return "删除失败：" + failure.getMessage();
        }
    }

    void delete(String id) {
        try {
            SkillFileSystemHelper.deleteDirectory(root.resolve(id));
            log.info("已删除技能: {}", id);
        } catch (IOException failure) {
            log.error("删除技能目录失败: {}", id, failure);
        }
    }

    void archive(Skill skill) {
        if (skill == null || skill.getDirectory() == null) {
            return;
        }
        Path document = skill.getDirectory().resolve(Skill.SKILL_FILE);
        if (!Files.exists(document)) {
            return;
        }
        try {
            Path history = skill.getDirectory().resolve(Skill.HISTORY_DIR);
            Files.createDirectories(history);
            Files.copy(document, history.resolve("v" + skill.getVersion() + ".md"),
                    StandardCopyOption.REPLACE_EXISTING);
            log.info("已归档技能版本快照: {} v{}", skill.getId(), skill.getVersion());
        } catch (IOException failure) {
            log.warn("归档技能版本失败: {} v{}", skill.getId(), skill.getVersion(), failure);
        }
    }

    List<String> listHistory(Skill skill) {
        if (skill == null || skill.getDirectory() == null) {
            return List.of();
        }
        Path history = skill.getDirectory().resolve(Skill.HISTORY_DIR);
        if (!Files.isDirectory(history)) {
            return List.of();
        }
        List<String> versions = new ArrayList<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(history, "v*.md")) {
            for (Path file : files) {
                String fileName = file.getFileName().toString();
                versions.add(fileName.substring(1, fileName.length() - 3));
            }
        } catch (IOException failure) {
            log.warn("读取技能历史版本失败: {}", skill.getId(), failure);
        }
        versions.sort(Comparator.comparing(SkillFileRepository::versionSortKey).reversed());
        return versions;
    }

    String readHistory(Skill skill, String version) {
        if (skill == null || skill.getDirectory() == null) {
            return null;
        }
        Path snapshot = skill.getDirectory().resolve(Skill.HISTORY_DIR)
                .resolve("v" + version + ".md");
        if (!Files.exists(snapshot)) {
            return null;
        }
        try {
            return Files.readString(snapshot, StandardCharsets.UTF_8);
        } catch (IOException failure) {
            log.warn("读取技能历史版本失败: {} v{}", skill.getId(), version, failure);
            return null;
        }
    }

    Skill rollback(Skill skill, String version) {
        String snapshot = readHistory(skill, version);
        if (skill == null || snapshot == null) {
            return null;
        }
        try {
            archive(skill);
            Files.writeString(skill.getDirectory().resolve(Skill.SKILL_FILE), snapshot,
                    StandardCharsets.UTF_8);
            Skill restored = parse(skill.getDirectory());
            restored.setVersion(SkillManager.bumpVersion(version, SkillManager.BumpLevel.PATCH));
            return saveAndReadBack(restored, restored.getName());
        } catch (IOException failure) {
            log.error("回滚技能失败: {} v{}", skill.getId(), version, failure);
            return null;
        }
    }

    static String sanitizeDirectoryName(String name) {
        if (name == null || name.isBlank()) {
            return "";
        }
        return name.trim()
                .replaceAll("\\s+", "-")
                .replaceAll("[^\\p{L}\\p{N}\\-_]", "")
                .toLowerCase();
    }

    static boolean isTextFile(Path file) {
        String name = file.getFileName().toString().toLowerCase();
        return name.endsWith(".md") || name.endsWith(".txt") || name.endsWith(".yaml")
                || name.endsWith(".yml") || name.endsWith(".json") || name.endsWith(".xml")
                || name.endsWith(".html") || name.endsWith(".csv");
    }

    static void requireCredentialFree(String content) {
        if (SensitiveDataRedactor.containsLikelyCredential(content)) {
            throw new IllegalArgumentException(SensitiveDataRedactor.credentialStorageDeniedReason());
        }
    }

    private Skill readBack(Path directory, String expectedName) {
        try {
            Skill persisted = parse(directory);
            if (!Objects.equals(expectedName, persisted.getName())) {
                throw new IOException("技能名称回读不一致");
            }
            return persisted;
        } catch (IOException | RuntimeException failure) {
            throw new IllegalStateException("技能落盘后回读校验失败", failure);
        }
    }

    private static String generateDocument(Skill skill) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("name", skill.getName());
        metadata.put("description", Objects.requireNonNullElse(skill.getDescription(), ""));
        metadata.put("enabled", String.valueOf(skill.isEnabled()));
        metadata.put("version", skill.getVersion());
        metadata.put("source", skill.getSource().getKey());
        metadata.put("user-modified", String.valueOf(skill.isUserModified()));
        putIfNotBlank(metadata, "category", skill.getCategory());
        putIfNotEmpty(metadata, "tags", skill.getTags());
        putIfNotEmpty(metadata, "platforms", skill.getPlatforms());
        putIfNotEmpty(metadata, "requires_toolsets", skill.getRequiresToolGroups());
        putIfNotEmpty(metadata, "fallback_for_toolsets", skill.getFallbackForToolGroups());
        return MarkdownSkillParser.generate(metadata, Objects.requireNonNullElse(skill.getContent(), ""));
    }

    private static void putIfNotBlank(Map<String, Object> metadata, String key, String value) {
        if (value != null && !value.isBlank()) {
            metadata.put(key, value);
        }
    }

    private static void putIfNotEmpty(Map<String, Object> metadata, String key, List<String> value) {
        if (value != null && !value.isEmpty()) {
            metadata.put(key, value);
        }
    }

    private static void moveIntoPlace(Path temporary, Path document) throws IOException {
        try {
            Files.move(temporary, document,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(temporary, document, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void deleteTemporaryFile(Path temporary) {
        if (temporary == null) {
            return;
        }
        try {
            Files.deleteIfExists(temporary);
        } catch (IOException cleanupFailure) {
            log.warn("清理技能临时文件失败: {}", temporary.getFileName());
        }
    }

    private static Path resolveSupportFile(Skill skill, String relativePath) {
        if (skill == null || skill.getDirectory() == null
                || relativePath == null || relativePath.isBlank()) {
            return null;
        }
        Path base = skill.getDirectory().toAbsolutePath().normalize();
        Path target = base.resolve(relativePath.strip()).normalize();
        if (!target.startsWith(base) || target.equals(base)) {
            return null;
        }
        if (target.getFileName().toString().equals(Skill.SKILL_FILE)
                && base.equals(target.getParent())) {
            return null;
        }
        return PathGuard.isInside(base, target) ? target : null;
    }

    private static String versionSortKey(String version) {
        StringBuilder key = new StringBuilder();
        for (String part : version.split("\\.")) {
            key.append(String.format("%06d", parseInteger(part))).append('.');
        }
        return key.toString();
    }

    private static int parseInteger(String value) {
        try {
            return Integer.parseInt(value.strip());
        } catch (NumberFormatException invalid) {
            return 0;
        }
    }

    private static String stringValue(
            Map<String, Object> metadata, String key, String defaultValue) {
        Object value = metadata.get(key);
        return value == null ? defaultValue : value.toString();
    }

    private static List<String> listValue(Map<String, Object> metadata, String key) {
        Object value = metadata.get(key);
        List<String> result = new ArrayList<>();
        if (value == null) {
            return result;
        }
        if (value instanceof Collection<?> values) {
            for (Object item : values) {
                if (item != null && !item.toString().isBlank()) {
                    result.add(item.toString().strip());
                }
            }
        } else {
            for (String item : value.toString().split(",")) {
                if (!item.isBlank()) {
                    result.add(item.strip());
                }
            }
        }
        return result;
    }
}
