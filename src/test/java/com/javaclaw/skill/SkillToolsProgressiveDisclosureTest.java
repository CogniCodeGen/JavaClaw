package com.javaclaw.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.config.AgentConfig;
import com.javaclaw.framework.api.RunId;
import com.javaclaw.framework.core.DefaultRunResourceRegistry;
import com.javaclaw.platform.data.DataRoot;
import com.javaclaw.platform.execution.ManagedTaskExecutor;
import com.javaclaw.platform.execution.TaskScope;
import com.javaclaw.platform.spring.ApplicationContexts;
import com.javaclaw.util.TokenEstimator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillToolsProgressiveDisclosureTest {
    private static final Pattern NEXT_CURSOR = Pattern.compile("next_cursor=(\\d+)");

    @TempDir
    Path temporaryDirectory;

    @Test
    void directoryModeTraversesRunSnapshotWithinHardTokenBudget() {
        try (Resources resources = resources("directory")) {
            List<Skill> skills = new ArrayList<>();
            for (int index = 0; index < 500; index++) {
                skills.add(dynamic("tool-page-" + index, "tool-page-skill-" + index,
                        "分页描述", "BODY-" + index));
            }
            resources.manager().registerDynamicSkills("directory-owner", skills);
            SkillTools tools = new SkillTools(
                    resources.manager(), resources.usage(), Set.of());

            resources.manager().registerDynamicSkills("late-owner", List.of(
                    dynamic("late", "运行后新增技能", "描述", "LATE")));

            Set<String> found = new HashSet<>();
            int cursor = 0;
            int pages = 0;
            while (true) {
                String response = tools.readSkill("*", null, cursor);
                assertTrue(TokenEstimator.estimate(response) <= 1_200);
                assertFalse(response.contains("运行后新增技能"));
                for (int index = 0; index < 500; index++) {
                    String name = "tool-page-skill-" + index;
                    if (response.contains("【" + name + "】")) found.add(name);
                }
                Matcher matcher = NEXT_CURSOR.matcher(response);
                if (!matcher.find()) break;
                int next = Integer.parseInt(matcher.group(1));
                assertTrue(next > cursor);
                cursor = next;
                assertTrue(++pages < 100);
            }
            assertEquals(500, found.size());
            assertTrue(tools.readSkill("*", "guide.md", 0).contains("不接受 path"));
            assertTrue(tools.readSkill("*", null, 100_000).contains("游标无效"));

            String missing = tools.readSkill("不存在", null, 0);
            assertTrue(missing.contains("skill_read(\"*\", cursor=0)"));
            assertTrue(missing.length() < 300);
            assertFalse(missing.contains("tool-page-skill-0"));
        }
    }

    @Test
    void runSnapshotKeepsTheFilteredManifestButReadsReferenceContentLive() throws Exception {
        try (Resources resources = resources("snapshot")) {
            Skill stable = dynamic("stable", "浏览器技能", "描述", "STABLE-BODY");
            stable.setRequiresToolGroups(List.of("browser"));
            resources.manager().registerDynamicSkills("stable-owner", List.of(stable));
            Skill disk = resources.manager().createSkill(
                    "参考快照", "描述", "REFERENCE-BODY", true);
            Path references = Files.createDirectories(
                    disk.getDirectory().resolve(Skill.REFERENCES_DIR));
            Files.writeString(references.resolve("before.md"), "BEFORE-REFERENCE");
            Files.writeString(references.resolve("changed.md"), "ORIGINAL-CHANGED");
            Files.writeString(references.resolve("deleted.md"), "ORIGINAL-DELETED");
            Files.writeString(references.resolve("large.md"),
                    "LARGE-START-" + "x".repeat(8_200) + "-LARGE-END");
            Files.writeString(references.resolve("huge.md"),
                    "header\nneedle alpha " + "x".repeat(300_000) + " beta\ntail");
            Files.writeString(references.resolve("changing.md"),
                    "header\nmutable needle " + "y".repeat(300_000) + "\ntail");
            try (SeekableByteChannel channel = Files.newByteChannel(
                    references.resolve("over-scan.md"), StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE)) {
                channel.position(SkillReferenceAccess.SEARCH_SCAN_LIMIT_BYTES);
                channel.write(ByteBuffer.wrap(new byte[] {'\n'}));
            }
            Files.writeString(references.resolve("sensitive.md"),
                    "password: SnapshotSecret-2026");
            Files.write(references.resolve("invalid.md"),
                    new byte[]{(byte) 0xc3, (byte) 0x28});

            SkillTools filtered = new SkillTools(
                    resources.manager(), resources.usage(), Set.of());
            SkillTools allowed = new SkillTools(
                    resources.manager(), resources.usage(), Set.of("browser"));
            resources.manager().unregisterDynamicSkills("stable-owner");
            Files.writeString(references.resolve("after.md"), "AFTER-REFERENCE");
            Files.writeString(references.resolve("changed.md"), "UPDATED-CHANGED");
            Files.delete(references.resolve("deleted.md"));

            assertTrue(filtered.readSkill("浏览器技能", null, 0).contains("未找到"));
            assertTrue(allowed.readSkill("浏览器技能", null, 0).contains("STABLE-BODY"));
            String body = allowed.readSkill("参考快照", null, 0);
            assertTrue(body.contains("before.md"));
            assertTrue(body.contains("sensitive.md"));
            assertTrue(body.contains("invalid.md"));
            assertFalse(body.contains("after.md"));
            assertTrue(allowed.readSkill("参考快照", "before.md", 0)
                    .contains("BEFORE-REFERENCE"));
            assertTrue(allowed.readSkill("参考快照", "changed.md", 0)
                    .contains("UPDATED-CHANGED"));
            assertTrue(allowed.readSkill("参考快照", "deleted.md", 0)
                    .contains("未找到目标参考文件"));
            String sensitive = allowed.readSkill("参考快照", "sensitive.md", 0);
            assertTrue(sensitive.contains("系统已阻止载入"));
            assertFalse(sensitive.contains("SnapshotSecret"));
            assertTrue(allowed.readSkill("参考快照", "invalid.md", 0)
                    .contains("不是合法 UTF-8"));
            String largeFirst = allowed.readSkill("参考快照", "large.md", 0);
            Matcher largeCursor = NEXT_CURSOR.matcher(largeFirst);
            assertTrue(largeCursor.find());
            String largeLast = allowed.readSkill(
                    "参考快照", "large.md", Integer.parseInt(largeCursor.group(1)));
            assertTrue(largeLast.contains("-LARGE-END"));
            assertTrue(largeLast.contains("next_cursor=END"));
            assertTrue(allowed.readSkill("参考快照", "huge.md", 0)
                    .contains("请先提供 query"));
            assertTrue(allowed.readSkill("参考快照", "huge.md", 1, null, null)
                    .contains("请先提供 query"));
            assertTrue(allowed.readSkill("参考快照", "huge.md", 0, null, 2)
                    .contains("请先提供 query"));
            String search = allowed.readSkill(
                    "参考快照", "huge.md", 0, "needle beta", null);
            assertTrue(search.contains("[huge.md:2]"));
            assertTrue(search.contains("needle alpha"));
            assertTrue(allowed.readSkill("参考快照", "huge.md", 1, null, null)
                    .contains("请先提供 query"));
            String located = allowed.readSkill("参考快照", "huge.md", 0, null, 2);
            assertTrue(located.contains("needle alpha"));
            Matcher locatedCursor = NEXT_CURSOR.matcher(located);
            assertTrue(locatedCursor.find());
            int authorizedCursor = Integer.parseInt(locatedCursor.group(1));
            assertTrue(allowed.readSkill(
                    "参考快照", "huge.md", authorizedCursor, null, null)
                    .contains("x"));
            assertTrue(allowed.readSkill(
                    "参考快照", "huge.md", authorizedCursor + 1, null, null)
                    .contains("请先提供 query"));
            SkillTools restartedReads = new SkillTools(
                    resources.manager(), resources.usage(), Set.of("browser"));
            assertTrue(restartedReads.readSkill("参考快照", "huge.md", 0, null, 2)
                    .contains("请先提供 query"));
            assertTrue(allowed.readSkill(
                    "参考快照", "changing.md", 0, "mutable needle", null)
                    .contains("[changing.md:2]"));
            Files.writeString(references.resolve("changing.md"),
                    "header\nmutable needle changed " + "z".repeat(300_100) + "\ntail");
            assertTrue(allowed.readSkill("参考快照", "changing.md", 0, null, 2)
                    .contains("请先提供 query"));
            assertTrue(allowed.readSkill("参考快照", "huge.md", 0, "needle missing", null)
                    .contains("未找到同时包含全部关键词"));
            assertTrue(allowed.readSkill("参考快照", "over-scan.md", 0)
                    .contains("超过 64 MiB"));
            assertTrue(allowed.readSkill(
                    "参考快照", "over-scan.md", 0, "needle", null)
                    .contains("扫描范围已达到上限"));
            assertTrue(allowed.readSkill("参考快照", "../SKILL.md", 0)
                    .contains("未找到目标参考文件"));
            assertTrue(allowed.readSkill("参考快照", "after.md", 0)
                    .contains("未找到目标参考文件"));

            SkillTools refreshed = new SkillTools(
                    resources.manager(), resources.usage(), Set.of("browser"));
            assertTrue(refreshed.readSkill("参考快照", "changed.md", 0)
                    .contains("UPDATED-CHANGED"));
            assertTrue(refreshed.readSkill("参考快照", "deleted.md", 0)
                    .contains("未找到目标参考文件"));
            assertTrue(refreshed.readSkill("参考快照", "after.md", 0)
                    .contains("AFTER-REFERENCE"));
        }
    }

    @Test
    void l0AndSkillBodyStayRunStableWhileReferenceReadsUseLatestDiskContent() throws Exception {
        try (Resources resources = resources("run-snapshot");
             DefaultRunResourceRegistry registry = new DefaultRunResourceRegistry()) {
            Skill disk = resources.manager().createSkill(
                    "运行级快照", "ORIGINAL-DESCRIPTION", "ORIGINAL-BODY", true);
            Path references = Files.createDirectories(
                    disk.getDirectory().resolve(Skill.REFERENCES_DIR));
            Files.writeString(references.resolve("guide.md"), "ORIGINAL-REFERENCE");
            var scope = registry.forRun(new RunId("skill-run"));

            SkillTools firstTurn = new SkillTools(
                    resources.manager(), resources.usage(), Set.of(), scope);
            String firstCatalog = resources.manager().buildSkillCatalogPrompt(scope, Set.of());

            resources.manager().applyEdit("运行级快照", "UPDATED-BODY");
            Skill updated = resources.manager().getSkillByName("运行级快照");
            updated.setDescription("UPDATED-DESCRIPTION");
            resources.manager().updateSkill(updated);
            Files.writeString(references.resolve("guide.md"), "UPDATED-REFERENCE");
            SkillTools laterTurn = new SkillTools(
                    resources.manager(), resources.usage(), Set.of(), scope);
            String laterCatalog = resources.manager().buildSkillCatalogPrompt(scope, Set.of());

            assertEquals(firstCatalog, laterCatalog);
            assertTrue(laterCatalog.contains("ORIGINAL-DESCRIPTION"));
            assertFalse(laterCatalog.contains("UPDATED-DESCRIPTION"));
            assertTrue(firstTurn.readSkill("运行级快照", null, 0).contains("ORIGINAL-BODY"));
            assertTrue(laterTurn.readSkill("运行级快照", null, 0).contains("ORIGINAL-BODY"));
            assertTrue(laterTurn.readSkill("运行级快照", "guide.md", 0)
                    .contains("UPDATED-REFERENCE"));

            var nextScope = registry.forRun(new RunId("skill-next-run"));
            SkillTools nextRun = new SkillTools(
                    resources.manager(), resources.usage(), Set.of(), nextScope);
            assertTrue(resources.manager().buildSkillCatalogPrompt(nextScope, Set.of())
                    .contains("UPDATED-DESCRIPTION"));
            assertTrue(nextRun.readSkill("运行级快照", null, 0).contains("UPDATED-BODY"));
            assertTrue(nextRun.readSkill("运行级快照", "guide.md", 0)
                    .contains("UPDATED-REFERENCE"));
        }
    }

    @Test
    void runCatalogSurvivesRegistryRestartThroughExtensionState() throws Exception {
        try (Resources resources = resources("durable-run-snapshot")) {
            Skill disk = resources.manager().createSkill(
                    "持久运行快照", "ORIGINAL-DESCRIPTION", "ORIGINAL-BODY", true);
            Path references = Files.createDirectories(
                    disk.getDirectory().resolve(Skill.REFERENCES_DIR));
            Files.writeString(references.resolve("guide.md"), "ORIGINAL-REFERENCE");
            RunId runId = new RunId("durable-skill-run");
            String firstCatalog;
            try (DefaultRunResourceRegistry firstRegistry = new DefaultRunResourceRegistry()) {
                var scope = firstRegistry.forRun(runId);
                SkillTools first = new SkillTools(
                        resources.manager(), resources.usage(), Set.of(), runId, scope);
                firstCatalog = resources.manager().buildSkillCatalogPrompt(runId, scope, Set.of());
                assertTrue(first.readSkill("持久运行快照", null, 0)
                        .contains("ORIGINAL-BODY"));
            }

            resources.manager().applyEdit("持久运行快照", "UPDATED-BODY");
            Skill updated = resources.manager().getSkillByName("持久运行快照");
            updated.setDescription("UPDATED-DESCRIPTION");
            resources.manager().updateSkill(updated);
            Files.writeString(references.resolve("guide.md"), "UPDATED-REFERENCE");
            Files.writeString(references.resolve("after.md"), "AFTER-REFERENCE");

            var context = resources.context();
            SkillManager restoredManager = new SkillManager(
                    resources.manager().getSkillsDir(), context.getBean(ObjectMapper.class),
                    context.getBean(AgentConfig.class),
                    context.getBean(com.javaclaw.framework.spi.ExtensionStateStore.class));
            try (DefaultRunResourceRegistry restoredRegistry = new DefaultRunResourceRegistry()) {
                var scope = restoredRegistry.forRun(runId);
                SkillTools restored = new SkillTools(
                        restoredManager, resources.usage(), Set.of(), runId, scope);
                String restoredCatalog = restoredManager.buildSkillCatalogPrompt(
                        runId, scope, Set.of());

                assertEquals(firstCatalog, restoredCatalog);
                assertTrue(restoredCatalog.contains("ORIGINAL-DESCRIPTION"));
                assertFalse(restoredCatalog.contains("UPDATED-DESCRIPTION"));
                assertTrue(restored.readSkill("持久运行快照", null, 0)
                        .contains("ORIGINAL-BODY"));
                assertTrue(restored.readSkill("持久运行快照", "guide.md", 0)
                        .contains("UPDATED-REFERENCE"));
                assertTrue(restored.readSkill("持久运行快照", "after.md", 0)
                        .contains("未找到目标参考文件"));
            }
        }
    }

    private Resources resources(String name) {
        var context = ApplicationContexts.createRoot(
                new DataRoot(temporaryDirectory.resolve("data-" + name)));
        TaskScope tasks = context.getBean(ManagedTaskExecutor.class)
                .openScope("skill-tools-" + name, 4);
        AgentConfig settings = context.getBean(AgentConfig.class);
        SkillManager manager = new SkillManager(
                temporaryDirectory.resolve("skills-" + name),
                context.getBean(ObjectMapper.class), settings,
                context.getBean(com.javaclaw.framework.spi.ExtensionStateStore.class));
        SkillUsageTracker usage = new SkillUsageTracker(
                "workspace-" + name,
                context.getBean(JdbcTemplate.class),
                context.getBean(PlatformTransactionManager.class),
                settings, context.getBean(ManagedTaskExecutor.class), tasks);
        return new Resources(context, tasks, manager, usage);
    }

    private static Skill dynamic(
            String id, String name, String description, String content) {
        Skill skill = new Skill(id, name, description, true);
        skill.setContent(content);
        return skill;
    }

    private record Resources(
            org.springframework.context.annotation.AnnotationConfigApplicationContext context,
            TaskScope tasks,
            SkillManager manager,
            SkillUsageTracker usage) implements AutoCloseable {
        @Override
        public void close() {
            usage.close();
            tasks.close();
            context.close();
        }
    }
}
