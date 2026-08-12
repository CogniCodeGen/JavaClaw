package com.javaclaw.agent.router;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.config.AgentConfig;
import com.javaclaw.platform.data.DataRoot;
import com.javaclaw.platform.spring.ApplicationContexts;
import com.javaclaw.skill.Skill;
import com.javaclaw.skill.SkillBundle;
import com.javaclaw.skill.SkillManager;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolRouterBehaviorTest {

    @TempDir
    Path tempDirectory;

    @Test
    void 问候快捷路径与多种模型响应都返回稳定路由() {
        try (Fixture fixture = fixture("routing")) {
            assertEquals(RoutingResult.noTools(), fixture.router.route(" 你好 "));
            assertEquals(0, fixture.model.calls);

            fixture.model.respond("""
                    {"toolGroups":["coding","invalid",7],
                     "skillNames":["代码审查"],"bundleNames":["工程包"],
                     "mcpServers":["docs"]}
                    """);
            RoutingResult direct = fixture.router.route("检查项目代码");
            assertEquals(List.of("coding"), direct.toolGroups());
            assertEquals(List.of("代码审查"), direct.skillNames());
            assertEquals(List.of("工程包"), direct.bundleNames());
            assertEquals(List.of("docs"), direct.mcpServers());

            fixture.model.respondWithContentVariants(
                    "前言\n```json\n",
                    "{\"toolGroups\":[\"web\"],\"skillNames\":[],"
                            + "\"bundleNames\":[],\"mcpServers\":[]}",
                    "\n```\n尾声");
            assertEquals(List.of("web"), fixture.router.route("查询网页").toolGroups());

            fixture.model.respond("模型判断：{\"toolGroups\":[\"email\"],"
                    + "\"skillNames\":[],\"bundleNames\":[],\"mcpServers\":[]}");
            assertEquals(List.of("email"), fixture.router.route("发送邮件").toolGroups());

            fixture.model.respond("没有结构化结果");
            assertTrue(fixture.router.route("复杂问题").isFallback());
            fixture.model.respond("{broken-json");
            assertTrue(fixture.router.route("仍然复杂").isFallback());
            fixture.model.fail(new IllegalStateException("模型不可用"));
            assertTrue(fixture.router.route("再次尝试").isFallback());
        }
    }

    @Test
    void 动态技能和技能包只在启用且非空时进入路由提示词() {
        try (Fixture fixture = fixture("prompt")) {
            fixture.model.respond(emptyRoutingJson());
            fixture.router.route("第一次路由");
            String emptyPrompt = fixture.model.lastMessages.getFirst().getTextContent();
            assertFalse(emptyPrompt.contains("## 可用技能\n"));
            assertFalse(emptyPrompt.contains("## 可用技能包\n"));

            Skill described = fixture.skills.buildDynamicSkill(
                    "plugin", "审查技能", "检查代码边界", "执行检查");
            Skill noDescription = fixture.skills.buildDynamicSkill(
                    "plugin", "简洁技能", null, "执行简洁步骤");
            fixture.skills.registerDynamicSkills("plugin", List.of(described, noDescription));
            fixture.skills.saveBundles(List.of(
                    new SkillBundle("工程包", "组合工程能力",
                            List.of("审查技能"), "执行后复核", true),
                    new SkillBundle("空描述包", " ",
                            List.of("简洁技能"), null, true),
                    new SkillBundle("禁用包", "不会出现",
                            List.of("审查技能"), null, false)));

            fixture.model.respond(emptyRoutingJson());
            fixture.router.route("第二次路由");
            String prompt = fixture.model.lastMessages.getFirst().getTextContent();
            assertTrue(prompt.contains("审查技能：检查代码边界"));
            assertTrue(prompt.contains("简洁技能"));
            assertTrue(prompt.contains("工程包：组合工程能力"));
            assertTrue(prompt.contains("空描述包（含：简洁技能）"));
            assertFalse(prompt.contains("禁用包"));

            fixture.settings.setSkillBundlesEnabled(false);
            fixture.model.respond(emptyRoutingJson());
            fixture.router.route("第三次路由");
            assertFalse(fixture.model.lastMessages.getFirst().getTextContent()
                    .contains("## 可用技能包\n"));
        }
    }

    @Test
    void 管理意图的每个受支持关键词都确定性补齐工具组() {
        for (String site : List.of("站点", "网站", "site")) {
            assertAdds("请管理" + site + "凭证", "web");
        }
        for (String credential : List.of(
                "凭证", "凭据", "账号", "登录", "credential", "session", "会话")) {
            assertAdds("请管理站点" + credential, "web");
        }
        for (String action : List.of(
                "添加", "新增", "创建", "保存", "记录", "登记", "删除", "移除", "管理",
                "add ", "save ", "delete ")) {
            assertAdds("请" + action + "站点", "web");
        }

        RoutingResult existing = ToolRouter.applyDeterministicManagementHints(
                "管理站点凭证并配置 MCP",
                new RoutingResult(List.of("web", "mcp"), null, null, null));
        assertEquals(List.of("web", "mcp"), existing.toolGroups());
        assertTrue(existing.skillNames().isEmpty());
        assertTrue(existing.bundleNames().isEmpty());
        assertTrue(existing.mcpServers().isEmpty());

        RoutingResult nullResult = ToolRouter.applyDeterministicManagementHints(
                null, null);
        assertEquals(RoutingResult.noTools(), nullResult);
        assertEquals(List.of("mcp"), ToolRouter.applyDeterministicManagementHints(
                "MCP", RoutingResult.noTools()).toolGroups());
        assertEquals(List.of(), ToolRouter.applyDeterministicManagementHints(
                "普通聊天", RoutingResult.noTools()).toolGroups());
    }

    @Test
    void 路由结果状态判定兼容空集合与空字段() {
        RoutingResult empty = RoutingResult.noTools();
        assertFalse(empty.hasToolGroups());
        assertFalse(empty.isFallback());
        assertFalse(empty.isAllSkills());
        assertFalse(empty.hasBundles());
        assertFalse(empty.isAllMcp());

        RoutingResult fallback = RoutingResult.fallbackAll();
        assertTrue(fallback.hasToolGroups());
        assertTrue(fallback.isFallback());
        assertTrue(fallback.isAllSkills());
        assertFalse(fallback.hasBundles());
        assertTrue(fallback.isAllMcp());

        RoutingResult nulls = new RoutingResult(null, null, null, null);
        assertFalse(nulls.hasToolGroups());
        assertFalse(nulls.isFallback());
        assertFalse(nulls.isAllSkills());
        assertFalse(nulls.hasBundles());
        assertFalse(nulls.isAllMcp());

        RoutingResult compatible = new RoutingResult(
                List.of("coding"), List.of("skill"), List.of("server"));
        assertTrue(compatible.hasToolGroups());
        assertFalse(compatible.isFallback());
    }

    private Fixture fixture(String name) {
        var root = ApplicationContexts.createRoot(
                new DataRoot(tempDirectory.resolve("data-" + name)));
        ObjectMapper mapper = root.getBean(ObjectMapper.class);
        AgentConfig settings = root.getBean(AgentConfig.class);
        SkillManager skills = new SkillManager(
                tempDirectory.resolve("skills-" + name), mapper, settings);
        FakeModel model = new FakeModel();
        return new Fixture(
                root, settings, skills, model,
                new ToolRouter(model, null, skills, settings, mapper));
    }

    private static void assertAdds(String message, String group) {
        RoutingResult result = ToolRouter.applyDeterministicManagementHints(
                message, RoutingResult.noTools());
        assertTrue(result.toolGroups().contains(group), message);
    }

    private static String emptyRoutingJson() {
        return "{\"toolGroups\":[],\"skillNames\":[],"
                + "\"bundleNames\":[],\"mcpServers\":[]}";
    }

    private record Fixture(
            org.springframework.context.annotation.AnnotationConfigApplicationContext root,
            AgentConfig settings,
            SkillManager skills,
            FakeModel model,
            ToolRouter router) implements AutoCloseable {
        @Override
        public void close() {
            root.close();
        }
    }

    private static final class FakeModel extends ChatModelBase {
        private Flux<ChatResponse> next = Flux.empty();
        private int calls;
        private List<Msg> lastMessages = List.of();

        private void respond(String text) {
            next = Flux.just(response(List.of(textBlock(text))));
        }

        private void respondWithContentVariants(String... chunks) {
            List<ChatResponse> responses = new ArrayList<>();
            responses.add(response(null));
            List<ContentBlock> mixed = new ArrayList<>();
            mixed.add(null);
            mixed.add(textBlock(chunks[0]));
            responses.add(response(mixed));
            for (int index = 1; index < chunks.length; index++) {
                responses.add(response(List.of(textBlock(chunks[index]))));
            }
            next = Flux.fromIterable(responses);
        }

        private void fail(Throwable failure) {
            next = Flux.error(failure);
        }

        @Override
        public String getModelName() {
            return "router-test";
        }

        @Override
        protected Flux<ChatResponse> doStream(
                List<Msg> messages,
                List<ToolSchema> tools,
                GenerateOptions options) {
            calls++;
            lastMessages = List.copyOf(messages);
            return next;
        }

        private static ChatResponse response(List<ContentBlock> content) {
            return ChatResponse.builder().content(content).build();
        }

        private static TextBlock textBlock(String text) {
            return TextBlock.builder().text(text).build();
        }
    }
}
