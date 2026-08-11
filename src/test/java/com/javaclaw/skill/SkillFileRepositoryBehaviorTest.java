package com.javaclaw.skill;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillFileRepositoryBehaviorTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void scanningParsesDefaultsCollectionsScalarsAndIgnoresUnsafeDirectories() throws Exception {
        Path root = temporaryDirectory.resolve("skills");
        SkillFileRepository repository = new SkillFileRepository(root);
        assertTrue(repository.loadAll().isEmpty());

        Path defaults = Files.createDirectories(root.resolve("defaults"));
        Files.writeString(defaults.resolve(Skill.SKILL_FILE), "仅有正文");
        Path metadata = Files.createDirectories(root.resolve("metadata"));
        Files.writeString(metadata.resolve(Skill.SKILL_FILE), """
                ---
                name: 元数据技能
                description: 完整字段
                enabled: false
                version: 2.1.0
                category: testing
                tags: [one, two, " "]
                source: agent
                user-modified: true
                platforms: macos, linux
                requires_toolsets: [browser, "", network]
                fallback_for_toolsets: legacy
                ---
                元数据正文
                """);
        Files.createDirectories(root.resolve("without-document"));
        Path hidden = Files.createDirectories(root.resolve(".hidden"));
        Files.writeString(hidden.resolve(Skill.SKILL_FILE), "隐藏正文");

        Path outside = Files.createDirectories(temporaryDirectory.resolve("outside-skill"));
        Files.writeString(outside.resolve(Skill.SKILL_FILE), "外部正文");
        try {
            Files.createSymbolicLink(root.resolve("outside-link"), outside);
        } catch (UnsupportedOperationException ignored) {
            // 路径过滤仍由隐藏目录和缺失文档场景验证。
        }

        List<Skill> loaded = repository.loadAll();
        assertEquals(2, loaded.size());
        Skill defaultSkill = loaded.stream()
                .filter(skill -> skill.getId().equals("defaults"))
                .findFirst().orElseThrow();
        assertEquals("defaults", defaultSkill.getName());
        assertEquals("", defaultSkill.getDescription());
        assertTrue(defaultSkill.isEnabled());
        assertEquals("1.0.0", defaultSkill.getVersion());
        assertTrue(defaultSkill.getTags().isEmpty());

        Skill parsed = loaded.stream()
                .filter(skill -> skill.getId().equals("metadata"))
                .findFirst().orElseThrow();
        assertEquals("元数据技能", parsed.getName());
        assertFalse(parsed.isEnabled());
        assertEquals(List.of("one", "two"), parsed.getTags());
        assertEquals(List.of("macos", "linux"), parsed.getPlatforms());
        assertEquals(List.of("browser", "network"), parsed.getRequiresToolGroups());
        assertEquals(List.of("legacy"), parsed.getFallbackForToolGroups());
        assertEquals(SkillSource.AGENT, parsed.getSource());
        assertTrue(parsed.isUserModified());

        Path impossibleRoot = temporaryDirectory.resolve("root-is-file");
        Files.writeString(impossibleRoot, "not a directory");
        assertTrue(new SkillFileRepository(impossibleRoot).loadAll().isEmpty());
    }

    @Test
    void savesValidateIdentityCredentialsAndReadBackName() throws Exception {
        SkillFileRepository repository = new SkillFileRepository(
                temporaryDirectory.resolve("save-root"));
        assertThrows(IllegalArgumentException.class, () -> repository.save(null));

        Skill noId = new Skill(null, "无 ID", "描述", true);
        assertThrows(IllegalArgumentException.class, () -> repository.save(noId));
        noId.setId(" ");
        assertThrows(IllegalArgumentException.class, () -> repository.save(noId));

        Skill secretDescription = new Skill(
                "secret-description", "安全", "password: RealSecret-2026", true);
        assertThrows(IllegalArgumentException.class, () -> repository.save(secretDescription));
        Skill secretContent = new Skill("secret-content", "安全", "描述", true);
        secretContent.setContent("token: RealSecret-2026");
        assertThrows(IllegalArgumentException.class, () -> repository.save(secretContent));

        Skill valid = new Skill("valid", "可持久化", null, true);
        valid.setContent(null);
        valid.setVersion(null);
        valid.setCategory(" ");
        valid.setTags(List.of());
        valid.setPlatforms(List.of());
        valid.setRequiresToolGroups(List.of());
        valid.setFallbackForToolGroups(List.of());
        Skill persisted = repository.saveAndReadBack(valid, "可持久化");
        assertEquals("", persisted.getDescription());
        assertEquals("", persisted.getContent().strip());
        assertEquals("1.0.0", persisted.getVersion());
        assertThrows(IllegalStateException.class,
                () -> repository.saveAndReadBack(valid, "另一个名称"));
    }

    @Test
    void supportAndHistoryOperationsFailClosed() throws Exception {
        Path root = temporaryDirectory.resolve("operations-root");
        SkillFileRepository repository = new SkillFileRepository(root);
        Skill skill = new Skill("operations", "操作技能", "描述", true);
        skill.setContent("版本一");
        skill = repository.saveAndReadBack(skill, skill.getName());

        assertNotNull(repository.writeSupportFile(null, "a.md", "x"));
        assertNotNull(repository.writeSupportFile(skill, null, "x"));
        assertNotNull(repository.writeSupportFile(skill, " ", "x"));
        assertNotNull(repository.writeSupportFile(skill, ".", "x"));
        assertNotNull(repository.writeSupportFile(skill, "../escape.md", "x"));
        assertNotNull(repository.writeSupportFile(skill, Skill.SKILL_FILE, "x"));

        Path blockedParent = skill.getDirectory().resolve("blocked-parent");
        Files.writeString(blockedParent, "regular file");
        assertTrue(repository.writeSupportFile(
                skill, "blocked-parent/child.txt", "x").contains("写入失败"));
        assertNotNull(repository.removeSupportFile(skill, "missing.txt"));
        assertNotNull(repository.removeSupportFile(null, "missing.txt"));

        repository.archive(null);
        repository.archive(new Skill("no-dir", "无目录", "描述", true));
        Skill noDocument = new Skill("no-doc", "无文档", "描述", true);
        noDocument.setDirectory(Files.createDirectories(root.resolve("no-doc")));
        repository.archive(noDocument);
        assertTrue(repository.listHistory(null).isEmpty());
        assertTrue(repository.listHistory(noDocument).isEmpty());
        assertNull(repository.readHistory(null, "1.0.0"));
        assertNull(repository.readHistory(noDocument, "1.0.0"));

        repository.archive(skill);
        skill.setVersion("2.10.0");
        repository.archive(skill);
        Path history = skill.getDirectory().resolve(Skill.HISTORY_DIR);
        Files.writeString(history.resolve("vbad.md"), "invalid-version-name");
        assertEquals(List.of("2.10.0", "1.0.0", "bad"), repository.listHistory(skill));
        assertTrue(repository.readHistory(skill, "1.0.0").contains("版本一"));

        Path unreadableSnapshot = history.resolve("vdirectory.md");
        Files.createDirectories(unreadableSnapshot);
        assertNull(repository.readHistory(skill, "directory"));

        Skill rollbackTarget = repository.rollback(skill, "1.0.0");
        assertNotNull(rollbackTarget);
        assertEquals("1.0.1", rollbackTarget.getVersion());
        assertNull(repository.rollback(null, "1.0.0"));
        assertNull(repository.rollback(skill, "missing"));

        Files.deleteIfExists(skill.getDirectory().resolve(Skill.SKILL_FILE));
        Files.createDirectories(skill.getDirectory().resolve(Skill.SKILL_FILE));
        assertNull(repository.rollback(skill, "1.0.0"));
    }

    @Test
    void fileClassificationAndModelsUseConservativeDefaults() throws Exception {
        assertEquals("", SkillFileRepository.sanitizeDirectoryName(null));
        assertEquals("", SkillFileRepository.sanitizeDirectoryName("  "));
        assertEquals("my-skill", SkillFileRepository.sanitizeDirectoryName(" My Skill! "));

        for (String extension : List.of(
                "a.md", "a.TXT", "a.yaml", "a.YML", "a.json", "a.XML",
                "a.html", "a.CSV")) {
            assertTrue(SkillFileRepository.isTextFile(Path.of(extension)), extension);
        }
        assertFalse(SkillFileRepository.isTextFile(Path.of("a.bin")));

        Skill skill = new Skill();
        assertEquals("", skill.getContent());
        skill.setVersion(null);
        assertEquals("1.0.0", skill.getVersion());
        skill.setVersion(" ");
        assertEquals("1.0.0", skill.getVersion());
        skill.setVersion(" 3.2.1 ");
        assertEquals("3.2.1", skill.getVersion());
        skill.setTags(null);
        skill.setCategory(null);
        skill.setSource(null);
        skill.setPlatforms(null);
        skill.setRequiresToolGroups(null);
        skill.setFallbackForToolGroups(null);
        assertTrue(skill.getTags().isEmpty());
        assertEquals("", skill.getCategory());
        assertEquals(SkillSource.USER, skill.getSource());
        assertTrue(skill.getPlatforms().isEmpty());
        assertTrue(skill.getRequiresToolGroups().isEmpty());
        assertTrue(skill.getFallbackForToolGroups().isEmpty());
        assertFalse(skill.hasScripts());
        assertFalse(skill.hasReferences());
        assertFalse(skill.hasAssets());

        Path directory = Files.createDirectories(temporaryDirectory.resolve("model-skill"));
        skill.setDirectory(directory);
        Files.createDirectories(directory.resolve(Skill.SCRIPTS_DIR));
        Files.createDirectories(directory.resolve(Skill.REFERENCES_DIR));
        Files.createDirectories(directory.resolve(Skill.ASSETS_DIR));
        assertTrue(skill.hasScripts());
        assertTrue(skill.hasReferences());
        assertTrue(skill.hasAssets());
    }
}
