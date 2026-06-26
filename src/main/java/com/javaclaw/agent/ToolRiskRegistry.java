package com.javaclaw.agent;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 工具风险等级注册表
 *
 * <p>维护工具名 → {@link ToolRiskLevel} 映射。未注册的工具不触发确认。</p>
 *
 * <p>{@code cmd_execute} 以 {@code CONFIRM} 等级登记：在托管任务中由
 * {@link ToolConfirmationManager} 统一确认（以便触发 PlannedAction 的免确认匹配）；
 * 非托管场景仍走 {@code CommandLineTools} 自带的白名单路径。</p>
 *
 * <p>登记原则：
 * <ul>
 *   <li>NOTIFY — 高频交互且单步影响有限的写操作（如浏览器点击、滚动、Tab 切换、外发通知）。
 *       仅以非阻塞 Toast 通知，不打断流程。</li>
 *   <li>CONFIRM — 单次即可造成可见副作用、需要用户确认的操作（如发邮件、文件写、JS 执行、
 *       Cookie 写、上传、密码字段填充）。弹窗，可"本次允许 / 永久允许 / 拒绝"。</li>
 *   <li>DOUBLE_CONFIRM — 高破坏性且难以撤销的操作（如文件删除）。要求用户键入"确认"。</li>
 * </ul>
 */
public final class ToolRiskRegistry {

    private static final Map<String, ToolRiskLevel> LEVELS;
    private static final Map<String, String> LABELS;

    static {
        Map<String, ToolRiskLevel> levels = new HashMap<>();
        Map<String, String> labels = new LinkedHashMap<>();

        // ==================== 邮件 ====================
        put(levels, labels, "email_send", ToolRiskLevel.CONFIRM, "发送邮件");
        put(levels, labels, "email_send_with_cc", ToolRiskLevel.CONFIRM, "发送邮件（带抄送）");
        put(levels, labels, "email_reply", ToolRiskLevel.CONFIRM, "回复邮件");

        // ==================== 系统文件 / 命令 ====================
        put(levels, labels, "sys_file_write", ToolRiskLevel.CONFIRM, "文件写入/覆盖");
        put(levels, labels, "sys_file_move", ToolRiskLevel.CONFIRM, "文件移动/重命名");
        put(levels, labels, "sys_file_copy", ToolRiskLevel.CONFIRM, "文件复制");
        put(levels, labels, "sys_file_delete", ToolRiskLevel.DOUBLE_CONFIRM, "文件删除");
        put(levels, labels, "sys_file_mkdir", ToolRiskLevel.NOTIFY, "创建目录");
        put(levels, labels, "cmd_execute", ToolRiskLevel.CONFIRM, "命令行执行");

        // ==================== 系统鼠键 / 截图（NOTIFY） ====================
        put(levels, labels, "sys_screenshot", ToolRiskLevel.NOTIFY, "系统截图");
        put(levels, labels, "sys_mouse_move", ToolRiskLevel.NOTIFY, "系统鼠标移动");
        put(levels, labels, "sys_mouse_click", ToolRiskLevel.NOTIFY, "系统鼠标点击");
        put(levels, labels, "sys_mouse_click_at", ToolRiskLevel.NOTIFY, "系统鼠标坐标点击");
        put(levels, labels, "sys_mouse_scroll", ToolRiskLevel.NOTIFY, "系统鼠标滚动");
        put(levels, labels, "sys_key_type", ToolRiskLevel.NOTIFY, "系统键盘输入");
        put(levels, labels, "sys_key_press", ToolRiskLevel.NOTIFY, "系统键盘按键");
        put(levels, labels, "sys_key_combo", ToolRiskLevel.NOTIFY, "系统键盘组合键");

        // ==================== 桌面自动化（操作其他软件） ====================
        // 启动外部程序可见副作用较大，上 CONFIRM；激活/截图/键鼠属高频交互，沿用系统鼠键约定走 NOTIFY。
        // desktop_probe / desktop_list_windows 为只读探测，不登记（不触发确认）。
        put(levels, labels, "desktop_launch", ToolRiskLevel.CONFIRM, "启动外部程序");
        put(levels, labels, "desktop_activate", ToolRiskLevel.NOTIFY, "激活窗口");
        put(levels, labels, "desktop_capture", ToolRiskLevel.NOTIFY, "桌面截图");
        put(levels, labels, "desktop_inspect", ToolRiskLevel.NOTIFY, "检视窗口元素");
        put(levels, labels, "desktop_click", ToolRiskLevel.NOTIFY, "桌面鼠标点击");
        put(levels, labels, "desktop_click_ref", ToolRiskLevel.NOTIFY, "按编号点击元素");
        put(levels, labels, "desktop_type", ToolRiskLevel.NOTIFY, "桌面键盘输入");
        put(levels, labels, "desktop_type_ref", ToolRiskLevel.NOTIFY, "按编号输入文本");
        put(levels, labels, "desktop_key", ToolRiskLevel.NOTIFY, "桌面按键");

        // ==================== 浏览器 — 导航/Tab/视口（NOTIFY） ====================
        put(levels, labels, "web_navigate", ToolRiskLevel.NOTIFY, "浏览器导航");
        put(levels, labels, "web_go_back", ToolRiskLevel.NOTIFY, "浏览器后退");
        put(levels, labels, "web_go_forward", ToolRiskLevel.NOTIFY, "浏览器前进");
        put(levels, labels, "web_reload", ToolRiskLevel.NOTIFY, "浏览器刷新");
        put(levels, labels, "web_tab_new", ToolRiskLevel.NOTIFY, "新建浏览器 Tab");
        put(levels, labels, "web_tab_close", ToolRiskLevel.NOTIFY, "关闭浏览器 Tab");
        put(levels, labels, "web_tab_switch", ToolRiskLevel.NOTIFY, "切换浏览器 Tab");
        put(levels, labels, "web_set_viewport", ToolRiskLevel.NOTIFY, "设置浏览器视口");

        // ==================== 浏览器 — 元素交互（NOTIFY） ====================
        put(levels, labels, "web_click", ToolRiskLevel.NOTIFY, "浏览器点击元素");
        put(levels, labels, "web_dblclick", ToolRiskLevel.NOTIFY, "浏览器双击元素");
        put(levels, labels, "web_fill", ToolRiskLevel.NOTIFY, "浏览器输入框填充");
        put(levels, labels, "web_type", ToolRiskLevel.NOTIFY, "浏览器键入文本");
        put(levels, labels, "web_hover", ToolRiskLevel.NOTIFY, "浏览器悬停");
        put(levels, labels, "web_select", ToolRiskLevel.NOTIFY, "浏览器下拉选择");
        put(levels, labels, "web_check", ToolRiskLevel.NOTIFY, "浏览器复选框勾选");
        put(levels, labels, "web_focus", ToolRiskLevel.NOTIFY, "浏览器元素聚焦");
        put(levels, labels, "web_drag", ToolRiskLevel.NOTIFY, "浏览器拖拽");
        put(levels, labels, "web_press_key", ToolRiskLevel.NOTIFY, "浏览器键盘按键");
        put(levels, labels, "web_scroll", ToolRiskLevel.NOTIFY, "浏览器滚动");
        put(levels, labels, "web_scroll_to_element", ToolRiskLevel.NOTIFY, "浏览器滚动到元素");
        put(levels, labels, "web_mouse_move", ToolRiskLevel.NOTIFY, "浏览器鼠标移动");
        put(levels, labels, "web_mouse_click_at", ToolRiskLevel.NOTIFY, "浏览器鼠标坐标点击");

        // ==================== 浏览器 — 高风险（CONFIRM） ====================
        put(levels, labels, "web_eval_js", ToolRiskLevel.CONFIRM, "浏览器执行 JS 代码");
        put(levels, labels, "web_upload", ToolRiskLevel.CONFIRM, "浏览器文件上传");
        put(levels, labels, "web_cookie_set", ToolRiskLevel.CONFIRM, "浏览器 Cookie 写入");
        put(levels, labels, "web_cookie_clear", ToolRiskLevel.CONFIRM, "浏览器 Cookie 清除");
        put(levels, labels, "web_save_pdf", ToolRiskLevel.CONFIRM, "浏览器页面保存为 PDF");
        put(levels, labels, "web_dialog_handle", ToolRiskLevel.CONFIRM, "浏览器原生对话框处理");
        put(levels, labels, "site_login_now", ToolRiskLevel.CONFIRM, "站点自动登录");
        put(levels, labels, "site_fill_password", ToolRiskLevel.CONFIRM, "站点密码填充");
        put(levels, labels, "site_save_session", ToolRiskLevel.CONFIRM, "站点会话保存");
        put(levels, labels, "site_clear_session", ToolRiskLevel.CONFIRM, "站点会话清除");

        // ==================== 技能自管理（skill_manage 工具集） ====================
        // suggest 模式本身已有提案审阅闸门，故写入类仅 NOTIFY（auto 模式 Toast 告知）；删除类破坏性更高，上 CONFIRM
        put(levels, labels, "skill_create", ToolRiskLevel.NOTIFY, "创建技能");
        put(levels, labels, "skill_patch", ToolRiskLevel.NOTIFY, "修补技能");
        put(levels, labels, "skill_edit", ToolRiskLevel.NOTIFY, "重写技能");
        put(levels, labels, "skill_write_file", ToolRiskLevel.NOTIFY, "写入技能支持文件");
        put(levels, labels, "skill_delete", ToolRiskLevel.CONFIRM, "删除技能");
        put(levels, labels, "skill_remove_file", ToolRiskLevel.CONFIRM, "删除技能支持文件");

        // ==================== JShell 执行（CONFIRM） ====================
        // jshell_run_script 不可放宽：agent 可经 skill_write_file（NOTIFY）写入脚本再执行，
        // 若不设确认将形成无人工闸门的任意代码执行链
        put(levels, labels, "jshell_exec", ToolRiskLevel.CONFIRM, "执行 Java 代码（JShell）");
        put(levels, labels, "jshell_run_script", ToolRiskLevel.CONFIRM, "运行技能 Java 脚本");

        // ==================== 长任务 / 定时任务管理 ====================
        // 创建长任务、定时任务会反复自主消耗 token，需人工确认。查询/控制类（list/status/pause/disable/delete 等）
        // 不在此登记，由工具自身直接执行不阻塞（尤其定时任务后台线程的自停调用绝不能弹窗等待）。
        put(levels, labels, "task_create", ToolRiskLevel.CONFIRM, "创建长任务");
        put(levels, labels, "schedule_create", ToolRiskLevel.CONFIRM, "创建定时任务");

        // ==================== 通知（NOTIFY） ====================
        put(levels, labels, "notify_send", ToolRiskLevel.NOTIFY, "外发通知（默认渠道）");
        put(levels, labels, "notify_dingtalk", ToolRiskLevel.NOTIFY, "外发钉钉通知");
        put(levels, labels, "notify_wechat", ToolRiskLevel.NOTIFY, "外发企业微信通知");
        put(levels, labels, "notify_feishu", ToolRiskLevel.NOTIFY, "外发飞书通知");
        put(levels, labels, "notify_email", ToolRiskLevel.NOTIFY, "外发邮件通知");
        put(levels, labels, "notify_custom_webhook", ToolRiskLevel.NOTIFY, "外发自定义 Webhook");

        LEVELS = Collections.unmodifiableMap(levels);
        LABELS = Collections.unmodifiableMap(labels);
    }

    private static void put(Map<String, ToolRiskLevel> levels, Map<String, String> labels,
                            String tool, ToolRiskLevel level, String label) {
        levels.put(tool, level);
        labels.put(tool, label);
    }

    /**
     * "可按目录限定免确认"的工具集合
     *
     * <p>UI 用一个目录选择器统一管理：只要工具调用的描述文本里出现该目录（作为子串），
     * 即视为在范围内而放行。包含文件读写工具，以及 cmd_execute（命令里常含目录路径，
     * 例如 {@code cat /path/to/file}，命中后可跳过确认）。</p>
     */
    private static final Set<String> DIR_SCOPED_TOOLS = Set.of(
            "sys_file_write", "sys_file_move", "sys_file_copy", "sys_file_delete", "cmd_execute"
    );

    private ToolRiskRegistry() {}

    /**
     * 查询工具的风险等级
     *
     * @return 对应等级；工具未注册时返回 {@code null}（调用方可跳过确认）
     */
    public static ToolRiskLevel levelOf(String toolName) {
        return LEVELS.get(toolName);
    }

    /** 工具是否受管（需确认或通知） */
    public static boolean isManaged(String toolName) {
        return LEVELS.containsKey(toolName);
    }

    /** 返回所有受管工具名集合，用于 UI 渲染"整工具免确认"列表 */
    public static Set<String> allManagedTools() {
        return LEVELS.keySet();
    }

    /** 返回工具的人类可读描述，未登记时返回工具名本身 */
    public static String labelOf(String toolName) {
        return LABELS.getOrDefault(toolName, toolName);
    }

    /** 该工具是否支持按目录限定免确认（UI 在一个目录选择器里统一管理） */
    public static boolean isDirScopedTool(String toolName) {
        return DIR_SCOPED_TOOLS.contains(toolName);
    }

    /** 返回所有支持"按目录限定免确认"的工具集合 */
    public static Set<String> dirScopedTools() {
        return DIR_SCOPED_TOOLS;
    }
}
