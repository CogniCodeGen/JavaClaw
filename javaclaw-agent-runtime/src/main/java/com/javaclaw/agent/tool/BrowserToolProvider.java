package com.javaclaw.agent.tool;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.javaclaw.agent.runtime.TurnExecutionContext;
import com.javaclaw.core.api.ThreadItem;
import com.javaclaw.core.api.ToolDescriptor;
import com.javaclaw.sandbox.api.SandboxPolicy;

/** 页面引用驱动的浏览器工具目录；每次操作走统一 Schema/审批/副作用凭据，Turn 退出释放浏览器。 */
public final class BrowserToolProvider implements ToolProvider {
    private static final String SESSION = "\"sessionId\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":100}";
    private static final String TAB = "\"tabId\":{\"type\":\"string\",\"maxLength\":100}";
    private static final String REF = "\"reference\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":100}";
    private final BrowserSessionGateway browser;
    private final SandboxPolicy ceiling;

    /** 注入受监督 Browser Gateway，工具不能访问 Playwright、SecretStore 或原始网络。 */
    public BrowserToolProvider(BrowserSessionGateway browser, SandboxPolicy ceiling) {
        this.browser = java.util.Objects.requireNonNull(browser);
        this.ceiling = java.util.Objects.requireNonNull(ceiling);
    }

    @Override
    public String id() {
        return "browser";
    }

    @Override
    public List<RegisteredTool> tools(TurnExecutionContext turn) {
        return registeredTools();
    }

    @Override
    public List<ToolDescriptor> catalog() {
        return registeredTools().stream().map(RegisteredTool::descriptor).toList();
    }

    private List<RegisteredTool> registeredTools() {
        var tools = new ArrayList<RegisteredTool>();
        tools.add(register("browser_sites", "列出当前工作区已启用站点，不读取凭据。", true, "", "[]", call -> {
            String result = browser.sites(call.call()).toString();
            return new ToolHandler.Result(
                    new ThreadItem.DynamicToolCall("browser_sites", Map.of("sites", result)), result);
        }));
        tools.add(register(
                "browser_open",
                "打开受隔离浏览器；siteId 使用已批准站点，省略时仅访问 URL 的公开源。",
                false,
                "\"url\":{\"type\":\"string\",\"maxLength\":8192},\"siteId\":{\"type\":\"string\",\"maxLength\":200}",
                "[]",
                call -> {
                    String url = call.arguments().path("url").asText("");
                    return result(browser.open(
                            call.call(),
                            call.arguments().path("siteId").asText(""),
                            url.isBlank() ? null : URI.create(url)));
                }));
        tools.add(
                operation("browser_page", "刷新当前页面的文本、元素引用、标签页和下载列表；不读取输入框值。", "snapshot", true, "", "[\"sessionId\"]"));
        tools.add(operation(
                "browser_navigate",
                "导航到已批准来源；URL 发生改变后重新获得页面引用。",
                "navigate",
                false,
                string("value", 8192),
                "[\"sessionId\",\"value\"]"));
        tools.add(operation(
                "browser_new_tab",
                "在同一隔离会话打开已批准来源的新标签页，最多八页。",
                "newTab",
                false,
                string("value", 8192),
                "[\"sessionId\",\"value\"]"));
        tools.add(operation(
                "browser_close_tab", "关闭指定标签页，不得关闭会话的最后一页。", "closeTab", true, "", "[\"sessionId\",\"tabId\"]"));
        tools.add(operation(
                "browser_click",
                "点击当前快照的元素引用；可能提交业务写入，必须按任务范围审批。",
                "click",
                false,
                REF,
                "[\"sessionId\",\"reference\"]"));
        tools.add(operation(
                "browser_fill",
                "填写非敏感值；密码必须改用 browser_fill_secret，页面引用过期必须刷新。",
                "fill",
                false,
                REF + "," + string("value", 16384),
                "[\"sessionId\",\"reference\",\"value\"]"));
        tools.add(operation(
                "browser_fill_secret",
                "使用站点专属 SecretRef 填充；模型看不到值，不得把凭据写入 value。",
                "fill",
                false,
                REF + "," + string("secretName", 80),
                "[\"sessionId\",\"reference\",\"secretName\"]"));
        tools.add(operation(
                "browser_select",
                "按当前引用选择下拉选项；不能提交任意 JavaScript。",
                "select",
                false,
                REF + "," + string("value", 500),
                "[\"sessionId\",\"reference\",\"value\"]"));
        tools.add(operation(
                "browser_press",
                "对引用发送 Enter/Tab/Escape/ArrowUp/ArrowDown/Space，提交动作仍需审批。",
                "press",
                false,
                REF + ",\"value\":{\"enum\":[\"Enter\",\"Tab\",\"Escape\",\"ArrowUp\",\"ArrowDown\",\"Space\"]}",
                "[\"sessionId\",\"reference\",\"value\"]"));
        tools.add(operation(
                "browser_upload",
                "向文件输入框上传已附加到本 Thread 的附件，单次最多 4 MiB；不能给本机路径。",
                "upload",
                false,
                REF + "," + string("attachmentSha256", 64),
                "[\"sessionId\",\"reference\",\"attachmentSha256\"]"));
        tools.add(operation(
                "browser_wait",
                "等待最多五秒并刷新页面快照，不自动重复任何业务动作。",
                "wait",
                true,
                "\"value\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":5000}",
                "[\"sessionId\",\"value\"]"));
        tools.add(
                operation("browser_screenshot", "生成屏蔽输入框的页面截图附件；不截图宿主桌面。", "screenshot", true, "", "[\"sessionId\"]"));
        tools.add(operation("browser_pdf", "将页面导出为有界 PDF 附件；不读取本机文件。", "pdf", true, "", "[\"sessionId\"]"));
        tools.add(operation(
                "browser_download_link",
                "从当前页面的链接引用经 Broker 下载有界附件；仅 GET，不点击或重新提交表单，不接受本机路径。",
                "downloadLink",
                true,
                REF,
                "[\"sessionId\",\"reference\"]"));
        tools.add(operation(
                "browser_download",
                "读取浏览器已完成的 Blob 下载并保存为内容寻址附件；HTTP 链接使用 browser_download_link，不接受下载路径。",
                "download",
                true,
                string("value", 100),
                "[\"sessionId\",\"value\"]"));
        tools.add(operation("browser_close", "关闭当前 Turn 的指定浏览器及其进程树，不自动保存登录状态。", "close", true, "", "[\"sessionId\"]"));
        return List.copyOf(tools);
    }

    @Override
    public Snapshot snapshot(TurnExecutionContext turn) {
        return new Snapshot(
                tools(turn), List.of(), () -> browser.closeTurn(turn.turn().id().value()));
    }

    private RegisteredTool operation(
            String name, String description, String operation, boolean readOnly, String properties, String required) {
        return register(
                name,
                description,
                readOnly,
                SESSION + "," + TAB + (properties.isBlank() ? "" : "," + properties),
                required,
                call -> result(browser.act(
                        call.call(),
                        call.arguments().path("sessionId").asText(),
                        new BrowserSessionGateway.Action(
                                operation,
                                call.arguments().path("tabId").asText(""),
                                call.arguments().path("reference").asText(""),
                                call.arguments().path("value").asText(""),
                                call.arguments().path("secretName").asText(""),
                                call.arguments().path("attachmentSha256").asText("")))));
    }

    private RegisteredTool register(
            String name,
            String description,
            boolean readOnly,
            String properties,
            String required,
            ToolHandler handler) {
        var tool = new RegisteredTool(
                new ToolDescriptor(
                        name,
                        description,
                        "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{" + properties
                                + "},\"required\":" + required + "}"),
                ToolOrigin.BUILTIN,
                readOnly ? ToolRisk.LOW : ToolRisk.HIGH,
                !readOnly,
                ceiling,
                handler);
        return readOnly ? tool.readOnly() : tool;
    }

    private static String string(String name, int maximum) {
        return "\"" + name + "\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":" + maximum + "}";
    }

    private static ToolHandler.Result result(BrowserSessionGateway.Result result) {
        String text = "sessionId=" + result.sessionId() + "\n" + result.text();
        if (result.artifact() != null) {
            String uri = "attachment:sha256:" + result.artifact().sha256();
            ThreadItem item = result.artifact().mediaType().startsWith("image/")
                    ? new ThreadItem.ImageView(uri, result.displayName())
                    : new ThreadItem.Artifact(
                            result.artifact().sha256(), "browserAttachment", result.displayName(), 1, uri, List.of());
            return new ToolHandler.Result(item, text + "\n" + uri);
        }
        return new ToolHandler.Result(
                new ThreadItem.DynamicToolCall(
                        "browser", Map.of("sessionId", result.sessionId(), "snapshot", result.text())),
                text);
    }
}
