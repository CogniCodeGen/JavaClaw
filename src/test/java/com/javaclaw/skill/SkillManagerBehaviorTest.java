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
import java.util.ArrayList;
import java.util.Arrays;
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
            assertTrue(catalog.contains("【文档技能】[knowledge] (docs/qa) 查询产品手册"));
            assertTrue(catalog.contains("参考文档："));
            assertTrue(catalog.contains("guide.md"));
            assertTrue(catalog.contains("脚本："));
            assertTrue(catalog.contains("check.jsh"));
            assertTrue(catalog.contains("Helper.JAVA"));
            assertTrue(catalog.contains("严格隔离下不可执行"));
            assertTrue(catalog.contains("[描述包含疑似凭据，已隐藏]"));
            assertFalse(catalog.contains("RealSecret-2026"));
            assertFalse(catalog.contains("关闭技能"));
            assertFalse(catalog.contains("待隐藏名称"));
            assertFalse(catalog.contains("经验沉淀"));

            settings.setSkillNudgeEnabled(true);
            settings.setSkillEvolutionMode("off");
            assertFalse(manager.buildSkillCatalogPrompt().contains("经验沉淀"));
            settings.setSkillEvolutionMode("suggest");
            assertTrue(manager.buildSkillCatalogPrompt().contains("经验沉淀"));
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
