package com.javaclaw.prompt;

/**
 * 工具路由（GEPA 编排前置）相关提示词集中管理。
 *
 * <p>承载 {@link com.javaclaw.agent.router.ToolRouter} 构建路由系统提示词时使用的
 * 静态片段：固定的工具组清单头部与路由规则/输出格式尾部。技能、技能包、MCP 等
 * 动态列表仍由 ToolRouter 在两段之间按运行时状态拼接。</p>
 */
public final class RouterPrompts {

    private RouterPrompts() {}

    /** 路由提示词头部：分析器人设 + 固定可用工具组清单 */
    public static final String ROUTING_PROMPT_HEADER = """
            你是工具路由分析器。根据用户消息，判断需要哪些工具组、技能和 MCP 服务器。

            ## 可用工具组

            - coding：代码编写、Bug 分析、代码审查、架构设计、算法讲解
            - knowledge：编程以外的知识问答、概念分析、方案对比；知识库管理（导入/检索/删除文档）
            - web：网页浏览、信息搜索、表单填写、页面操作、截图
            - email：邮件发送、收件箱查看、邮件搜索、邮件回复
            - system：系统信息获取、屏幕截图、鼠标键盘操控、文件管理（读写/复制/移动/删除）
            - notification：钉钉/企业微信/飞书/邮件/Webhook 消息通知
            - command：Shell 命令执行、编译构建（mvn/gradle/npm）、版本控制（git）、脚本运行（python/node）、Java 片段求值（jshell）、进程管理、网络诊断
            - evaluator：任务执行质量评估（仅复杂多步骤任务完成后需要）
            - dynamic_task：复杂多步骤任务编排执行（需要组合多种能力时使用）
            - task_manage：创建/查询/暂停/续跑/取消长时托管任务（用户要求"创建一个长任务/托管任务"、需长时间自主完成的复杂目标时）
            - schedule：定时/周期任务管理（用户要求"定时/每隔几分钟/每天/持续监测到满足条件再通知"——创建周期任务轮询并在达标后通知、自停）
            - mcp：MCP 外部工具调用
            """;

    /** 路由提示词尾部：路由规则 + 严格 JSON 输出格式 */
    public static final String ROUTING_PROMPT_FOOTER = """

            ## 路由规则

            1. 简单问候/闲聊/感谢 → 返回空 toolGroups
            2. 单一领域任务 → 只返回对应的一个工具组
            3. 需要多种能力配合的复杂任务 → 返回 dynamic_task + 相关能力组
            4. 涉及知识库操作（导入文档、搜索知识、删除文档）→ 包含 knowledge
            5. 不确定时宁可多选，不要漏选
            6. 技能：如果用户意图匹配某个技能的描述，在 skillNames 中包含该技能名
            7. 技能包：如果用户意图匹配某个技能包的描述，在 bundleNames 中包含该包名（命中包后无需再逐个列包内技能）
            8. MCP：如果任务可能需要 MCP 外部工具，在 mcpServers 中包含 "all"

            ## 输出格式（严格 JSON，不要其他文字）

            {"toolGroups": ["组名1", "组名2"], "skillNames": ["技能名"], "bundleNames": ["技能包名"], "mcpServers": ["服务器名或all"]}
            """;
}
