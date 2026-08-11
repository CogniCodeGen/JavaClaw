package com.javaclaw.ui.javafx.settings;

import java.util.Locale;

/** 设置窗口中稳定的分组和分类标识，避免用展示文案承载页面路由。 */
public enum SettingsCategory {

    MODEL(Group.CORE, "模型配置",
            "api key base url provider openai anthropic ollama dashscope gemini 模型 思考 "
                    + "thinking 高级 http 超时 timeout 迭代 循环"),
    TIERED_MODEL(Group.CORE, "分级模型",
            "tier 分级 轻量 light 普通 normal 高性能 high 路由 routing 意图 intent 规划"),
    EMBEDDING(Group.CORE, "嵌入模型",
            "rag embedding 嵌入 向量 vector 检索 文档 knowledge 知识库 维度 dimension"),
    AGENT(Group.CORE, "智能体",
            "agent expert orchestrator iters 迭代 子智能体 编排"),

    GEPA(Group.INTELLIGENCE, "GEPA 能力",
            "gepa 自适应 规划 trajectory 目标"),
    SKILL_EVOLUTION(Group.INTELLIGENCE, "技能进化",
            "skill 技能 自学习 进化 沉淀 提案 hermes"),

    MCP(Group.INTEGRATION, "MCP 服务器",
            "mcp model context protocol server 服务器 claude desktop"),
    SITE(Group.INTEGRATION, "站点管理",
            "site 站点 网站 凭据 cookie 登录 自动登录 用户名 密码 password"),

    APPEARANCE(Group.APPEARANCE, "界面风格",
            "主题 theme 风格 外观 配色 深色 暗色 dark emerald midnight carbon sapphire "
                    + "ocean plum graphite terracotta honey 翡翠 午夜 碳黑 蓝宝石 海洋 梅紫 石墨 陶土 蜂蜜"),
    FONT(Group.APPEARANCE, "字体",
            "字体 font typeface sans mono 等宽 字号 密度 缩放 inter noto cascadia jetbrains 排版 对话"),

    GENERAL(Group.GENERAL, "通用设置",
            "托盘 tray 后台 常驻 最小化 关闭 minimize 窗口 退出 background 托管任务 风险 "
                    + "评估 自动放行 确认 目录 risk autoapprove 免确认"),
    TEST_DATA(Group.MAINTENANCE, "测试数据清理",
            "data junit test 测试 数据 临时目录 清理 storage maintenance"),

    EMAIL(Group.COMMUNICATION, "邮件配置",
            "smtp imap email mail 邮箱 发件 收件"),
    NOTIFICATION(Group.COMMUNICATION, "通知配置",
            "钉钉 dingtalk 企业微信 wework 飞书 lark webhook notification 通知");

    public enum Group {
        CORE("核心配置"),
        INTELLIGENCE("智能能力"),
        INTEGRATION("外部集成"),
        APPEARANCE("外观"),
        GENERAL("通用"),
        MAINTENANCE("系统维护"),
        COMMUNICATION("通信渠道");

        private final String displayName;

        Group(String displayName) {
            this.displayName = displayName;
        }

        public String displayName() {
            return displayName;
        }
    }

    private final Group group;
    private final String displayName;
    private final String searchIndex;

    SettingsCategory(Group group, String displayName, String keywords) {
        this.group = group;
        this.displayName = displayName;
        this.searchIndex = (displayName + " " + keywords).toLowerCase(Locale.ROOT);
    }

    public Group group() {
        return group;
    }

    public String displayName() {
        return displayName;
    }

    public boolean matches(String normalizedQuery) {
        return normalizedQuery == null || normalizedQuery.isBlank()
                || searchIndex.contains(normalizedQuery);
    }

    public static SettingsCategory named(String displayName) {
        if (displayName == null || displayName.isBlank()) return null;
        for (SettingsCategory category : values()) {
            if (category.displayName.equals(displayName)) return category;
        }
        return null;
    }
}
