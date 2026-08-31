package com.javaclaw.desktop;

import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.ListView;
import javafx.scene.layout.BorderPane;

import com.javaclaw.sdk.JavaClawClient;
import com.javaclaw.sdk.model.AttachmentInfo;
import com.javaclaw.sdk.model.PluginInfo;
import com.javaclaw.sdk.model.PluginPreviewInfo;

/** 原插件设置的安装、健康、启停和信任表单；签名、来源确认与权限审批三者独立。 */
final class PluginPane extends ManagedManagementPage {
    private final ListView<PluginInfo> plugins = ManagementForms.list(
            value -> value.id() + " · " + value.version() + "\n" + DesktopPresentationMapper.status(value.state()),
            "尚未安装插件",
            "安装 Plugin 4.0 ZIP 后，可在这里管理启停、健康状态和隔离。");
    private final BorderPane detail = new BorderPane();

    PluginPane(ManagementViewModel model) {
        super(model);
        setCenter(ManagementForms.split(plugins, detail));
        setTop(ManagementForms.actions(
                ManagementForms.command(
                        "＋ 安装 Plugin 4.0", UiActionKind.PRIMARY, model, () -> requestNavigation(this::install)),
                ManagementForms.command("信任公钥", model, () -> requestNavigation(this::trust)),
                ManagementForms.command("刷新", UiActionKind.GHOST, model, () -> requestNavigation(this::reload))));
        guardSelection(plugins, this::show);
        detail.setCenter(ManagementForms.emptyState("⬡", "安装第一个插件", "第三方扩展全部进程外运行。安装前检查实际 ZIP、签名来源和声明权限。"));
        reload();
    }

    private void reload() {
        refreshing();
        String selectedId = plugins.getSelectionModel().getSelectedItem() == null
                ? null
                : plugins.getSelectionModel().getSelectedItem().id();
        model.execute(
                "读取插件",
                sdk -> sdk.extensions().listPlugins(),
                values -> {
                    plugins.getItems().setAll(values);
                    var selected = values.stream()
                            .filter(value -> value.id().equals(selectedId))
                            .findFirst()
                            .orElse(values.isEmpty() ? null : values.getFirst());
                    if (selected != null) {
                        plugins.getSelectionModel().select(selected);
                    }
                    ready();
                },
                ignored -> loadFailed());
    }

    private void show(PluginInfo plugin) {
        var enabled = ManagementForms.check("启用插件", plugin.enabled());
        var save = ManagementForms.command("应用启停", UiActionKind.PRIMARY, model, () -> {
            boolean value = enabled.isSelected();
            model.execute(
                    "设置插件状态",
                    sdk -> sdk.extensions()
                            .setPluginEnabled(
                                    plugin.id(), value, plugin.revision(), ManagementViewModel.key("plugin-enabled")),
                    saved -> {
                        saveSucceeded();
                        show(saved);
                        reload();
                    },
                    this::saveFailed);
        });
        var health = ManagementForms.command(
                "健康检查",
                UiActionKind.GHOST,
                model,
                () -> requestNavigation(
                        () -> model.execute("检查插件", sdk -> sdk.extensions().pluginHealth(plugin.id()), value -> {
                            show(value);
                            reload();
                        })));
        var uninstall = ManagementForms.command("卸载到应用 Trash", UiActionKind.DANGER, model, () -> {
            if (ManagementForms.confirm(this, model, "卸载插件", "先停止进程，再移动安装目录到应用 Trash；不会直接永久删除插件目录。")) {
                model.execute(
                        "卸载插件",
                        sdk -> sdk.extensions()
                                .uninstallPlugin(
                                        plugin.id(), plugin.revision(), ManagementViewModel.key("plugin-remove")),
                        ignored -> {
                            detail.setCenter(ManagementForms.emptyState("✓", "插件已卸载", "插件已停止并移到应用 Trash，可从 Trash 恢复。"));
                            reload();
                        });
            }
        });
        var fields = ManagementForms.form(
                ManagementForms.hint(plugin.id() + " · " + plugin.version() + "\n状态："
                        + DesktopPresentationMapper.status(plugin.state())),
                ManagementForms.hint("签名已验证：" + DesktopPresentationMapper.yesNo(plugin.signatureVerified())
                        + " · 公钥：" + DesktopPresentationMapper.text(plugin.signerKeyId(), "未提供")
                        + "\n来源已确认：" + DesktopPresentationMapper.yesNo(plugin.sourceConfirmed()) + " · 权限已批准："
                        + DesktopPresentationMapper.yesNo(plugin.permissionsApproved()) + "\n重启次数："
                        + plugin.restartCount() + "\n最近错误："
                        + DesktopPresentationMapper.text(plugin.lastError(), "无")),
                enabled);
        editSession(plugin.id(), fields, save, this::reload);
        detail.setCenter(ManagementForms.editor(fields, save, health, uninstall));
    }

    private void install() {
        var file = model.dialogs()
                .chooseOpenFile(
                        this,
                        "选择 Plugin 4.0 ZIP",
                        List.of(new DesktopDialogGateway.FileType("Plugin ZIP", List.of("*.zip"))))
                .orElse(null);
        if (file == null) {
            return;
        }
        String key = ManagementViewModel.key("plugin-upload");
        model.execute(
                "上传并审阅插件",
                sdk -> sdk.attachments()
                        .upload(file, "application/zip", key)
                        .thenCompose(bundle -> releaseAfterFailure(
                                sdk,
                                bundle,
                                sdk.extensions()
                                        .previewPlugin(bundle)
                                        .thenApply(preview -> new PendingInstall(bundle, preview)))),
                this::reviewInstall);
    }

    private void reviewInstall(PendingInstall pending) {
        PluginPreviewInfo preview = pending.preview();
        var source = ManagementForms.check("我已核实并确认此插件来源", false);
        var permissions = ManagementForms.check("我批准上述声明的工作区与网络范围", false);
        var enabled = ManagementForms.check("安装后启用", false);
        var content = ManagementForms.area(String.join("\n\n", preview.permissions()), 12);
        content.setEditable(false);
        var dialog = new Dialog<ButtonType>();
        dialog.setTitle("安装前审阅 · " + preview.name());
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        dialog.getDialogPane()
                .setContent(ManagementForms.form(
                        ManagementForms.hint(preview.id() + " · " + preview.version() + "\nSHA-256：" + preview.sha256()
                                + "\n签名已验证：" + DesktopPresentationMapper.yesNo(preview.signatureVerified())
                                + " · 公钥：" + DesktopPresentationMapper.text(preview.signerKeyId(), "未提供")),
                        content,
                        source,
                        permissions,
                        enabled,
                        ManagementForms.hint("签名只证明来源，不自动提升权限。安装使用本次预览的同一附件摘要。")));
        ManagementForms.style(this, dialog);
        dialog.getDialogPane()
                .lookupButton(ButtonType.OK)
                .disableProperty()
                .bind(source.selectedProperty()
                        .not()
                        .and(javafx.beans.binding.Bindings.createBooleanBinding(() -> !preview.signatureVerified()))
                        .or(permissions
                                .selectedProperty()
                                .not()
                                .and(javafx.beans.binding.Bindings.createBooleanBinding(
                                        preview::requiresPermissions))));
        dialog.setResultConverter(value -> value);
        if (dialog.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
            releaseUninstalledBundle(pending.attachment());
            return;
        }
        boolean confirmed = source.isSelected();
        boolean approved = permissions.isSelected();
        boolean start = enabled.isSelected();
        model.execute(
                "安装已审阅插件",
                sdk -> releaseAfterCompletion(
                        sdk,
                        pending.attachment(),
                        sdk.extensions()
                                .installPlugin(
                                        pending.attachment(),
                                        confirmed,
                                        approved,
                                        start,
                                        ManagementViewModel.key("plugin-install"))),
                value -> {
                    show(value);
                    reload();
                });
    }

    private void releaseUninstalledBundle(AttachmentInfo attachment) {
        model.execute("释放未安装插件包", sdk -> sdk.attachments().release(attachment.sha256()), ignored -> {});
    }

    private static <T> CompletableFuture<T> releaseAfterFailure(
            JavaClawClient sdk, AttachmentInfo attachment, CompletableFuture<T> request) {
        return request.handle((value, failure) -> {
                    if (failure == null) {
                        return CompletableFuture.completedFuture(value);
                    }
                    return sdk.attachments().release(attachment.sha256()).<T>handle((ignored, cleanupFailure) -> {
                        throw propagate(failure);
                    });
                })
                .thenCompose(value -> value);
    }

    private static <T> CompletableFuture<T> releaseAfterCompletion(
            JavaClawClient sdk, AttachmentInfo attachment, CompletableFuture<T> request) {
        return request.handle((value, failure) -> sdk.attachments()
                        .release(attachment.sha256())
                        .handle((ignored, cleanupFailure) -> {
                            if (failure != null) {
                                throw propagate(failure);
                            }
                            return value;
                        }))
                .thenCompose(value -> value);
    }

    private static CompletionException propagate(Throwable failure) {
        return failure instanceof CompletionException completion ? completion : new CompletionException(failure);
    }

    private void trust() {
        model.execute("读取信任公钥", sdk -> sdk.extensions().listTrustKeys(), values -> {
            var list = ManagementForms.<com.javaclaw.sdk.model.PluginTrustKeyInfo>list(
                    value -> value.label() + " · " + value.keyId() + "\nSHA-256：" + value.fingerprintSha256());
            list.getItems().setAll(values);
            var id = ManagementForms.text("", "发布者公钥 id");
            var label = ManagementForms.text("", "展示标签");
            var encoded = ManagementForms.area("", 5);
            var add = ManagementForms.command("添加公钥", UiActionKind.PRIMARY, model, () -> {
                String keyId = id.getText();
                String title = label.getText();
                String publicKey = encoded.getText().replaceAll("\\s", "");
                if (!ManagementForms.confirm(this, model, "确认信任公钥", "请通过独立渠道核验公钥指纹。信任此发布者不会批准其插件权限。")) {
                    return;
                }
                model.execute(
                        "登记公钥",
                        sdk -> sdk.extensions()
                                .addTrustKey(
                                        keyId,
                                        Base64.getDecoder().decode(publicKey),
                                        title,
                                        0,
                                        ManagementViewModel.key("trust-add")),
                        ignored -> trust());
            });
            var remove = ManagementForms.command("移除所选公钥", UiActionKind.DANGER, model, () -> {
                var value = list.getSelectionModel().getSelectedItem();
                if (value == null || !ManagementForms.confirm(this, model, "移除信任", "后续签名验证不能再依赖此公钥。")) {
                    return;
                }
                model.execute(
                        "移除信任公钥",
                        sdk -> sdk.extensions()
                                .removeTrustKey(
                                        value.keyId(), value.revision(), ManagementViewModel.key("trust-remove")),
                        ignored -> trust());
            });
            detail.setCenter(ManagementForms.scroll(ManagementForms.form(
                    list,
                    remove,
                    ManagementForms.field("公钥 id", id),
                    ManagementForms.field("标签", label),
                    ManagementForms.field("X.509 Ed25519 公钥 Base64（不是私钥）", encoded),
                    add)));
        });
    }

    private record PendingInstall(AttachmentInfo attachment, PluginPreviewInfo preview) {}
}
