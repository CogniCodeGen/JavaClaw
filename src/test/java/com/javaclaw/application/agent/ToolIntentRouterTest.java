package com.javaclaw.application.agent;

import com.javaclaw.api.conversation.ConversationRequest;
import com.javaclaw.api.conversation.ConversationMessage;
import com.javaclaw.api.conversation.ConversationOptions;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ToolIntentRouterTest {
    private final ToolIntentRouter router = new ToolIntentRouter(true);

    @Test
    void informationalRequestsExposeOnlyResidentProgressiveTools() {
        for (String prompt : List.of(
                "什么是向量数据库？", "解释一下 CAP 原理", "如何理解 Java 虚拟线程？",
                "why is the sky blue?", "介绍一下微服务", "怎样设计缓存策略？",
                "邮件协议有什么区别？", "任务调度的原理是什么？", "解释网页渲染流程",
                "代码测试为什么要隔离？", "如何发送邮件？", "what is MCP?",
                "how to send email?", "please explain how to send email",
                "帮我解释如何运行测试", "could you explain git diff?")) {
            ToolExposureDecision decision = route(prompt);
            assertEquals(java.util.Set.of("ask_user_clarification", "skill_read"),
                    decision.allowedTools(), prompt);
            assertTrue(decision.bundleIds().isEmpty(), prompt);
            assertFalse(decision.legacyAll());
        }
    }

    @Test
    void explicitActionsChooseSmallCorrectBundles() {
        assertBundle("帮我查最新新闻", "web.read");
        assertBundle("search the web for the latest release", "web.read");
        assertBundle("打开网页并点击登录按钮", "web.interact");
        assertBundle("读取当前项目文件", "files.read");
        assertBundle("请检查我未提交的更改", "files.read");
        assertBundle("review the uncommitted changes", "files.read");
        assertBundle("查看 git diff", "files.read");
        assertBundle("修复当前项目代码并运行测试", "files.read", "files.write", "code.execute");
        assertBundle("run tests in this repository", "code.execute");
        assertBundle("发送邮件给张三", "email.send");
        assertBundle("查看未读邮件", "email.read");
        assertBundle("创建一个每天九点执行的定时任务", "schedule.manage");
        assertBundle("create a scheduled task every day at nine", "schedule.manage");
        assertBundle("查询知识库中的项目文档", "knowledge.read");
        assertBundle("解释项目文档中的部署流程", "knowledge.read");
        assertBundle("如何根据知识库处理退款", "knowledge.read");
        assertBundle("搜索一下知识库里的退款规则", "knowledge.read");
        assertBundle("创建技能记录这个工作流", "skill.manage");
        assertBundle("解释邮件协议，并顺便帮我发送邮件给张三", "email.send");
        assertBundle("解释并修复当前项目代码", "files.read", "files.write");
        assertBundle("解释当前实现，然后修改项目文件", "files.read", "files.write");
        assertBundle("explain and fix the current repository", "files.read", "files.write");
        assertTrue(route("帮我查最新新闻").allowedTools().size()
                <= ToolBundleCatalog.MAX_TOOLS);
    }

    @Test
    void versionControlExplanationsRemainToolFree() {
        for (String prompt : List.of(
                "什么是 git diff？", "解释未暂存和已暂存的区别",
                "如何理解 working tree？", "what is an uncommitted change?",
                "什么是知识库？", "what is a knowledge base?")) {
            ToolExposureDecision decision = route(prompt);
            assertTrue(decision.bundleIds().isEmpty(), prompt);
            assertEquals(java.util.Set.of("ask_user_clarification", "skill_read"),
                    decision.allowedTools(), prompt);
        }
    }

    @Test
    void recurringWordsWithoutAScheduleObjectRemainToolFree() {
        for (String prompt : List.of(
                "每天应该喝多少水？", "每隔多久休息一次比较好？",
                "why should I exercise every day?")) {
            ToolExposureDecision decision = route(prompt);
            assertTrue(decision.bundleIds().isEmpty(), prompt);
            assertEquals(java.util.Set.of("ask_user_clarification", "skill_read"),
                    decision.allowedTools(), prompt);
        }
    }

    @Test
    void actionCorpusRoutesAtLeastNinetyFivePercentExactly() {
        List<RouteCase> cases = List.of(
                expected("帮我查最新新闻", "web.read"),
                expected("访问官网查看公告", "web.read"),
                expected("打开网页并填写表单", "web.interact"),
                expected("读取当前项目源码", "files.read"),
                expected("请检查我未提交的更改", "files.read"),
                expected("review the unstaged changes", "files.read"),
                expected("修改当前项目文件", "files.read", "files.write"),
                expected("编译当前项目", "code.execute"),
                expected("运行测试", "code.execute"),
                expected("执行终端命令", "command"),
                expected("获取系统信息", "system.info"),
                expected("操作桌面窗口", "desktop"),
                expected("查看未读邮件", "email.read"),
                expected("发送邮件给李四", "email.send"),
                expected("查询知识库", "knowledge.read"),
                expected("导入知识库", "knowledge.manage"),
                expected("查看定时任务", "schedule.read"),
                expected("创建每天九点的定时任务", "schedule.manage"),
                expected("查看任务状态", "task.read"),
                expected("暂停任务", "task.manage"),
                expected("调用 MCP 工具", "mcp.call"),
                expected("配置 MCP 服务器", "mcp.manage"),
                expected("创建技能", "skill.manage"),
                expected("发送飞书通知", "notification"),
                expected("图片文字识别", "media"),
                expected("调用插件工具", "plugin"));
        long exact = cases.stream().filter(test ->
                route(test.prompt()).bundleIds().equals(test.bundles())).count();
        assertTrue((double) exact / cases.size() >= 0.95,
                () -> "exact routes=" + exact + "/" + cases.size());
    }

    @Test
    void attachmentAndOverBroadRequestsStayControlled() {
        File attachment = new File("pom.xml").getAbsoluteFile();
        for (String prompt : List.of(
                "分析这个附件", "总结这个附件", "概括这份文档", "翻译这个 PDF",
                "比较附件中的两个方案", "这个附件讲了什么",
                "summarize the attached file", "translate this attachment",
                "compare the proposals in this attachment",
                "what does this attachment say")) {
            ToolExposureDecision attached = router.route(new ConversationRequest(
                    prompt, List.of(attachment)));
            assertTrue(attached.bundleIds().contains("files.read"), prompt);
        }

        for (String prompt : List.of("什么是摘要？", "how to summarize a document?")) {
            assertTrue(route(prompt).bundleIds().isEmpty(), prompt);
        }

        ToolExposureDecision broad = route(
                "读取项目文件、发送邮件、创建定时任务并操作桌面窗口");
        assertTrue(broad.bundleIds().isEmpty());
        assertEquals(java.util.Set.of("ask_user_clarification", "skill_read"),
                broad.allowedTools());
    }

    @Test
    void disabledSwitchRestoresLegacyAllTools() {
        ToolExposureDecision decision = new ToolIntentRouter(false)
                .route(ConversationRequest.ofText("什么是 Java"));
        assertTrue(decision.legacyAll());
        assertTrue(decision.allowedTools().isEmpty());
    }

    @Test
    void shortConfirmationInheritsOnlyTheAdjacentConcreteProposal() {
        ToolExposureDecision confirmed = routeWithHistory("好的！", List.of(
                user("当前项目测试失败"),
                assistant("我可以帮你修复当前项目代码并运行测试，要我继续吗？")));

        assertEquals(java.util.Set.of("files.read", "files.write", "code.execute"),
                confirmed.bundleIds());
        assertTrue(confirmed.allowedTools().size() <= ToolBundleCatalog.MAX_TOOLS);

        ToolExposureDecision explicit = routeWithHistory("发送邮件给张三", List.of(
                user("当前项目测试失败"),
                assistant("我可以帮你修复当前项目代码，要我继续吗？")));
        assertEquals(java.util.Set.of("email.send"), explicit.bundleIds());
    }

    @Test
    void denialStaleContextAndNonConfirmationDoNotInheritTools() {
        List<ConversationMessage> proposal = List.of(
                user("查看未读邮件"),
                assistant("我可以读取你的未读邮件，要我继续吗？"));
        for (String denial : List.of("不用了", "不行", "nope", "算了")) {
            assertTrue(routeWithHistory(denial, proposal).bundleIds().isEmpty(), denial);
        }
        assertTrue(routeWithHistory("我很好奇", proposal).bundleIds().isEmpty());
        assertTrue(routeWithHistory("好的", List.of(
                assistant("我可以读取未读邮件，要我继续吗？"),
                user("中间插入的新问题"))).bundleIds().isEmpty());
        assertTrue(routeWithHistory("好的", List.of(
                user("查看未读邮件"),
                assistant("未读邮件通常会显示在收件箱顶部。"))).bundleIds().isEmpty());
    }

    @Test
    void overBroadConfirmedProposalKeepsTheExistingToolCap() {
        ToolExposureDecision decision = routeWithHistory("好", List.of(
                user("请帮我处理这些事情"),
                assistant("我可以读取项目文件、发送邮件、创建定时任务并操作桌面窗口，要我执行吗？")));

        assertTrue(decision.bundleIds().isEmpty());
        assertEquals(java.util.Set.of("ask_user_clarification", "skill_read"),
                decision.allowedTools());
    }

    private ToolExposureDecision route(String prompt) {
        return router.route(ConversationRequest.ofText(prompt));
    }

    private ToolExposureDecision routeWithHistory(
            String prompt, List<ConversationMessage> priorMessages) {
        return router.route(new ConversationRequest(
                prompt, List.of(), "session", ConversationOptions.DEFAULT, priorMessages));
    }

    private static ConversationMessage user(String content) {
        return new ConversationMessage(ConversationMessage.Role.USER, content);
    }

    private static ConversationMessage assistant(String content) {
        return new ConversationMessage(ConversationMessage.Role.ASSISTANT, content);
    }

    private void assertBundle(String prompt, String... expected) {
        ToolExposureDecision decision = route(prompt);
        assertEquals(java.util.Set.of(expected), decision.bundleIds(), prompt);
        assertTrue(decision.allowedTools().contains("skill_read"));
        assertTrue(decision.allowedTools().contains("ask_user_clarification"));
        assertTrue(decision.allowedTools().size() <= ToolBundleCatalog.MAX_TOOLS, prompt);
    }

    private static RouteCase expected(String prompt, String... bundles) {
        return new RouteCase(prompt, java.util.Set.of(bundles));
    }

    private record RouteCase(String prompt, java.util.Set<String> bundles) { }
}
