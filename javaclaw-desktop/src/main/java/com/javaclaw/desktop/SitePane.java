package com.javaclaw.desktop;

import java.net.URI;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.ListView;
import javafx.scene.control.PasswordField;
import javafx.scene.layout.BorderPane;

import com.javaclaw.sdk.model.BrowserSiteInfo;

/** 原站点管理的列表/表单布局；登录在受监督浏览器完成，密码不回填普通文本框。 */
final class SitePane extends ManagedManagementPage {
    private final ListView<BrowserSiteInfo> sites = ManagementForms.list(
            value -> value.name() + "\n" + value.origin() + " · " + (value.enabled() ? "已启用" : "已停用"));
    private final BorderPane detail = new BorderPane();

    SitePane(ManagementViewModel model) {
        super(model);
        setCenter(ManagementForms.split(sites, detail));
        setTop(ManagementForms.actions(
                ManagementForms.command(
                        "＋ 站点",
                        UiActionKind.PRIMARY,
                        model,
                        () -> requestNavigation(() -> edit(new BrowserSiteInfo(
                                null,
                                model.workspaceId(),
                                "新站点",
                                URI.create("https://example.com"),
                                Set.of(),
                                true,
                                0,
                                null)))),
                ManagementForms.command("私网授权…", model, this::network),
                ManagementForms.command("刷新", UiActionKind.GHOST, model, () -> requestNavigation(this::reload))));
        guardSelection(sites, this::edit);
        detail.setCenter(ManagementForms.form(
                ManagementForms.hint("保留站点登录与网页操作。页面、重定向和资源请求仅访问明确授权的来源；不支持原始 Cookie 导出或宿主桌面控制。")));
        reload();
    }

    private void reload() {
        refreshing();
        String workspace = model.workspaceId();
        String selectedId = sites.getSelectionModel().getSelectedItem() == null
                ? null
                : sites.getSelectionModel().getSelectedItem().id();
        model.execute(
                "读取站点",
                sdk -> sdk.extensions().listSites(workspace),
                values -> {
                    sites.getItems().setAll(values);
                    values.stream()
                            .filter(value -> java.util.Objects.equals(value.id(), selectedId))
                            .findFirst()
                            .or(() -> values.stream().findFirst())
                            .ifPresent(sites.getSelectionModel()::select);
                    ready();
                },
                ignored -> loadFailed());
    }

    private void edit(BrowserSiteInfo original) {
        var name = ManagementForms.text(original.name(), "站点名称");
        var origin = ManagementForms.text(original.origin().toString(), "准确源，如 https://example.com");
        var allowed = ManagementForms.area(
                original.allowedOrigins().stream().map(URI::toString).sorted().collect(Collectors.joining("\n")), 4);
        var enabled = ManagementForms.check("启用站点", original.enabled());
        var save = ManagementForms.command("保存站点", UiActionKind.PRIMARY, model, () -> {
            try {
                var value = new BrowserSiteInfo(
                        original.id(),
                        original.workspaceId(),
                        name.getText(),
                        URI.create(origin.getText().strip()),
                        allowed.getText()
                                .lines()
                                .map(String::strip)
                                .filter(line -> !line.isBlank())
                                .map(URI::create)
                                .collect(Collectors.toSet()),
                        enabled.isSelected(),
                        original.revision(),
                        null);
                if (!ManagementForms.confirm(
                        this,
                        model,
                        "确认站点范围",
                        "仅允许所列准确来源；配置变更会关闭旧浏览器，旧版本凭据及登录状态不自动沿用。\n\n" + value.origin() + "\n"
                                + value.allowedOrigins().stream()
                                        .map(URI::toString)
                                        .sorted()
                                        .collect(Collectors.joining("\n")))) {
                    return;
                }
                model.execute(
                        "保存站点",
                        sdk -> sdk.extensions().saveSite(value, true, ManagementViewModel.key("site")),
                        saved -> {
                            saveSucceeded();
                            reload();
                            edit(saved);
                        },
                        this::saveFailed);
            } catch (IllegalArgumentException invalid) {
                ManagementForms.showText(this, "站点地址无效", "请填写准确 HTTP(S) 源，不含路径、查询、通配符或凭据。");
            }
        });
        var credentials = new BorderPane();
        if (original.revision() > 0 && original.enabled()) {
            var slot = ManagementForms.text("password", "SecretRef 名称");
            var password = new PasswordField();
            password.setPromptText("只输入新凭据，不显示已保存值");
            var set = ManagementForms.command("保存凭据", model, () -> {
                String reference = slot.getText();
                char[] value = password.getText().toCharArray();
                password.clear();
                model.execute(
                        "保存站点凭据",
                        sdk -> sdk.extensions()
                                .setSiteCredential(
                                        original.id(), reference, value, ManagementViewModel.key("site-secret"))
                                .whenComplete((result, failure) -> Arrays.fill(value, '\0')),
                        metadata -> ManagementForms.showText(
                                this, "凭据状态", "已配置 · 版本 " + metadata.revision() + "\nSecretRef：" + reference));
            });
            var clear = ManagementForms.command("清除凭据", UiActionKind.DANGER, model, () -> {
                String reference = slot.getText();
                model.execute(
                        "读取凭据版本", sdk -> sdk.extensions().readSiteCredential(original.id(), reference), metadata -> {
                            if (metadata.isPresent()
                                    && ManagementForms.confirm(this, model, "清除凭据", "仅移除当前站点版本中的 " + reference + "。")) {
                                model.execute(
                                        "清除站点凭据",
                                        sdk -> sdk.extensions()
                                                .clearSiteCredential(
                                                        original.id(),
                                                        reference,
                                                        metadata.get().revision(),
                                                        ManagementViewModel.key("site-secret-clear")),
                                        ignored -> reload());
                            }
                        });
            });
            var login = ManagementForms.command("人工登录…", model, () -> login(original));
            var state = ManagementForms.command(
                    "登录状态",
                    model,
                    () -> model.execute(
                            "读取登录状态",
                            sdk -> sdk.extensions().readBrowserSession(original.id()),
                            metadata -> ManagementForms.showText(
                                    this,
                                    "加密会话",
                                    metadata.isPresent()
                                            ? "当前站点版本有加密会话 · 版本 "
                                                    + metadata.get().revision()
                                            : "尚未保存当前站点版本的登录状态。")));
            var clearState = ManagementForms.command(
                    "清除会话",
                    UiActionKind.DANGER,
                    model,
                    () -> model.execute(
                            "读取会话版本", sdk -> sdk.extensions().readBrowserSession(original.id()), metadata -> {
                                if (metadata.isPresent()
                                        && ManagementForms.confirm(
                                                this, model, "清除登录会话", "会停止此站点的活动浏览器；不会删除其他站点或浏览器数据。")) {
                                    model.execute(
                                            "清除会话",
                                            sdk -> sdk.extensions()
                                                    .clearBrowserSession(
                                                            original.id(),
                                                            metadata.get().revision(),
                                                            ManagementViewModel.key("site-state-clear")),
                                            ignored -> reload());
                                }
                            }));
            credentials.setCenter(ManagementForms.form(
                    ManagementForms.field("凭据引用（SecretRef）", slot),
                    ManagementForms.field("新凭据", password),
                    ManagementForms.actions(set, clear),
                    ManagementForms.actions(login, state, clearState)));
        }
        ManagementForms.independent(credentials);
        var fields = ManagementForms.form(
                ManagementForms.hint("版本 " + original.revision() + " · 站点与凭据只适用于当前工作区"),
                ManagementForms.section(
                        "站点范围",
                        ManagementForms.field("名称", name),
                        ManagementForms.field("起始来源", origin),
                        ManagementForms.field("其他明确允许的来源（每行一个）", allowed),
                        enabled),
                ManagementForms.section("授权与登录", credentials));
        editSession(original.id() == null ? "新站点" : original.name(), fields, save, this::reload);
        detail.setCenter(ManagementForms.editor(fields, save));
    }

    private void login(BrowserSiteInfo site) {
        if (!ManagementForms.confirm(
                this, model, "打开隔离浏览器", "请在新浏览器中亲自完成认证，再回来确认保存。浏览器最长保留十分钟，原始 Cookie 不进入模型。\n\n" + site.origin())) {
            return;
        }
        model.execute(
                "启动人工登录",
                sdk -> sdk.extensions()
                        .startBrowserLogin(site.id(), site.revision(), true, ManagementViewModel.key("browser-login")),
                session -> {
                    var dialog = new Dialog<ButtonType>();
                    dialog.setTitle("人工登录 · " + site.name());
                    dialog.getDialogPane()
                            .setContent(ManagementForms.form(
                                    ManagementForms.hint("在隔离浏览器登录完成后点击“保存登录”。取消或关闭不会保存。\n到期：" + session.expiresAt())));
                    var save = new ButtonType("保存登录", javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
                    dialog.getDialogPane().getButtonTypes().addAll(save, ButtonType.CANCEL);
                    ManagementForms.style(this, dialog);
                    boolean confirmed =
                            dialog.showAndWait().filter(save::equals).isPresent();
                    model.execute(
                            confirmed ? "加密保存登录" : "取消登录",
                            sdk -> sdk.extensions()
                                    .finishBrowserLogin(
                                            session.sessionId(),
                                            confirmed,
                                            ManagementViewModel.key("browser-login-finish")),
                            ignored -> reload());
                });
    }

    private void network() {
        var dialog = new Dialog<Void>();
        dialog.setTitle("工作区私网授权");
        dialog.getDialogPane().setContent(new NetworkGrantPane(model));
        dialog.getDialogPane().setPrefSize(920, 660);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        ManagementForms.style(this, dialog);
        dialog.showAndWait();
    }
}
