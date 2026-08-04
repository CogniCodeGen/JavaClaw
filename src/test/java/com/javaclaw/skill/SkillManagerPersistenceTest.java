package com.javaclaw.skill;

import com.javaclaw.util.ProjectAccessPolicy;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillManagerPersistenceTest {

    @Test
    void writeFailureNeverLeavesAnInMemorySkillThatLooksPersisted() throws Exception {
        Path testRoot = ProjectAccessPolicy.projectRoot().resolve("target/skill-manager-write-failure");
        deleteTree(testRoot);
        Files.createDirectories(testRoot.getParent());
        Files.writeString(testRoot, "这是文件，不是目录");
        SkillManager manager = new SkillManager(testRoot);

        assertThrows(IllegalStateException.class,
                () -> manager.createAgentSkill("不会落盘", "测试", "正文", "测试", java.util.List.of()));
        assertTrue(manager.getAllSkills().isEmpty());

        Files.deleteIfExists(testRoot);
    }

    @Test
    void successfulCreateIsReadBackFromSkillMdBeforePublication() throws Exception {
        Path testRoot = ProjectAccessPolicy.projectRoot().resolve("target/skill-manager-success");
        deleteTree(testRoot);
        SkillManager manager = new SkillManager(testRoot);

        Skill created = manager.createAgentSkill(
                "原子技能", "描述", "可复用流程", "测试", java.util.List.of("原子"));

        assertTrue(Files.isRegularFile(created.getDirectory().resolve("SKILL.md")));
        assertEquals("原子技能", manager.getSkillByName("原子技能").getName());
        assertEquals("可复用流程", manager.getSkillByName("原子技能").getContent().strip());

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
