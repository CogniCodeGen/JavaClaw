package com.javaclaw.launcher;

import java.util.List;
import java.util.Set;

/** Desktop 黑盒验收目录。每项能力必须登记“入口—操作—状态变化—持久结果”，不能用页面文字存在代替交互完成。 */
final class DesktopFeatureCatalog {
    private static final List<Page> PAGES = List.of(
            new Page("Agent Studio", "profiles.prompt", Set.of("人设与业务约定", "提示词构成"), 1100, 760, 900, 680),
            new Page("记忆中心", "memory.lifecycle", Set.of("固定此条内容", "历史版本"), 1000, 680, 820, 600),
            new Page("知识中心", "knowledge.documents", Set.of("读取正文", "重建索引"), 1340, 864, 1040, 680),
            new Page("技能中心", "skills.bundle", Set.of("完整指令", "引用资源 / 脚本"), 920, 650, 780, 560),
            new Page("自动化与工作流", "automation.runtime", Set.of("步骤 / 迭代", "验收条件"), 1240, 800, 960, 680),
            new Page("定时任务", "schedule.lifecycle", Set.of("Cron 表达式", "下次触发"), 960, 680, 780, 560),
            new Page("插件", "plugins.lifecycle", Set.of("尚未安装插件"), 960, 680, 780, 560),
            new Page("MCP 连接", "mcp.lifecycle", Set.of("HTTPS 地址", "网络允许列表"), 960, 680, 780, 560),
            new Page("站点管理", "browser.sites", Set.of("起始来源", "其他明确允许的来源"), 1080, 740, 860, 620),
            new Page("项目约定", "agents.instructions", Set.of("正文只在模型上下文边界内读取", "内容摘要"), 1000, 700, 820, 600),
            new Page("协作与工作树恢复", "collaboration.worktrees", Set.of("没有待恢复的工作树"), 1100, 740, 900, 620),
            new Page("设置", "settings.shell", Set.of("当前主题", "外观与主题"), 1100, 760, 900, 680));

    private static final List<Feature> FEATURES = List.of(
            new Feature(
                    "startup.lifecycle",
                    "main",
                    "启动产品入口",
                    "完成 App Server initialize 握手",
                    "主窗口连接状态变为已连接",
                    "关闭窗口后子进程退出且不遗留运行目录锁"),
            new Feature(
                    "workspace.lifecycle",
                    "main",
                    "会话侧栏的工作区菜单",
                    "新建、重命名或删除工作区登记",
                    "选择器和 Thread 列表同步刷新；关联 Thread 拒绝删除时保留现场",
                    "重新读取 workspace/list 得到同一登记结果且项目文件未被删除"),
            new Feature(
                    "thread.lifecycle",
                    "main",
                    "会话侧栏和新对话快捷键",
                    "新建、重命名、分支、归档、恢复、删除或批量处理 Thread",
                    "按成功项和失败项逐项更新分组、选择与反馈",
                    "重新读取 thread/list 得到服务端确认后的状态"),
            new Feature(
                    "attachment.lifecycle",
                    "main",
                    "输入区附件按钮、拖放区和附件列表",
                    "并发上传多个附件、逐项移除或取消提交",
                    "显示逐项上传/失败状态；任一失败时不启动 Turn 并保留草稿",
                    "成功 Turn 的输入引用内容寻址附件；未引用上传得到尽力释放"),
            new Feature(
                    "transcript.lifecycle",
                    "main",
                    "当前 Thread 的 transcript",
                    "发送、追加、停止、审批、回答、复制、引用、导出或新分支重试",
                    "Turn 内用户、助手、执行、交互和错误 Item 独立变化且滚动策略稳定",
                    "恢复 thread/read 后持久 Item 替换流式占位且副作用不重复"),
            new Feature(
                    "profiles.prompt",
                    "Agent Studio",
                    "更多管理中的 Agent Studio",
                    "编辑身份、模型、提示词、工具权限或预算并保存",
                    "dirty、校验、保存、冲突和 Prompt 差异采用状态可见",
                    "重新读取 profile/list 得到已确认 revision"),
            new Feature(
                    "providers.cloud",
                    "设置",
                    "设置中的云模型分类",
                    "编辑 Provider 与替换凭据后保存",
                    "字段级校验和资源级提交反馈可见，已保存 Secret 只显示已配置",
                    "重新读取脱敏 Provider 配置，不回显 Secret"),
            new Feature(
                    "memory.lifecycle",
                    "记忆中心",
                    "侧栏记忆入口",
                    "搜索、编辑、固定、纠错、恢复版本或删除 Memory",
                    "列表统计、状态、详情和操作反馈同步更新",
                    "重新读取 Memory 及历史得到服务端确认版本"),
            new Feature(
                    "knowledge.documents",
                    "知识中心",
                    "侧栏知识入口",
                    "导入、检索测试、查看版本或重建知识源",
                    "generation、片段数、检索模式、索引时间和失败摘要更新",
                    "批量 stats/read 返回同一索引权威状态"),
            new Feature(
                    "skills.bundle",
                    "技能中心",
                    "侧栏技能入口",
                    "导入、编辑指令与资源、采纳提案、启停、导出或卸载",
                    "各操作独立反馈且 dirty 草稿不会被列表刷新覆盖",
                    "重新读取 Skill/Bundle 得到已确认版本"),
            new Feature(
                    "automation.runtime",
                    "自动化与工作流",
                    "侧栏自动化入口",
                    "编辑 Loop、Workflow 图或 SDD，并选择保存运行或运行已保存版本",
                    "图校验、预算、分段文档和运行时间线按目标资源更新",
                    "重新读取 AutomationDefinition 与执行 Items 得到已保存/运行结果"),
            new Feature(
                    "schedule.lifecycle",
                    "定时任务",
                    "侧栏定时任务入口",
                    "校验 Cron/时区、预览五次触发、保存、启停或立即运行",
                    "无效字段就地报错，SKIP 与运行结果不混淆",
                    "重新读取 Schedule 与历史得到服务端确认状态"),
            new Feature(
                    "plugins.lifecycle",
                    "插件",
                    "侧栏插件入口",
                    "选择 ZIP，审查签名/来源/权限后确认安装，或执行禁用/卸载",
                    "安装阶段、健康、隔离与危险确认独立反馈",
                    "重新读取 Plugin 4 清单得到安装和启用状态"),
            new Feature(
                    "mcp.lifecycle",
                    "MCP 连接",
                    "侧栏 MCP 入口和主窗口快捷键",
                    "分步配置 HTTPS、认证、OAuth，检查健康或发现工具",
                    "认证字段随模式变化且 stdio 声明保持只读",
                    "重新读取 MCP 配置、OAuth 和健康状态且 Secret 不回显"),
            new Feature(
                    "browser.sites",
                    "站点管理",
                    "更多管理中的站点管理",
                    "编辑站点、人工登录、凭据或会话并保存",
                    "加载、空、错误、冲突和危险操作状态保持当前 Thread 上下文",
                    "重新读取站点与脱敏凭据元数据得到服务端结果"),
            new Feature(
                    "network.authorization",
                    "站点管理",
                    "站点允许来源编辑区",
                    "审查并确认精确私网来源授权",
                    "未确认、越界或冲突时拒绝写入并保留草稿",
                    "重新读取站点只包含服务端接受的精确来源"),
            new Feature(
                    "agents.instructions",
                    "项目约定",
                    "更多管理中的项目约定",
                    "刷新并审查层级、覆盖、截断和快照元数据",
                    "加载/错误状态不以巨大可编辑 TextArea 冒充正文",
                    "后续 Turn 使用服务端解析的约定快照"),
            new Feature(
                    "collaboration.worktrees",
                    "协作与工作树恢复",
                    "更多管理中的工作树恢复",
                    "查看补丁、恢复、解决冲突或返回关联 Thread",
                    "目标资源操作中禁用且失败可重试，导航上下文不丢失",
                    "重新读取恢复列表得到服务端工作树状态"),
            new Feature("settings.shell", "设置", "标题栏设置按钮或快捷键", "切换九套主题并保存", "当前窗口立即应用且 dirty/保存反馈可见", "重新启动后读取同一主题标识"),
            new Feature(
                    "diagnostics.lifecycle",
                    "设置",
                    "设置中的连接诊断分类",
                    "刷新、复制或导出脱敏诊断",
                    "读取失败保留已有内容并提供重试",
                    "导出文件只包含服务端脱敏后的诊断快照"));

    private DesktopFeatureCatalog() {}

    static List<Page> pages() {
        return PAGES;
    }

    static List<Feature> features() {
        return FEATURES;
    }

    record Page(
            String title,
            String featureId,
            Set<String> requiredText,
            int preferredWidth,
            int preferredHeight,
            int minimumWidth,
            int minimumHeight) {
        Page {
            requiredText = Set.copyOf(requiredText);
            if (preferredWidth < minimumWidth || preferredHeight < minimumHeight) {
                throw new IllegalArgumentException("recommended page size must contain its minimum size");
            }
        }
    }

    /** 一个可审计的用户旅程，四段证据均不能为空。 */
    record Feature(String id, String page, String entry, String action, String stateChange, String persistentResult) {}
}
