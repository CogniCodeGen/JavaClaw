package com.javaclaw.application.agent;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Stable, reviewable bundles used by the deterministic interactive-chat router. */
public final class ToolBundleCatalog {
    public static final int MAX_BUNDLES = 3;
    public static final int MAX_TOOLS = 24;

    private static final Map<String, Bundle> BUNDLES = bundles();

    private ToolBundleCatalog() { }

    public static ToolExposureDecision select(Set<String> bundleIds, String reason) {
        LinkedHashSet<String> selected = new LinkedHashSet<>(bundleIds);
        if (selected.size() > MAX_BUNDLES) {
            return base("too many tool domains: " + selected);
        }
        LinkedHashSet<String> groups = new LinkedHashSet<>();
        LinkedHashSet<String> tools = new LinkedHashSet<>();
        for (String id : selected) {
            Bundle bundle = BUNDLES.get(id);
            if (bundle == null) return base("unknown tool bundle: " + id);
            groups.addAll(bundle.groups());
            tools.addAll(bundle.tools());
        }
        // The two resident progressive-disclosure tools are counted as model-visible schemas.
        if (tools.size() + 2 > MAX_TOOLS) {
            return base("tool schema limit exceeded: " + (tools.size() + 2));
        }
        return ToolExposureDecision.restricted(selected, groups, tools, reason);
    }

    public static ToolExposureDecision base(String reason) {
        return ToolExposureDecision.restricted(Set.of(), Set.of(), Set.of(), reason);
    }

    public static Set<String> ids() {
        return BUNDLES.keySet();
    }

    /** Fails startup when a routed schema name no longer exists in the actual host inventory. */
    public static void validateAgainst(Set<String> declaredToolNames) {
        Set<String> inventory = Set.copyOf(declaredToolNames);
        LinkedHashSet<String> missing = new LinkedHashSet<>();
        BUNDLES.values().forEach(bundle -> bundle.tools().stream()
                .filter(tool -> !inventory.contains(tool)).forEach(missing::add));
        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "tool bundles reference undeclared host tools: " + missing);
        }
    }

    private static Map<String, Bundle> bundles() {
        LinkedHashMap<String, Bundle> values = new LinkedHashMap<>();
        add(values, "web.read", Set.of("web"), List.of(
                "web_navigate", "web_snapshot", "web_get_text", "web_get_html",
                "web_get_attribute", "web_get_url", "web_get_title", "web_get_value",
                "web_get_count", "web_is_visible", "web_is_enabled", "web_is_checked",
                "web_wait_for_element", "web_wait_for_text", "web_wait_for_url",
                "web_go_back", "web_go_forward", "web_reload"));
        add(values, "web.interact", Set.of("web"), List.of(
                "web_navigate", "web_snapshot", "web_get_text", "web_get_url",
                "web_get_title", "web_click", "web_dblclick", "web_fill", "web_type",
                "web_press_key", "web_select", "web_check", "web_hover", "web_scroll",
                "web_wait_for_load", "web_wait_for_element", "web_wait_for_text",
                "web_upload", "web_dialog_handle", "web_eval_js"));
        add(values, "web.session", Set.of("web"), List.of(
                "web_tab_new", "web_tab_list", "web_tab_close", "web_tab_switch",
                "web_cookie_get", "web_cookie_set", "web_cookie_clear", "web_save_pdf",
                "site_select_account", "site_login_interactive", "site_login_now",
                "site_fill_password", "site_save_session", "site_clear_session",
                "site_credential_list", "site_credential_save",
                "site_credential_set_password_secure", "site_credential_delete"));
        add(values, "files.read", Set.of("coding", "system"), List.of(
                "code_read", "code_grep", "code_glob", "git_status", "git_diff", "git_log",
                "sys_file_list", "sys_file_read"));
        add(values, "files.write", Set.of("coding", "system"), List.of(
                "code_set_project_root", "code_edit", "code_insert", "git_commit",
                "sys_file_write", "sys_file_delete", "sys_file_copy", "sys_file_move",
                "sys_file_mkdir"));
        add(values, "code.execute", Set.of("coding"), List.of(
                "code_build", "code_test", "jshell_exec", "jshell_run_script"));
        add(values, "command", Set.of("command"), List.of(
                "cmd_execute", "cmd_session_open", "cmd_session_exec", "cmd_session_input",
                "cmd_session_read", "cmd_session_close", "cmd_session_list",
                "cmd_whitelist_list", "cmd_whitelist_add", "cmd_whitelist_remove"));
        add(values, "system.info", Set.of("system"), List.of(
                "sys_get_info", "sys_get_time", "sys_screenshot"));
        add(values, "desktop", Set.of("desktop"), List.of(
                "desktop_probe", "desktop_launch", "desktop_list_windows", "desktop_activate",
                "desktop_capture", "desktop_inspect", "desktop_click_ref", "desktop_type_ref",
                "desktop_click", "desktop_type", "desktop_key"));
        add(values, "email.read", Set.of("email"), List.of(
                "email_list_inbox", "email_list_unread", "email_read", "email_search"));
        add(values, "email.send", Set.of("email"), List.of(
                "email_send", "email_send_with_cc", "email_reply"));
        add(values, "knowledge.read", Set.of("knowledge"), List.of(
                "knowledge_list", "knowledge_search"));
        add(values, "knowledge.manage", Set.of("knowledge"), List.of(
                "knowledge_import_file", "knowledge_import_text", "knowledge_delete",
                "knowledge_clear"));
        add(values, "schedule.read", Set.of("schedule"), List.of(
                "schedule_list", "schedule_get"));
        add(values, "schedule.manage", Set.of("schedule"), List.of(
                "schedule_create", "schedule_disable", "schedule_delete", "schedule_run_now"));
        add(values, "task.read", Set.of("task_manage"), List.of(
                "task_list", "task_status", "inspect_list", "inspect_read"));
        add(values, "task.manage", Set.of("task_manage"), List.of(
                "task_create", "task_pause", "task_resume", "task_cancel",
                "inspect_compile", "inspect_run_smoke"));
        add(values, "mcp.call", Set.of("mcp"), List.of("mcp_list_tools", "mcp_call_tool"));
        add(values, "mcp.manage", Set.of("mcp"), List.of(
                "mcp_server_list", "mcp_server_add", "mcp_server_update",
                "mcp_server_set_enabled", "mcp_server_set_header_secure",
                "mcp_server_delete"));
        add(values, "skill.manage", Set.of("skill"), List.of(
                "skill_create", "skill_create_direct", "skill_patch", "skill_edit",
                "skill_delete", "skill_write_file", "skill_remove_file"));
        add(values, "notification", Set.of("notification"), List.of(
                "notify_send", "notify_dingtalk", "notify_wechat", "notify_feishu",
                "notify_email", "notify_custom_webhook", "notify_list_channels"));
        add(values, "media", Set.of("media"), List.of("view_image", "ocr_recognize"));
        add(values, "plugin", Set.of("plugins"), List.of(
                "plugin_list_tools", "plugin_call_tool"));
        return Map.copyOf(values);
    }

    private static void add(
            Map<String, Bundle> target, String id, Set<String> groups, List<String> tools) {
        target.put(id, new Bundle(id, groups, Set.copyOf(tools)));
    }

    private record Bundle(String id, Set<String> groups, Set<String> tools) { }
}
