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

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    private static void deleteTree(Path root) throws Exception {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
