package com.javaclaw.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.config.AgentConfig;
import com.javaclaw.platform.data.DataRoot;
import com.javaclaw.platform.spring.ApplicationContexts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillManagerMutationTest {

    @TempDir
    Path temporaryDirectory;

    private final AtomicInteger sequence = new AtomicInteger();

    @Test
    void createUpdateAndDeletePublishOnlyValidatedSnapshots() throws Exception {
        try (Fixture fixture = fixture("lifecycle")) {
            SkillManager manager = fixture.manager();

            assertThrows(IllegalArgumentException.class,
                    () -> manager.createSkill(null, "描述", "正文", true));
            assertThrows(IllegalArgumentException.class,
                    () -> manager.createSkill(" ", "描述", "正文", true));
            assertThrows(IllegalArgumentException.class,
                    () -> manager.createSkill("token: RealSecret-2026", "描述", "正文", true));
            assertThrows(IllegalArgumentException.class,
                    () -> manager.createSkill("安全名称", "password: RealSecret-2026", "正文", true));
            assertThrows(IllegalArgumentException.class,
                    () -> manager.createSkill("安全名称", "描述", "api_key: RealSecret-2026", true));
            assertThrows(IllegalArgumentException.class,
                    () -> manager.createAgentSkill("", "描述", "正文", "测试", List.of()));

            Skill first = manager.createSkill("My / Skill", "初始描述", "初始正文", true);
            Skill collision = manager.createSkill("My / Skill", "第二份", "第二正文", true);
            assertNotEquals(first.getId(), collision.getId());
            assertFalse(first.getId().contains("/"));
            assertTrue(Files.isRegularFile(first.getDirectory().resolve(Skill.SKILL_FILE)));
            assertEquals(2, manager.getAllSkills().size());
            assertNotNull(manager.getSkill(first.getId()));
            assertNull(manager.getSkill("missing"));

            assertNull(manager.createAgentSkill(
                    first.getName(), "重复", "正文", "测试", List.of()));
            Files.createDirectories(manager.getSkillsDir().resolve("预留目录"));
            Skill collisionWithDirectory = manager.createAgentSkill(
                    "预留目录", "目录已存在", "正文", "测试", List.of());
            assertTrue(collisionWithDirectory.getId().startsWith("预留目录-"));
            Skill agent = manager.createAgentSkill(
                    "智能体技能", "可沉淀流程", "执行后核验。", "automation", List.of("agent"));
            assertEquals(SkillSource.AGENT, agent.getSource());
            assertEquals("automation", agent.getCategory());
            assertEquals(List.of("agent"), agent.getTags());

            agent.setDescription("更新描述");
            agent.setContent("更新正文");
            agent.setEnabled(false);
            agent.setVersion("2.3.4");
            agent.setTags(List.of("updated"));
            agent.setCategory("updated-category");
            agent.setSource(SkillSource.BUILTIN);
            agent.setUserModified(true);
            agent.setPlatforms(List.of("macos"));
            agent.setRequiresToolGroups(List.of("browser"));
            agent.setFallbackForToolGroups(List.of("legacy"));
            manager.updateSkill(agent);

            Skill updated = manager.getSkill(agent.getId());
            assertEquals("更新正文", updated.getContent().strip());
            assertFalse(updated.isEnabled());
            assertEquals("2.3.4", updated.getVersion());
            assertEquals(List.of("updated"), updated.getTags());
            assertEquals("updated-category", updated.getCategory());
            assertEquals(SkillSource.BUILTIN, updated.getSource());
            assertTrue(updated.isUserModified());
            assertEquals(List.of("macos"), updated.getPlatforms());
            assertEquals(List.of("browser"), updated.getRequiresToolGroups());
            assertEquals(List.of("legacy"), updated.getFallbackForToolGroups());

            Skill external = new Skill("external", "外部快照", null, true);
            external.setContent(null);
            external.setTags(null);
            external.setCategory(null);
            external.setSource(null);
            external.setPlatforms(null);
            external.setRequiresToolGroups(null);
            external.setFallbackForToolGroups(null);
            manager.updateSkill(external);
            assertNotNull(manager.getSkill("external"));
            assertEquals("", manager.getSkill("external").getContent().strip());

            assertThrows(IllegalArgumentException.class, () -> manager.updateSkill(null));
            external.setContent("password: RealSecret-2026");
            assertThrows(IllegalArgumentException.class, () -> manager.updateSkill(external));

            manager.deleteSkill(agent.getId());
            assertNull(manager.getSkill(agent.getId()));
            assertFalse(Files.exists(agent.getDirectory()));
            manager.deleteSkill("missing");
        }
    }

    @Test
    void patchEditHistoryAndRollbackHaveDeterministicVersionSemantics() throws Exception {
        try (Fixture fixture = fixture("versions")) {
            SkillManager manager = fixture.manager();
            Skill skill = manager.createAgentSkill(
                    "版本技能", "描述", "alpha unique omega", "testing", List.of("version"));

            assertTrue(manager.applyPatch("不存在", "a", "b").contains("未找到"));
            assertTrue(manager.applyPatch(skill.getName(), null, "b").contains("old_string 为空"));
            assertTrue(manager.applyPatch(skill.getName(), "", "b").contains("old_string 为空"));
            assertTrue(manager.applyPatch(skill.getName(), "missing", "b").contains("未找到"));

            skill.setContent("repeat repeat");
            assertTrue(manager.applyPatch(skill.getName(), "repeat", "once").contains("出现多次"));
            manager.reload();
            skill = manager.getSkillByName("版本技能");
            assertTrue(manager.applyPatch(
                    skill.getName(), "unique", "password: RealSecret-2026").contains("疑似凭据"));

            assertNull(manager.applyPatch(skill.getName(), "unique ", null));
            Skill patched = manager.getSkill(skill.getId());
            assertEquals("1.0.1", patched.getVersion());
            assertEquals("alpha omega", patched.getContent().strip());
            assertEquals(List.of("1.0.0"), manager.listHistory(skill.getId()));
            assertTrue(manager.readHistory(skill.getId(), "1.0.0").contains("alpha unique omega"));
            assertNull(manager.readHistory(skill.getId(), "9.9.9"));
            assertNull(manager.readHistory("missing", "1.0.0"));

            assertTrue(manager.applyEdit("不存在", "正文").contains("未找到"));
            assertTrue(manager.applyEdit(skill.getName(), null).contains("new_content 为空"));
            assertTrue(manager.applyEdit(skill.getName(), " ").contains("new_content 为空"));
            assertTrue(manager.applyEdit(
                    skill.getName(), "token: RealSecret-2026").contains("疑似凭据"));
            assertNull(manager.applyEdit(skill.getName(), "结构化的新正文"));
            Skill edited = manager.getSkill(skill.getId());
            assertEquals("1.1.0", edited.getVersion());
            assertEquals(List.of("1.0.1", "1.0.0"), manager.listHistory(skill.getId()));

            assertFalse(manager.rollback("missing", "1.0.0"));
            assertFalse(manager.rollback(skill.getId(), "9.9.9"));
            assertTrue(manager.rollback(skill.getId(), "1.0.0"));
            Skill rolledBack = manager.getSkill(skill.getId());
            assertEquals("1.0.1", rolledBack.getVersion());
            assertTrue(rolledBack.getContent().contains("alpha unique omega"));

            rolledBack.setContent("password: RealSecret-2026 then safe");
            assertNull(manager.applyPatch(
                    rolledBack.getName(), "password: RealSecret-2026", "redacted"));
            assertFalse(manager.getSkill(skill.getId()).getContent().contains("RealSecret"));

            manager.archiveVersion(null);
            manager.archiveVersion(new Skill("no-directory", "无目录", "描述", true));
            Skill noDocument = new Skill("no-document", "无文档", "描述", true);
            noDocument.setDirectory(Files.createDirectories(
                    temporaryDirectory.resolve("no-document-directory")));
            manager.archiveVersion(noDocument);
            assertTrue(manager.listHistory("missing").isEmpty());

            assertEquals("1.0.1", SkillManager.bumpVersion(null, SkillManager.BumpLevel.PATCH));
            assertEquals("1.0.1", SkillManager.bumpVersion("", null));
            assertEquals("2.0.1", SkillManager.bumpVersion("2", SkillManager.BumpLevel.PATCH));
            assertEquals("2.3.1", SkillManager.bumpVersion("2.3", SkillManager.BumpLevel.PATCH));
            assertEquals("2.4.0", SkillManager.bumpVersion("2.3.9", SkillManager.BumpLevel.MINOR));
            assertEquals("1.1.0", SkillManager.bumpVersion("invalid", SkillManager.BumpLevel.MINOR));
        }
    }

    @Test
    void supportFilesStayInsideTheSkillAndNeverPersistCredentials() throws Exception {
        try (Fixture fixture = fixture("support")) {
            SkillManager manager = fixture.manager();
            Skill skill = manager.createSkill("支持文件", "描述", "正文", true);

            assertTrue(manager.writeSupportFile("missing", "references/a.md", "x")
                    .contains("未找到"));
            assertTrue(manager.removeSupportFile("missing", "references/a.md")
                    .contains("未找到"));
            assertNotNull(manager.writeSupportFile(skill.getName(), null, "x"));
            assertNotNull(manager.writeSupportFile(skill.getName(), " ", "x"));
            assertNotNull(manager.writeSupportFile(skill.getName(), ".", "x"));
            assertNotNull(manager.writeSupportFile(skill.getName(), "../escape.md", "x"));
            assertNotNull(manager.writeSupportFile(skill.getName(), Skill.SKILL_FILE, "x"));
            assertTrue(manager.writeSupportFile(
                    skill.getName(), "references/secret.md", "password: RealSecret-2026")
                    .contains("疑似凭据"));

            assertNull(manager.writeSupportFile(
                    skill.getName(), "references/empty.md", null));
            assertEquals("", Files.readString(
                    skill.getDirectory().resolve("references/empty.md")));
            assertNull(manager.writeSupportFile(
                    skill.getName(), "assets/template.txt", "template"));
            assertTrue(skill.hasReferences());
            assertTrue(skill.hasAssets());
            assertFalse(skill.hasScripts());

            assertNotNull(manager.removeSupportFile(skill.getName(), "references/missing.md"));
            assertNotNull(manager.removeSupportFile(skill.getName(), "../escape.md"));
            assertNull(manager.removeSupportFile(skill.getName(), "references/empty.md"));
            assertFalse(Files.exists(skill.getDirectory().resolve("references/empty.md")));

            Path outside = Files.createDirectories(temporaryDirectory.resolve("outside"));
            Path link = skill.getDirectory().resolve("linked-outside");
            try {
                Files.createSymbolicLink(link, outside);
                assertNotNull(manager.writeSupportFile(
                        skill.getName(), "linked-outside/escape.md", "blocked"));
            } catch (UnsupportedOperationException ignored) {
                // 当前文件系统不支持符号链接时，其他路径穿越断言仍覆盖安全边界。
            }
        }
    }

    private Fixture fixture(String name) {
        int index = sequence.incrementAndGet();
        AnnotationConfigApplicationContext context = ApplicationContexts.createRoot(
                new DataRoot(temporaryDirectory.resolve("data-" + name + "-" + index)));
        SkillManager manager = new SkillManager(
                temporaryDirectory.resolve("skills-" + name + "-" + index),
                context.getBean(ObjectMapper.class), context.getBean(AgentConfig.class));
        return new Fixture(context, manager);
    }

    private record Fixture(
            AnnotationConfigApplicationContext context,
            SkillManager manager) implements AutoCloseable {

        @Override
        public void close() {
            context.close();
        }
    }
}
