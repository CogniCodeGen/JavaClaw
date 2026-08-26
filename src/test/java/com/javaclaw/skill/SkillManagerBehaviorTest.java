package com.javaclaw.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.config.AgentConfig;
import com.javaclaw.platform.data.DataRoot;
import com.javaclaw.platform.spring.ApplicationContexts;
import com.javaclaw.util.TokenEstimator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillManagerBehaviorTest {

    @TempDir
    Path temporaryDirectory;

    private final AtomicInteger fixtureSequence = new AtomicInteger();

    @Test
    void dynamicSkillsAreSanitizedScopedAndConditionallyActivated() throws Exception {
        try (Fixture fixture = fixture("dynamic")) {
            SkillManager manager = fixture.manager();
            fixture.settings().setSkillNudgeEnabled(false);
            fixture.settings().setSkillBundlesEnabled(false);

            assertTrue(manager.getAllSkills().isEmpty());
            assertTrue(manager.getEnabledSkills().isEmpty());
            assertNull(manager.getSkillByName(null));
            assertNull(manager.getSkillByName("  "));
            assertEquals("", manager.buildSkillCatalogPrompt());
            assertEquals("", manager.buildEnabledSkillsPrompt());

            Skill safe = manager.buildDynamicSkill(
                    "plugin-a", "动态检索", "检索公开资料", "先检索，再核验来源。\n");
            Skill disabled = manager.buildDynamicSkill(
                    "plugin-a", "禁用技能", "不应暴露", "不会出现");
            disabled.setEnabled(false);
            Skill secretName = dynamic("secret-name", "token: RealSecret-2026", "描述", "正文");
            Skill secretDescription = dynamic(
                    "secret-description", "安全名称", "password: RealSecret-2026", "正文");
            Skill secretContent = dynamic(
                    "secret-content", "安全名称二", "描述", "api_key: RealSecret-2026");

            manager.registerDynamicSkills(null, List.of(safe));
            manager.registerDynamicSkills("  ", List.of(safe));
            manager.registerDynamicSkills("plugin-a", null);
            manager.registerDynamicSkills("plugin-a", List.of());
            manager.registerDynamicSkills("plugin-a", Arrays.asList(
                    null, secretName, secretDescription, secretContent, safe, disabled));

            assertEquals(List.of("动态检索"), manager.getEnabledSkills().stream()
                    .map(Skill::getName).toList());
            assertEquals(1, manager.getActiveSkills(null).size());
            assertSame(safe, manager.getSkillByName(" 动态检索 "));
            assertNull(manager.getSkill("dyn-plugin-a-动态检索"));
            assertTrue(manager.getAllSkills().isEmpty());

            Skill replacement = manager.buildDynamicSkill(
                    "plugin-a", "替换技能", "覆盖同一来源", "新正文");
            manager.registerDynamicSkills("plugin-a", List.of(replacement));
            assertNull(manager.getSkillByName("动态检索"));
            assertSame(replacement, manager.getSkillByName("替换技能"));
            manager.unregisterDynamicSkills(null);
            manager.unregisterDynamicSkills(" ");
            manager.unregisterDynamicSkills("missing");
            manager.unregisterDynamicSkills("plugin-a");
            manager.unregisterDynamicSkills("plugin-a");
            assertTrue(manager.getEnabledSkills().isEmpty());

            assertThrows(IllegalArgumentException.class, () -> manager.buildDynamicSkill(
                    "plugin-a", "危险技能", "描述", "password: RealSecret-2026"));

            verifyPlatformActivation(manager);
            verifyToolGroupActivation(manager);
        }
    }

    @Test
    void catalogsAndDetailsExposeMetadataWithoutLeakingCredentials() throws Exception {
        try (Fixture fixture = fixture("catalog")) {
            SkillManager manager = fixture.manager();
            AgentConfig settings = fixture.settings();
            settings.setSkillBundlesEnabled(false);
            settings.setSkillNudgeEnabled(false);

            Skill documented = manager.createSkill(
                    "文档技能", "查询产品手册", "按照参考文档回答。", true);
            documented.setCategory("knowledge");
            documented.setTags(new ArrayList<>(List.of("docs", "qa")));
            Path references = Files.createDirectories(
                    documented.getDirectory().resolve(Skill.REFERENCES_DIR));
            Files.writeString(references.resolve("guide.md"), "公开指南");
            Files.writeString(references.resolve("secret.txt"), "password: RealSecret-2026");
            Files.writeString(references.resolve("large.yaml"), "x".repeat(10_050));
            Files.writeString(references.resolve("raw.bin"), "不应列出");
            Files.createDirectories(references.resolve("nested.md"));
            Path scripts = Files.createDirectories(
                    documented.getDirectory().resolve(Skill.SCRIPTS_DIR));
            Files.writeString(scripts.resolve("check.jsh"), "System.out.println(1);");
            Files.writeString(scripts.resolve("Helper.JAVA"), "class Helper {}");
            Files.writeString(scripts.resolve("ignore.py"), "print(1)");

            Skill hiddenDescription = manager.createSkill(
                    "脱敏描述", "普通描述", "正常正文", true);
            hiddenDescription.setDescription("password: RealSecret-2026");
            Skill hiddenName = manager.createSkill(
                    "待隐藏名称", "普通描述", "正常正文", true);
            hiddenName.setName("token: RealSecret-2026");
            Skill disabled = manager.createSkill(
                    "关闭技能", "不会展示", "关闭正文", false);

            String catalog = manager.buildSkillCatalogPrompt(Set.of());
            assertTrue(catalog.contains("【文档技能】[knowledge] 查询产品手册"));
            assertFalse(catalog.contains("参考文档："));
            assertFalse(catalog.contains("guide.md"));
            assertFalse(catalog.contains("脚本："));
            assertFalse(catalog.contains("check.jsh"));
            assertFalse(catalog.contains("Helper.JAVA"));
            assertTrue(catalog.contains("[描述已隐藏]"));
            assertFalse(catalog.contains("RealSecret-2026"));
            assertFalse(catalog.contains("关闭技能"));
            assertFalse(catalog.contains("待隐藏名称"));
            assertFalse(catalog.contains("经验沉淀"));

            settings.setSkillNudgeEnabled(true);
            settings.setSkillEvolutionMode("off");
            assertFalse(manager.buildSkillCatalogPrompt().contains("经验沉淀"));
            settings.setSkillEvolutionMode("suggest");
            assertFalse(manager.buildSkillCatalogPrompt().contains("经验沉淀"));
            settings.setSkillNudgeEnabled(false);

            String allEnabled = manager.buildEnabledSkillsPrompt();
            assertTrue(allEnabled.contains("【文档技能】"));
            assertTrue(allEnabled.contains("[参考文档]"));
            assertTrue(allEnabled.contains("公开指南"));
            assertTrue(allEnabled.contains("该参考文档包含疑似凭据"));
            assertTrue(allEnabled.contains("内容已截断"));
            assertTrue(manager.buildFilteredSkillsPrompt(List.of("文档技能"))
                    .contains("公开指南"));
            assertFalse(allEnabled.contains("关闭正文"));

            documented.setContent("password: RealSecret-2026");
            assertTrue(manager.buildEnabledSkillsPrompt().contains("系统已阻止载入"));
            assertFalse(manager.buildEnabledSkillsPrompt().contains("RealSecret-2026"));
            assertEquals("", manager.buildFilteredSkillsPrompt(null));
            assertEquals("", manager.buildFilteredSkillsPrompt(List.of()));
            assertEquals("", manager.buildFilteredSkillsPrompt(List.of("不存在")));
            assertTrue(manager.buildFilteredSkillsPrompt(List.of("文档技能"))
                    .contains("系统已阻止载入"));

            assertNull(manager.buildSkillDetail(null));
            assertNull(manager.buildSkillDetail(" "));
            assertNull(manager.buildSkillDetail("不存在"));
            assertNull(manager.buildSkillDetail(disabled.getName()));
            assertNull(manager.buildSkillDetail(hiddenName.getName()));
            assertTrue(manager.buildSkillDetail(documented.getName()).contains("系统已阻止载入"));

            documented.setContent(null);
            String emptyDetail = manager.buildSkillDetail(documented.getName());
            assertTrue(emptyDetail.startsWith("【文档技能】"));
            assertTrue(emptyDetail.contains("公开指南"));
        }
    }

    @Test
    void referencesAreConfinedRedactedAndBounded() throws Exception {
        try (Fixture fixture = fixture("references")) {
            SkillManager manager = fixture.manager();
            fixture.settings().setSkillNudgeEnabled(false);
            Skill enabled = manager.createSkill("参考技能", "按需读取", "正文", true);
            Skill disabled = manager.createSkill("禁用参考", "不可读", "正文", false);
            Path refs = Files.createDirectories(enabled.getDirectory().resolve(Skill.REFERENCES_DIR));
            Files.writeString(refs.resolve("safe.md"), "安全参考内容");
            Files.writeString(refs.resolve("secret.txt"), "password: RealSecret-2026");
            Files.writeString(refs.resolve("large.yaml"), "x".repeat(10_050));
            Files.writeString(refs.resolve("binary.bin"), "not text");
            Files.createDirectories(refs.resolve("folder.md"));

            assertNull(manager.buildReferenceDetail(null, "safe.md"));
            assertNull(manager.buildReferenceDetail(" ", "safe.md"));
            assertNull(manager.buildReferenceDetail("不存在", "safe.md"));
            assertNull(manager.buildReferenceDetail(disabled.getName(), "safe.md"));
            assertNull(manager.buildReferenceDetail(enabled.getName(), null));
            assertNull(manager.buildReferenceDetail(enabled.getName(), " "));
            assertNull(manager.buildReferenceDetail(enabled.getName(), "../SKILL.md"));
            assertNull(manager.buildReferenceDetail(enabled.getName(), "binary.bin"));
            assertNull(manager.buildReferenceDetail(enabled.getName(), "folder.md"));
            assertNull(manager.buildReferenceDetail(enabled.getName(), "missing.md"));

            assertTrue(manager.buildReferenceDetail(
                    enabled.getName(), "references/safe.md").contains("安全参考内容"));
            assertTrue(manager.buildReferenceDetail(
                    enabled.getName(), "secret.txt").contains("系统已阻止载入"));
            String truncated = manager.buildReferenceDetail(enabled.getName(), "large.yaml");
            assertTrue(truncated.contains("内容已截断"));
            assertTrue(truncated.length() < 10_100);

            Skill dynamic = manager.buildDynamicSkill("owner", "动态无目录", "描述", "正文");
            manager.registerDynamicSkills("owner", List.of(dynamic));
            assertNull(manager.buildReferenceDetail(dynamic.getName(), "safe.md"));
        }
    }

    @Test
    void progressiveReadSeparatesCatalogBodyAndSinglePagedReference() throws Exception {
        try (Fixture fixture = fixture("progressive")) {
            SkillManager manager = fixture.manager();
            Skill skill = manager.createSkill(
                    "渐进技能", "验证三级披露", "正文指令-" + "甲".repeat(8_200), true);
            Path refs = Files.createDirectories(skill.getDirectory().resolve(Skill.REFERENCES_DIR));
            Files.writeString(refs.resolve("first.md"), "FIRST-UNIQUE-" + "乙".repeat(8_100));
            Files.writeString(refs.resolve("second.md"), "SECOND-UNIQUE");

            String catalog = manager.buildSkillCatalogPrompt();
            assertTrue(catalog.contains("渐进技能"));
            assertFalse(catalog.contains("正文指令"));
            assertFalse(catalog.contains("first.md"));
            assertTrue(TokenEstimator.estimate(catalog) <= 1_200);

            SkillContentPage body = manager.readProgressivePage("渐进技能", null, 0, 8_000);
            assertNotNull(body);
            assertTrue(body.content().contains("正文指令"));
            assertFalse(body.content().contains("FIRST-UNIQUE"));
            assertTrue(body.hasMore());
            SkillContentPage bodyTail = manager.readProgressivePage(
                    "渐进技能", null, body.nextCursor(), 8_000);
            assertTrue(bodyTail.content().contains("first.md"));
            assertTrue(bodyTail.content().contains("second.md"));

            SkillContentPage first = manager.readProgressivePage(
                    "渐进技能", "first.md", 0, 8_000);
            assertTrue(first.content().contains("FIRST-UNIQUE"));
            assertFalse(first.content().contains("SECOND-UNIQUE"));
            assertTrue(first.hasMore());
            SkillContentPage firstTail = manager.readProgressivePage(
                    "渐进技能", "first.md", first.nextCursor(), 8_000);
            assertFalse(firstTail.hasMore());
            assertNull(manager.readProgressivePage("渐进技能", "../SKILL.md", 0, 8_000));
        }
    }

    @Test
    void oversizedCatalogKeepsEveryNameAndDropsDescriptionsWithinBudget() throws Exception {
        try (Fixture fixture = fixture("catalog-budget")) {
            List<Skill> many = new ArrayList<>();
            for (int index = 0; index < 100; index++) {
                many.add(dynamic("catalog-" + index, "skill-" + index,
                        "很长的用途说明".repeat(14), "正文不应进入目录"));
            }
            fixture.manager().registerDynamicSkills("catalog-budget", many);

            String catalog = fixture.manager().buildSkillCatalogPrompt(Set.of());

            assertTrue(TokenEstimator.estimate(catalog) <= 1_200);
            for (int index = 0; index < 100; index++) {
                assertTrue(catalog.contains("skill-" + index));
            }
            assertFalse(catalog.contains("正文不应进入目录"));
            assertFalse(catalog.contains("很长的用途说明"));
        }
    }

    @Test
    void veryLargeCatalogUsesCompleteBoundedIndexPages() {
        try (Fixture fixture = fixture("catalog-pages")) {
            List<Skill> many = new ArrayList<>();
            for (int index = 0; index < 500; index++) {
                many.add(dynamic("paged-" + index, "paged-skill-" + index,
                        "目录分页用途说明", "正文-" + index));
            }
            fixture.manager().registerDynamicSkills("catalog-pages", many);

            String firstPromptPage = fixture.manager().buildSkillCatalogPrompt(Set.of());
            assertTrue(TokenEstimator.estimate(firstPromptPage) <= 1_200);
            assertTrue(firstPromptPage.contains("next_cursor="));
            assertFalse(firstPromptPage.contains("next_cursor=END"));

            SkillPromptRenderer.CatalogSnapshot snapshot =
                    fixture.manager().catalogSnapshot(Set.of());
            Set<String> found = new HashSet<>();
            int cursor = 0;
            int pageCount = 0;
            while (true) {
                SkillCatalogPage page = fixture.manager().readCatalogPage(snapshot, cursor, 1_200);
                assertNotNull(page);
                assertTrue(TokenEstimator.estimate(page.content()) <= 1_200);
                for (int index = 0; index < 500; index++) {
                    String name = "paged-skill-" + index;
                    if (page.content().contains("【" + name + "】")) found.add(name);
                }
                pageCount++;
                if (!page.hasMore()) break;
                assertTrue(page.nextCursor() > cursor);
                cursor = page.nextCursor();
                assertTrue(pageCount < 100);
            }
            assertEquals(500, found.size());
            assertTrue(pageCount > 1);
        }
    }

    @Test
    void activationSnapshotStaysStableForCatalogAndBodyReads() {
        try (Fixture fixture = fixture("catalog-snapshot")) {
            Skill stable = dynamic("stable", "运行快照技能", "描述", "SNAPSHOT-BODY");
            stable.setRequiresToolGroups(List.of("browser", "network"));
            fixture.manager().registerDynamicSkills("stable-owner", List.of(stable));

            assertNull(fixture.manager().catalogSnapshot(Set.of("network"))
                    .resolveLookup(stable.getName()));
            SkillPromptRenderer.CatalogSnapshot metadata = fixture.manager()
                    .catalogSnapshot(Set.of("browser", "network"));
            assertTrue(metadata.skills().getFirst().content().isEmpty());
            assertTrue(metadata.skills().getFirst().references().isEmpty());
            SkillPromptRenderer.CatalogSnapshot snapshot = fixture.manager()
                    .readableCatalogSnapshot(Set.of("browser", "network"));
            assertEquals(stable.getName(), snapshot.resolveLookup(stable.getName()));

            fixture.manager().unregisterDynamicSkills("stable-owner");
            fixture.manager().registerDynamicSkills("late-owner", List.of(
                    dynamic("late", "晚注册技能", "描述", "LATE-BODY")));

            assertNull(snapshot.resolveLookup("晚注册技能"));
            assertFalse(fixture.manager().readCatalogPage(snapshot, 0, 1_200)
                    .content().contains("晚注册技能"));
            SkillContentPage body = fixture.manager().readProgressivePage(
                    snapshot, stable.getName(), null, 0, 7_900);
            assertNotNull(body);
            assertTrue(body.content().contains("SNAPSHOT-BODY"));
        }
    }

    @Test
    void legacyLongNameUsesStableAliasAndNewNamesAreValidatedByCodePoint() {
        try (Fixture fixture = fixture("legacy-name")) {
            String longName = "旧".repeat(81);
            Skill legacy = dynamic("legacy-id", "temporary", "描述", "LEGACY-BODY");
            fixture.manager().registerDynamicSkills("legacy-owner", List.of(legacy));
            // Simulates a pre-validation skill loaded from an existing installation.
            legacy.setName(longName);

            SkillPromptRenderer.CatalogSnapshot snapshot =
                    fixture.manager().readableCatalogSnapshot(Set.of());
            SkillPromptRenderer.CatalogSkill catalogSkill = snapshot.skills().getFirst();
            assertTrue(catalogSkill.lookupKey().startsWith("skill:"));
            assertEquals(longName, snapshot.resolveLookup(catalogSkill.lookupKey()));
            assertEquals(longName, snapshot.resolveLookup(longName));
            String catalog = fixture.manager().buildSkillCatalogPrompt(Set.of());
            assertTrue(TokenEstimator.estimate(catalog) <= 1_200);
            assertTrue(catalog.contains(catalogSkill.lookupKey()));
            assertFalse(catalog.contains(longName));
            assertTrue(fixture.manager().readProgressivePage(
                    snapshot, catalogSkill.lookupKey(), null, 0, 7_900)
                    .content().contains("LEGACY-BODY"));

            assertThrows(IllegalArgumentException.class, () -> fixture.manager()
                    .buildDynamicSkill("owner", "新".repeat(81), "描述", "正文"));
            assertThrows(IllegalArgumentException.class, () -> fixture.manager()
                    .buildDynamicSkill("owner", "*", "描述", "正文"));
            assertThrows(IllegalArgumentException.class, () -> fixture.manager()
                    .buildDynamicSkill("owner", "两行\n名称", "描述", "正文"));
            assertNotNull(fixture.manager().buildDynamicSkill(
                    "owner", "😀".repeat(80), "描述", "正文"));
        }
    }

    @Test
    void progressivePagesNeverSplitEmojiSurrogatePairs() {
        try (Fixture fixture = fixture("emoji-page")) {
            fixture.manager().createSkill("emoji", "描述", "A😀B", true);
            String prefix = "【emoji / SKILL.md】\n";
            SkillContentPage first = fixture.manager().readProgressivePage(
                    "emoji", null, 0, prefix.length() + 2);
            assertNotNull(first);
            assertEquals(prefix + "A", first.content());
            assertFalse(hasUnpairedSurrogate(first.content()));

            SkillContentPage emoji = fixture.manager().readProgressivePage(
                    "emoji", null, first.nextCursor(), 1);
            assertNotNull(emoji);
            assertEquals("😀", emoji.content());
            assertFalse(hasUnpairedSurrogate(emoji.content()));
            assertNull(fixture.manager().readProgressivePage(
                    "emoji", null, first.nextCursor() + 1, 10));
        }
    }

    @Test
    void bundlesRejectInvalidEntriesAndLoadAvailableSkillsAsAUnit() throws Exception {
        try (Fixture fixture = fixture("bundles")) {
            SkillManager manager = fixture.manager();
            AgentConfig settings = fixture.settings();
            settings.setSkillNudgeEnabled(false);
            settings.setSkillBundlesEnabled(true);
            Skill first = manager.createSkill("技能甲", "第一步", "执行甲。", true);
            manager.createSkill("技能乙", "第二步", "执行乙。", true);
            Skill disabled = manager.createSkill("技能丙", "已禁用", "执行丙。", false);

            SkillBundle valid = new SkillBundle(
                    "组合包", "组合处理", List.of("技能甲", "不存在", "技能乙"),
                    "最后检查输出", true);
            SkillBundle disabledBundle = new SkillBundle(
                    "关闭包", "不启用", List.of("技能甲"), null, false);
            SkillBundle blank = new SkillBundle(" ", "非法", List.of(), null, true);
            manager.saveBundles(Arrays.asList(null, valid, disabledBundle, blank));

            assertEquals(2, manager.getBundles().size());
            assertEquals(List.of(valid), manager.getEnabledBundles());
            assertSame(valid, manager.getBundle(" 组合包 "));
            assertNull(manager.getBundle(null));
            assertNull(manager.getBundle(" "));
            assertNull(manager.getBundle("关闭包"));
            assertNull(manager.getBundle("不存在"));

            String prompt = manager.buildBundlePrompt("组合包");
            assertTrue(prompt.contains("以下 2 项技能作为一组配合使用"));
            assertTrue(prompt.contains("执行甲。"));
            assertTrue(prompt.contains("执行乙。"));
            assertTrue(prompt.contains("最后检查输出"));
            assertFalse(prompt.contains("执行丙。"));
            assertEquals("", manager.buildBundlePrompt(null));
            assertEquals("", manager.buildBundlePrompt("不存在"));
            manager.reload();
            assertNotNull(manager.getBundle("组合包"));

            manager.saveBundles(List.of(new SkillBundle(
                    "空包", "没有技能", List.of(), "", true)));
            assertEquals("", manager.buildBundlePrompt("空包"));
            manager.saveBundles(List.of(new SkillBundle(
                    "缺失包", "都不存在", List.of("不存在"), "", true)));
            assertEquals("", manager.buildBundlePrompt("缺失包"));
            manager.saveBundles(List.of(new SkillBundle(
                    "禁用技能包", "仅禁用技能", List.of(disabled.getName()), null, true)));
            assertEquals("", manager.buildBundlePrompt("禁用技能包"));

            manager.saveBundles(List.of(new SkillBundle(
                    "无附言包", "描述", List.of(first.getName()), "  ", true)));
            assertFalse(manager.buildBundlePrompt("无附言包").contains("本包附加指令"));
            manager.saveBundles(List.of(new SkillBundle(
                    "脱敏附言包", "描述", List.of(first.getName()),
                    "password: RealSecret-2026", true)));
            String redactedInstructions = manager.buildBundlePrompt("脱敏附言包");
            assertTrue(redactedInstructions.contains("附加指令包含疑似凭据"));
            assertFalse(redactedInstructions.contains("RealSecret-2026"));

            settings.setSkillBundlesEnabled(false);
            assertFalse(manager.buildSkillCatalogPrompt().contains("可用技能包"));
            settings.setSkillBundlesEnabled(true);
            SkillBundle secretDescription = new SkillBundle(
                    "安全包名", "password: RealSecret-2026", List.of(first.getName()), null, true);
            secretDescription.skills = Arrays.asList(
                    first.getName(), null, "token: RealSecret-2026");
            manager.saveBundles(List.of(secretDescription));
            String catalog = manager.buildSkillCatalogPrompt();
            assertTrue(catalog.contains("【安全包名】[描述包含疑似凭据，已隐藏]"));
            assertTrue(catalog.contains("[已隐藏]"));
            assertFalse(catalog.contains("RealSecret-2026"));

            manager.saveBundles(null);
            assertTrue(manager.getBundles().isEmpty());
            Files.writeString(manager.getSkillsDir().resolve("bundles.json"), "not-json");
            manager.reload();
            assertTrue(manager.getBundles().isEmpty());
        }
    }

    private void verifyPlatformActivation(SkillManager manager) {
        Skill windows = dynamic("windows", "Windows 技能", "描述", "正文");
        windows.setPlatforms(List.of("WINDOWS"));
        Skill macos = dynamic("macos", "macOS 技能", "描述", "正文");
        macos.setPlatforms(List.of("macos"));
        Skill linux = dynamic("linux", "Linux 技能", "描述", "正文");
        linux.setPlatforms(List.of("linux"));
        Skill unrestricted = dynamic("all", "通用技能", "描述", "正文");
        manager.registerDynamicSkills("platforms", List.of(windows, macos, linux, unrestricted));

        String oldOs = System.getProperty("os.name");
        try {
            System.setProperty("os.name", "Windows 11");
            assertEquals(Set.of("Windows 技能", "通用技能"), names(manager.getActiveSkills(Set.of())));
            System.setProperty("os.name", "Mac OS X");
            assertEquals(Set.of("macOS 技能", "通用技能"), names(manager.getActiveSkills(Set.of())));
            System.setProperty("os.name", "FreeBSD");
            assertEquals(Set.of("Linux 技能", "通用技能"), names(manager.getActiveSkills(Set.of())));
            assertTrue(unrestricted.isActiveFor(Set.of(), null));
        } finally {
            if (oldOs == null) {
                System.clearProperty("os.name");
            } else {
                System.setProperty("os.name", oldOs);
            }
        }
        manager.unregisterDynamicSkills("platforms");
    }

    private void verifyToolGroupActivation(SkillManager manager) {
        Skill requires = dynamic("requires", "需要浏览器", "描述", "正文");
        requires.setRequiresToolGroups(List.of("browser", "network"));
        Skill fallback = dynamic("fallback", "无浏览器备选", "描述", "正文");
        fallback.setFallbackForToolGroups(List.of("browser"));
        manager.registerDynamicSkills("conditions", List.of(requires, fallback));

        assertEquals(Set.of("无浏览器备选"), names(manager.getActiveSkills(Set.of("network"))));
        assertEquals(Set.of("需要浏览器"),
                names(manager.getActiveSkills(Set.of("browser", "network"))));
        assertEquals(Set.of("需要浏览器", "无浏览器备选"),
                names(manager.getActiveSkills(null)));
    }

    private Fixture fixture(String name) {
        int index = fixtureSequence.incrementAndGet();
        AnnotationConfigApplicationContext context = ApplicationContexts.createRoot(
                new DataRoot(temporaryDirectory.resolve("data-" + name + "-" + index)));
        AgentConfig settings = context.getBean(AgentConfig.class);
        SkillManager manager = new SkillManager(
                temporaryDirectory.resolve("skills-" + name + "-" + index),
                context.getBean(ObjectMapper.class), settings);
        return new Fixture(context, manager, settings);
    }

    private static Skill dynamic(
            String id, String name, String description, String content) {
        Skill skill = new Skill(id, name, description, true);
        skill.setContent(content);
        return skill;
    }

    private static Set<String> names(List<Skill> skills) {
        return skills.stream().map(Skill::getName)
                .collect(java.util.stream.Collectors.toSet());
    }

    private static boolean hasUnpairedSurrogate(String value) {
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (index + 1 >= value.length()
                        || !Character.isLowSurrogate(value.charAt(++index))) return true;
            } else if (Character.isLowSurrogate(current)) {
                return true;
            }
        }
        return false;
    }

    private record Fixture(
            AnnotationConfigApplicationContext context,
            SkillManager manager,
            AgentConfig settings) implements AutoCloseable {

        @Override
        public void close() {
            context.close();
        }
    }
}
