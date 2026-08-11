package com.javaclaw.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.config.AgentConfig;
import com.javaclaw.platform.data.DataRoot;
import com.javaclaw.platform.spring.ApplicationContexts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillManagerPersistenceTest {

    @Test
    void writeFailureNeverLeavesAnInMemorySkillThatLooksPersisted(
            @TempDir Path temporaryDirectory) throws Exception {
        Path testRoot = temporaryDirectory.resolve("skill-manager-write-failure");
        deleteTree(testRoot);
        Files.createDirectories(testRoot.getParent());
        Files.writeString(testRoot, "这是文件，不是目录");
        try (var root = ApplicationContexts.createRoot(
                new DataRoot(temporaryDirectory.resolve("data-v3")))) {
            SkillManager manager = new SkillManager(
                    testRoot, new ObjectMapper(), root.getBean(AgentConfig.class));
            assertThrows(IllegalStateException.class,
                    () -> manager.createAgentSkill(
                            "不会落盘", "测试", "正文", "测试", java.util.List.of()));
            assertTrue(manager.getAllSkills().isEmpty());
        }

        Files.deleteIfExists(testRoot);
    }

    @Test
    void successfulCreateIsReadBackFromSkillMdBeforePublication(
            @TempDir Path temporaryDirectory) throws Exception {
        Path testRoot = temporaryDirectory.resolve("skill-manager-success");
        deleteTree(testRoot);
        try (var root = ApplicationContexts.createRoot(
                new DataRoot(temporaryDirectory.resolve("data-v3")))) {
            SkillManager manager = new SkillManager(
                    testRoot, new ObjectMapper(), root.getBean(AgentConfig.class));
            Skill created = manager.createAgentSkill(
                    "原子技能", "描述", "可复用流程", "测试", java.util.List.of("原子"));

            assertTrue(Files.isRegularFile(created.getDirectory().resolve("SKILL.md")));
            assertEquals("原子技能", manager.getSkillByName("原子技能").getName());
            assertEquals("可复用流程", manager.getSkillByName("原子技能").getContent().strip());
        }

        deleteTree(testRoot);
    }

    @Test
    void supportFilesVersionsAndBundlesRemainDurable(
            @TempDir Path temporaryDirectory) throws Exception {
        Path skills = temporaryDirectory.resolve("durable-skills");
        try (var root = ApplicationContexts.createRoot(
                new DataRoot(temporaryDirectory.resolve("data-v3")))) {
            ObjectMapper mapper = root.getBean(ObjectMapper.class);
            AgentConfig settings = root.getBean(AgentConfig.class);
            SkillManager manager = new SkillManager(skills, mapper, settings);
            Skill created = manager.createAgentSkill(
                    "持久技能", "描述", "执行第一步", "测试", List.of("持久化"));

            assertNull(manager.writeSupportFile(
                    created.getName(), "references/check.md", "核对结果"));
            assertTrue(manager.buildReferenceDetail(
                    created.getName(), "check.md").contains("核对结果"));
            assertNotNull(manager.writeSupportFile(
                    created.getName(), "../escape.md", "越界"));

            assertNull(manager.applyPatch(created.getName(), "第一步", "第二步"));
            assertEquals("1.0.1", manager.getSkill(created.getId()).getVersion());
            assertEquals(List.of("1.0.0"), manager.listHistory(created.getId()));
            assertTrue(manager.rollback(created.getId(), "1.0.0"));
            assertTrue(manager.getSkill(created.getId()).getContent().contains("第一步"));

            manager.saveBundles(List.of(new SkillBundle(
                    "持久包", "组合流程", List.of(created.getName()), "最后核对", true)));
            SkillManager restored = new SkillManager(skills, mapper, settings);
            assertTrue(restored.buildBundlePrompt("持久包").contains("最后核对"));
            assertNull(restored.removeSupportFile(created.getName(), "references/check.md"));
        }

        deleteTree(skills);
    }

    private static void deleteTree(Path root) throws Exception {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
