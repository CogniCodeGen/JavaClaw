package com.javaclaw.application.agent;

import com.javaclaw.api.conversation.ConversationRequest;
import com.javaclaw.api.conversation.ConversationMessage;
import com.javaclaw.config.AgentConfig;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Deterministic, zero-model-call routing for interactive chat and plan turns. */
public final class ToolIntentRouter {
    private final AgentConfig settings;
    private final Boolean fixedEnabled;

    public ToolIntentRouter(AgentConfig settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.fixedEnabled = null;
    }

    /** Deterministic constructor for tests and embedded callers without a settings store. */
    public ToolIntentRouter(boolean enabled) {
        this.settings = null;
        this.fixedEnabled = enabled;
    }

    public ToolExposureDecision route(ConversationRequest request) {
        if (!(fixedEnabled == null ? settings.isToolRoutingEnabled() : fixedEnabled)) {
            return ToolExposureDecision.legacyAll("tool routing disabled");
        }
        ToolExposureDecision direct = routeText(
                request.userInput(), !request.attachments().isEmpty());
        String current = normalize(request.userInput());
        if (!direct.bundleIds().isEmpty()
                || !isShortContinuation(current)
                || containsNegative(current)) {
            return direct;
        }
        ConversationMessage[] pair = immediatePriorPair(request);
        if (pair == null || !isConcreteAssistantProposal(pair[1].content())) {
            return ToolBundleCatalog.base("ambiguous continuation without an adjacent proposal");
        }
        ToolExposureDecision inherited = routeText(
                pair[0].content() + "\n" + pair[1].content(), false);
        return inherited.bundleIds().isEmpty()
                ? ToolBundleCatalog.base("adjacent proposal did not resolve a safe tool domain")
                : new ToolExposureDecision(
                        inherited.legacyAll(), inherited.bundleIds(), inherited.allowedGroups(),
                        inherited.allowedTools(), "inherited adjacent confirmed action");
    }

    private static ToolExposureDecision routeText(String input, boolean attachments) {
        String text = normalize(input);
        LinkedHashSet<String> bundles = new LinkedHashSet<>();

        boolean explanatory = containsAny(text,
                "如何", "怎么", "怎样", "为什么", "什么是", "解释", "介绍", "教程", "原理",
                "区别", "how to", "what is", "explain", "why ");
        boolean explicitKnowledgeRead = containsAny(text,
                "知识库搜索", "搜索知识库", "查询知识库", "查知识库", "根据知识库", "从知识库",
                "搜索一下知识库", "查询一下知识库", "查一下知识库", "知识库中搜索", "知识库里搜索",
                "知识库中查询", "知识库里查询", "在知识库中查", "在知识库里查",
                "项目文档中", "根据项目文档", "从项目文档", "查询项目文档", "搜索项目文档", "查项目文档",
                "search the knowledge base", "query the knowledge base", "according to the knowledge base",
                "look up in the knowledge base", "in the project documentation", "from the project documentation");
        boolean actionAlongsideExplanation = containsAny(text,
                "顺便", "并帮我", "同时帮我", "然后帮我",
                "并发送", "并运行", "并执行", "并读取", "并打开", "并搜索", "并检查",
                "并修复", "并修改", "并编辑", "并实现", "并重构",
                "同时发送", "同时运行", "同时执行", "同时读取", "同时打开", "同时搜索", "同时检查",
                "同时修复", "同时修改", "同时编辑", "同时实现", "同时重构",
                "然后修复", "然后修改", "然后编辑", "然后实现", "然后重构",
                "and also ", "then also ", "and send ", "and run ", "and execute ",
                "and read ", "and open ", "and search ", "and check ",
                "and fix ", "and edit ", "and modify ", "and implement ",
                "then fix ", "then edit ", "then modify ", "then implement ");
        // Knowledge questions remain tool-free even if the subject contains an action verb
        // (for example, “如何发送邮件” or “what is MCP”). Only a distinct second action or an
        // attached artifact overrides the informational default; politeness words do not.
        if (explanatory && !explicitKnowledgeRead
                && !actionAlongsideExplanation && !attachments) {
            return ToolBundleCatalog.base("informational request");
        }
        boolean workspace = containsAny(text,
                "当前项目", "这个项目", "项目文件", "仓库", "代码库", "源码", "文件", "附件", "目录",
                "路径", "bug", "报错", "测试", "构建", "编译", "repository", "repo", "file",
                "未提交", "未暂存", "已暂存", "工作区变更", "工作区更改", "代码变更", "代码更改",
                "当前变更", "当前更改", "git status", "git diff", "代码审查", "code review",
                "uncommitted", "unstaged", "staged changes", "working tree", "working copy");
        boolean attachmentReadIntent = attachments && containsAny(text,
                "总结", "概括", "摘要", "归纳", "翻译", "比较", "对比", "提炼", "梳理",
                "附件讲了什么", "附件说了什么", "这份文档讲什么", "这个文档讲什么",
                "summarize", "summary", "translate", "compare", "outline", "review",
                "extract", "what does this attachment say", "what is in this attachment");

        boolean realtimeWeb = containsAny(text,
                "最新", "新闻", "实时", "联网", "搜索网络", "天气", "行情");
        boolean explicitWebAction = containsAny(text,
                "打开网页", "访问网页", "访问网站", "打开官网", "访问官网", "访问 http", "访问 www", "浏览网站",
                "搜索网页", "网页搜索", "操作网页", "点击", "填写", "表单", "上传", "登录",
                "打开浏览器", "官网查看", "open website", "open web page", "visit website",
                "browse web", "search web", "search the web");
        if (realtimeWeb || explicitWebAction) {
            bundles.add(containsAny(text, "点击", "填写", "表单", "上传", "网页操作", "登录")
                    ? "web.interact" : "web.read");
        }
        if (containsAny(text, "站点凭证", "站点凭据", "网站账号", "保存会话", "cookie",
                "切换账号", "登录会话", "mfa", "2fa")) {
            bundles.add("web.session");
        }
        if (attachmentReadIntent
                || (attachments && containsAny(text, "读", "看", "分析", "检查", "识别", "提取"))
                || (workspace && containsAny(text, "读", "查看", "检查", "分析", "搜索", "查找",
                "审查", "review", "grep", "diff", "状态", "修改", "编辑", "修复", "重构", "实现",
                "read", "inspect", "check", "analyze", "search", "find",
                "fix", "edit", "modify", "refactor", "implement"))) {
            bundles.add("files.read");
        }
        if (workspace && containsAny(text, "修改", "编辑", "修复", "重构", "实现", "写入",
                "创建文件", "删除文件", "提交代码", "patch",
                "fix", "edit", "modify", "refactor", "implement", "write")) {
            bundles.add("files.write");
        }
        if (containsAny(text, "运行测试", "执行测试", "编译", "构建", "mvn test",
                "gradle", "npm test", "jshell", "run test", "execute test",
                "build project", "build the project", "compile project",
                "compile the project")) {
            bundles.add("code.execute");
        }
        if (containsAny(text, "执行命令", "运行命令", "终端", "shell", "命令行", "脚本运行")) {
            bundles.add("command");
        }
        if (containsAny(text, "当前时间", "现在几点", "今天几号", "系统信息", "cpu", "内存信息",
                "磁盘信息", "系统截图")) {
            bundles.add("system.info");
        }
        if (containsAny(text, "桌面", "窗口", "应用界面", "启动应用", "操作应用", "鼠标", "键盘")) {
            bundles.add("desktop");
        }
        if (containsAny(text, "收件箱", "未读邮件", "读取邮件", "查看邮件", "搜索邮件")) {
            bundles.add("email.read");
        }
        if (containsAny(text, "发送邮件", "发邮件", "回复邮件", "邮件给", "send email")) {
            bundles.add("email.send");
        }
        if (explicitKnowledgeRead || containsAny(text, "知识库列表")) {
            bundles.add("knowledge.read");
        }
        if (containsAny(text, "导入知识库", "删除知识库", "清空知识库", "添加到知识库")) {
            bundles.add("knowledge.manage");
        }
        boolean scheduleObject = containsAny(text,
                "定时任务", "计划任务", "提醒", "cron", "scheduled task", "schedule", "reminder");
        if (scheduleObject && containsAny(text,
                "列表", "查看", "详情", "状态", "list", "show", "view", "status")) {
            bundles.add("schedule.read");
        }
        if (scheduleObject && containsAny(text,
                "创建", "新增", "每天", "每隔", "周期执行", "立即运行", "停用", "删除",
                "修改", "编辑", "create", "add", "every day", "interval", "run now",
                "disable", "delete", "remove", "edit", "update")) {
            bundles.add("schedule.manage");
        }
        if (containsAny(text, "任务状态", "托管任务列表", "检查任务")) {
            bundles.add("task.read");
        }
        if (containsAny(text, "创建任务", "创建托管", "暂停任务", "恢复任务", "取消任务")) {
            bundles.add("task.manage");
        }
        if (text.contains("mcp")) {
            bundles.add(containsAny(text, "配置", "新增", "添加", "删除", "启用", "停用", "header")
                    ? "mcp.manage" : "mcp.call");
        }
        if (containsAny(text, "创建技能", "修改技能", "编辑技能", "删除技能", "沉淀技能",
                "skill_create", "skill_patch")) {
            bundles.add("skill.manage");
        }
        if (containsAny(text, "发送通知", "钉钉", "企业微信", "飞书", "webhook 通知")) {
            bundles.add("notification");
        }
        if ((attachments && containsAny(text, "ocr", "识别文字", "提取文字", "查看图片"))
                || containsAny(text, "打开图片", "查看图片", "图片文字识别", "pdf 识别")) {
            bundles.add("media");
        }
        if (containsAny(text, "插件工具", "调用插件", "plugin tool")) {
            bundles.add("plugin");
        }

        if (bundles.isEmpty()) {
            String reason = explanatory
                    ? "informational request" : "no explicit external action";
            return ToolBundleCatalog.base(reason);
        }
        return ToolBundleCatalog.select(bundles, "matched explicit action intent");
    }

    private static ConversationMessage[] immediatePriorPair(ConversationRequest request) {
        var prior = request.priorMessages();
        if (prior.size() < 2) return null;
        ConversationMessage user = prior.get(prior.size() - 2);
        ConversationMessage assistant = prior.get(prior.size() - 1);
        if (user.role() != ConversationMessage.Role.USER
                || assistant.role() != ConversationMessage.Role.ASSISTANT
                || user.content().isBlank() || assistant.content().isBlank()) {
            return null;
        }
        return new ConversationMessage[] { user, assistant };
    }

    private static boolean isShortContinuation(String text) {
        if (text.isBlank() || text.codePointCount(0, text.length()) > 32) return false;
        String confirmation = text.replaceFirst("[\\p{Punct}\\p{IsPunctuation}\\s]+$", "");
        return Set.of(
                "好", "好的", "好吧", "可以", "行", "是的", "没问题", "执行吧", "继续吧",
                "继续", "照做", "那就做", "那就执行", "开始吧", "就这样做",
                "yes", "ok", "okay", "please do", "do it", "go ahead", "proceed",
                "continue", "sounds good").contains(confirmation);
    }

    private static boolean containsNegative(String text) {
        return containsAny(text,
                "不要", "不用", "不行", "不好", "别", "否", "取消", "停止", "算了", "不执行", "不继续",
                " no", "no ", "don't", "do not", "cancel", "stop", "never mind", "nope")
                || text.equals("不") || text.equals("no");
    }

    private static boolean isConcreteAssistantProposal(String value) {
        String text = normalize(value);
        boolean proposal = containsAny(text,
                "要我", "是否需要我", "需要我", "我可以", "可以帮你", "让我",
                "shall i", "should i", "would you like me", "do you want me", "want me to");
        boolean action = containsAny(text,
                "修复", "修改", "编辑", "执行", "运行", "发送", "删除", "创建", "打开",
                "读取", "搜索", "提交", "应用", "继续", "重构", "实现",
                "fix", "edit", "modify", "execute", "run", "send", "delete", "create",
                "open", "read", "search", "commit", "apply", "continue", "refactor",
                "implement");
        return proposal && action;
    }

    private static String normalize(String value) {
        return (value == null ? "" : value).strip().toLowerCase(Locale.ROOT);
    }

    private static boolean containsAny(String text, String... values) {
        for (String value : values) {
            if (text.contains(value)) return true;
        }
        return false;
    }
}
