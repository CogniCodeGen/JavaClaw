package com.javaclaw.desktop;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import javafx.scene.control.Dialog;
import javafx.scene.control.ListView;
import javafx.scene.control.PasswordField;
import javafx.scene.layout.BorderPane;

import com.javaclaw.sdk.model.McpServerInfo;
import com.javaclaw.sdk.model.McpSettingsInfo;

/** 原 MCP 设置的连接表单与授权入口；只支持固定新协议，密码不进入普通配置或诊断展示。 */
final class McpPane extends ManagedManagementPage {
    private final ListView<McpServerInfo> servers = ManagementForms.list(
            value -> value.name() + "\n" + DesktopPresentationMapper.status(value.state()) + " · "
                    + (value.enabled() ? "已启用" : "已停用"),
            "尚无 MCP 连接",
            "添加 HTTPS MCP，或安装声明 stdio MCP 的 Plugin 4.0。");
    private final BorderPane detail = new BorderPane();
    private long selection;

    McpPane(ManagementViewModel model) {
        super(model);
        setCenter(ManagementForms.split(servers, detail));
        setTop(ManagementForms.actions(
                ManagementForms.command(
                        "＋ HTTPS MCP",
                        UiActionKind.PRIMARY,
                        model,
                        () -> requestNavigation(() -> edit(
                                new McpServerInfo(
                                        "mcp_" + UUID.randomUUID().toString().substring(0, 8),
                                        null,
                                        "新连接",
                                        com.javaclaw.sdk.model.JsonDocument.EMPTY_OBJECT,
                                        false,
                                        "DRAFT",
                                        0,
                                        null),
                                new McpSettingsInfo(
                                        "http", "", Set.of(), "none", "", "", "", Set.of(), 30_000, 4_194_304, "")))),
                ManagementForms.command("刷新", UiActionKind.GHOST, model, () -> requestNavigation(this::reload)),
                ManagementForms.command("通信预授权…", model, () -> ToolAuthorizationPane.show(this, model))));
        guardSelection(servers, value -> {
            long accepted = ++selection;
            model.execute("读取 MCP 元数据", sdk -> sdk.extensions().readMcpSettings(value.id()), settings -> {
                if (accepted == selection) {
                    edit(value, settings);
                }
            });
        });
        detail.setCenter(
                ManagementForms.emptyState("⇄", "添加 MCP 连接", "邮件、通知等外部能力通过 MCP 配置。仅支持 2026-07-28，HTTP 只能经过网络 Broker。"));
        reload();
    }

    private void reload() {
        refreshing();
        String selectedId = servers.getSelectionModel().getSelectedItem() == null
                ? null
                : servers.getSelectionModel().getSelectedItem().id();
        model.execute(
                "读取 MCP",
                sdk -> sdk.extensions().listMcpServers(),
                values -> {
                    servers.getItems().setAll(values);
                    var selected = values.stream()
                            .filter(value -> value.id().equals(selectedId))
                            .findFirst()
                            .orElse(values.isEmpty() ? null : values.getFirst());
                    if (selected != null) {
                        servers.getSelectionModel().select(selected);
                    }
                    ready();
                },
                ignored -> loadFailed());
    }

    private void edit(McpServerInfo original, McpSettingsInfo settings) {
        selection++;
        var name = ManagementForms.text(original.name(), "连接名");
        var url = ManagementForms.text(settings.url(), "https://…");
        var allowlist = ManagementForms.area(String.join("\n", settings.networkAllowlist()), 3);
        var auth = ManagementForms.choices(
                List.of("none", "bearer", "apiKey", "oauth"), McpPane::authenticationName, settings.authentication());
        var reference = ManagementForms.text(settings.credentialName(), "SecretStore 引用名，如 api-key");
        var header = ManagementForms.text(settings.headerName(), "API Key Header，如 X-Api-Key");
        var clientId = ManagementForms.text(settings.clientId(), "预注册 client id，可留空");
        var scopes = ManagementForms.text(String.join(" ", settings.scopes()), "以空格分隔的最小 scope");
        var timeout = ManagementForms.number((int) settings.timeoutMillis(), 600_000);
        var output = ManagementForms.number((int) settings.outputLimitBytes(), 16 * 1024 * 1024);
        var enabled = ManagementForms.check("启用连接", original.enabled());
        var scoped = ManagementForms.check(
                "仅用于当前工作区（私网 OAuth 必须绑定）", !settings.workspaceId().isBlank());
        var save = ManagementForms.command("保存连接", UiActionKind.PRIMARY, model, () -> {
            boolean requestedEnabled = enabled.isSelected();
            String requestedName = name.getText();
            if (original.pluginId() != null) {
                model.execute(
                        "设置 MCP 启用状态",
                        sdk -> sdk.extensions()
                                .setMcpEnabled(original, requestedEnabled, ManagementViewModel.key("mcp-enable")),
                        ignored -> {
                            saveSucceeded();
                            reload();
                        },
                        this::saveFailed);
                return;
            }
            var value = new McpSettingsInfo(
                    "http",
                    url.getText(),
                    words(allowlist.getText()),
                    auth.getValue(),
                    reference.getText(),
                    header.getText(),
                    clientId.getText(),
                    words(scopes.getText()),
                    timeout.getValue(),
                    output.getValue(),
                    scoped.isSelected() ? model.workspaceId() : "");
            model.execute(
                    "保存 MCP 配置",
                    sdk -> sdk.extensions()
                            .saveMcpSettings(
                                    original.id(),
                                    requestedName,
                                    value,
                                    requestedEnabled,
                                    original.revision(),
                                    ManagementViewModel.key("mcp-config")),
                    stored -> {
                        saveSucceeded();
                        edit(stored, value);
                        reload();
                    },
                    this::saveFailed);
        });
        var health = ManagementForms.command(
                "健康检查",
                UiActionKind.GHOST,
                model,
                () -> model.execute("检查 MCP", sdk -> sdk.extensions().mcpHealth(original.id()), ignored -> reload()));
        var discover = ManagementForms.command(
                "能力目录",
                UiActionKind.GHOST,
                model,
                () -> model.execute(
                        "读取发现状态",
                        sdk -> sdk.extensions().discoverMcp(original.id()),
                        value -> ManagementForms.showText(this, "MCP 能力与缓存状态", value.canonicalJson())));
        var credential = new PasswordField();
        credential.setPromptText("只输入新凭据；不会返回原值");
        var setCredential = ManagementForms.command("保存凭据", model, () -> {
            char[] value = credential.getText().toCharArray();
            credential.clear();
            model.execute(
                    "设置 MCP 凭据",
                    sdk -> sdk.extensions()
                            .setMcpCredential(original.id(), value, ManagementViewModel.key("mcp-secret"))
                            .whenComplete((result, failure) -> Arrays.fill(value, '\0')),
                    ignored -> reload());
        });
        var clearCredential = ManagementForms.command(
                "清除凭据",
                UiActionKind.DANGER,
                model,
                () -> model.execute("读取凭据状态", sdk -> sdk.extensions().readMcpCredential(original.id()), metadata -> {
                    if (metadata.isEmpty()) {
                        ManagementForms.showText(this, "凭据状态", "没有持久静态凭据。");
                    } else if (ManagementForms.confirm(this, model, "清除 MCP 凭据", "仅清除当前修订；并发轮换后会拒绝删除。")) {
                        model.execute(
                                "清除 MCP 凭据",
                                sdk -> sdk.extensions()
                                        .clearMcpCredential(
                                                original.id(),
                                                metadata.get().revision(),
                                                ManagementViewModel.key("mcp-clear")),
                                ignored -> reload());
                    }
                }));
        var authorize = ManagementForms.command(
                "OAuth 授权…",
                model,
                () -> model.execute(
                        "开始授权", sdk -> sdk.extensions().startMcpAuthorization(original.id()), authorization -> {
                            var dialog = new Dialog<Void>();
                            dialog.setTitle("MCP 授权");
                            var address = ManagementForms.text(
                                    authorization.authorizationUrl().toString(), "");
                            address.setEditable(false);
                            var open = ManagementForms.button(
                                    "在浏览器中继续", () -> model.openAuthorization(authorization.authorizationUrl()));
                            var cancel = ManagementForms.button("取消授权", UiActionKind.DANGER, () -> {
                                model.execute(
                                        "取消授权",
                                        sdk -> sdk.extensions().cancelMcpAuthorization(authorization.authorizationId()),
                                        ignored -> reload());
                                dialog.close();
                            });
                            dialog.getDialogPane()
                                    .setContent(ManagementForms.form(
                                            ManagementForms.hint("授权仅在一次性 loopback 回调完成，不开放 App Server RPC。到期："
                                                    + authorization.expiresAt()),
                                            address,
                                            ManagementForms.actions(open, cancel)));
                            dialog.getDialogPane().getButtonTypes().add(javafx.scene.control.ButtonType.CLOSE);
                            ManagementForms.style(this, dialog);
                            dialog.showAndWait();
                        }));
        var metadataForm = ManagementForms.form(
                ManagementForms.field("名称", name),
                ManagementForms.field("HTTPS 地址", url),
                ManagementForms.field("网络允许列表（allowlist，精确主机，每行一个）", allowlist),
                ManagementForms.field("认证方式", auth),
                ManagementForms.actions(
                        ManagementForms.field("超时毫秒", timeout), ManagementForms.field("输出字节上限", output)));
        var referenceField = ManagementForms.field("静态凭据引用", reference);
        var headerField = ManagementForms.field("API Key 请求头", header);
        var clientIdField = ManagementForms.field("OAuth 客户端 ID", clientId);
        var scopesField = ManagementForms.field("OAuth 授权范围（scopes）", scopes);
        Runnable updateAuthentication = () -> {
            String selected = auth.getValue();
            setShown(referenceField, "bearer".equals(selected) || "apiKey".equals(selected));
            setShown(headerField, "apiKey".equals(selected));
            setShown(clientIdField, "oauth".equals(selected));
            setShown(scopesField, "oauth".equals(selected));
        };
        auth.valueProperty().addListener((ignored, old, value) -> updateAuthentication.run());
        metadataForm.getChildren().addAll(referenceField, headerField, clientIdField, scopesField);
        updateAuthentication.run();
        metadataForm.setDisable(original.pluginId() != null);
        var authentication = ManagementForms.form(
                ManagementForms.field("新静态凭据", credential),
                ManagementForms.actions(setCredential, clearCredential, authorize));
        authentication.setVisible(original.revision() > 0 && original.pluginId() == null);
        authentication.setManaged(authentication.isVisible());
        ManagementForms.independent(authentication);
        for (var button : List.of(health, discover)) {
            button.setVisible(original.revision() > 0);
            button.setManaged(original.revision() > 0);
        }
        var fields = ManagementForms.form(
                ManagementForms.hint(transportName(settings.transport()) + " · "
                        + DesktopPresentationMapper.status(original.state())
                        + (original.pluginId() == null ? "" : "\n入口与权限由插件 " + original.pluginId() + " 固定，仅可启停。")),
                ManagementForms.section("连接", metadataForm, scoped, enabled),
                ManagementForms.section("认证", authentication));
        editSession(original.name(), fields, save, this::reload);
        detail.setCenter(ManagementForms.editor(fields, save, health, discover));
    }

    private static void setShown(javafx.scene.Node node, boolean shown) {
        node.setVisible(shown);
        node.setManaged(shown);
    }

    private static Set<String> words(String value) {
        return Arrays.stream(value.split("[,\\s]+"))
                .filter(item -> !item.isBlank())
                .collect(Collectors.toSet());
    }

    private static String authenticationName(String value) {
        return switch (value) {
            case "none" -> "无认证";
            case "bearer" -> "Bearer Token";
            case "apiKey" -> "API Key";
            case "oauth" -> "OAuth 2.1";
            default -> DesktopPresentationMapper.text(value, "未知认证方式");
        };
    }

    private static String transportName(String value) {
        return switch (value) {
            case "http" -> "HTTPS 连接";
            case "stdio" -> "插件进程连接";
            default -> DesktopPresentationMapper.text(value, "未知连接方式");
        };
    }
}
