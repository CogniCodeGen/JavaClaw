package com.javaclaw.ui.javafx.plugin.v2;

import com.javaclaw.api.interaction.ChoiceOption;
import com.javaclaw.api.interaction.ChoiceRequest;
import com.javaclaw.api.interaction.ConfirmKind;
import com.javaclaw.api.interaction.ConfirmRequest;
import com.javaclaw.application.plugin.v2.PluginManagementService;
import com.javaclaw.application.plugin.v2.PluginManagementService.ConfigurationUpdate;
import com.javaclaw.application.plugin.v2.PluginManagementService.InstallProgress;
import com.javaclaw.application.plugin.v2.PluginManagementService.PackagePreview;
import com.javaclaw.application.plugin.v2.PluginManagementService.PluginDetails;
import com.javaclaw.application.plugin.v2.PluginManagementService.PluginVersion;
import com.javaclaw.platform.dialog.DialogService;
import com.javaclaw.platform.execution.ManagedTaskExecutor;
import com.javaclaw.platform.execution.TaskContext;
import com.javaclaw.platform.execution.TaskSpec;
import com.javaclaw.platform.fx.FxDispatcher;
import com.javaclaw.platform.fx.UiAsyncAction;
import javafx.beans.property.ReadOnlyBooleanProperty;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** 将下载、预览确认与生命周期命令放在托管任务中，禁止阻塞 FX 线程。 */
final class PluginCenterOperations implements AutoCloseable {
    record Result(PluginDetails details, boolean replaceForm, String message, boolean replacePermissions) {
        Result(PluginDetails details, boolean replaceForm, String message) { this(details, replaceForm, message, replaceForm); }
    }
    @FunctionalInterface interface Command { void execute(); }
    @FunctionalInterface private interface Inspection { PackagePreview inspect(TaskContext task, Consumer<InstallProgress> progress); }

    private final PluginManagementService service;
    private final DialogService dialogs;
    private final FxDispatcher fx;
    private final String workspace;
    private final UiAsyncAction<Result> action;
    private final UiAsyncAction<Boolean> leaveAction;
    private final Consumer<Result> success;
    private final Consumer<Throwable> failure;
    private final Consumer<InstallProgress> progress;
    private final AtomicLong generation = new AtomicLong();
    private volatile boolean closed;

    PluginCenterOperations(PluginManagementService service, DialogService dialogs,
                          ManagedTaskExecutor tasks, FxDispatcher fx, String workspace,
                          Consumer<Result> success, Consumer<Throwable> failure,
                          Consumer<InstallProgress> progress) {
        this.service = service;
        this.dialogs = dialogs;
        this.fx = fx;
        this.workspace = workspace;
        this.success = success;
        this.failure = failure;
        this.progress = progress;
        action = new UiAsyncAction<>(tasks, fx);
        leaveAction = new UiAsyncAction<>(tasks, fx);
    }

    ReadOnlyBooleanProperty busyProperty() { return action.busyProperty(); }

    void install(Path source, String expectedPluginId) {
        install((task, observer) -> service.inspectPackage(source, observer,
                task.cancellation()::isCancellationRequested), expectedPluginId);
    }
    void install(URI source, String expectedPluginId) {
        if (!"https".equalsIgnoreCase(source.getScheme()) || source.getHost() == null
                || source.getUserInfo() != null) {
            failure.accept(new IllegalArgumentException("请输入不含账号密码的 HTTPS 插件包链接"));
            return;
        }
        install((task, observer) -> service.inspectPackage(source, observer,
                task.cancellation()::isCancellationRequested), expectedPluginId);
    }
    private void install(Inspection inspection, String expectedPluginId) {
        long run = generation.incrementAndGet();
        action.execute(TaskSpec.io("插件包预览与安装"), task -> {
            PackagePreview preview = inspection.inspect(task, update -> fx.dispatch(() -> {
                if (!closed && generation.get() == run) progress.accept(update);
            }));
            try {
                task.cancellation().throwIfCancellationRequested();
                if (expectedPluginId != null && !expectedPluginId.equals(preview.id())) {
                    throw new IllegalArgumentException("升级包属于 " + preview.id() + "，与当前插件不一致");
                }
                if (!confirm("plugin.install", "安装预览", previewText(preview))) {
                    return new Result(null, false, "已取消安装");
                }
                task.cancellation().throwIfCancellationRequested();
                return new Result(service.install(workspace, preview.previewId()), true,
                        "插件已安装，默认保持停用；请在权限页检查授权后启用");
            } finally { service.discardPackagePreview(preview.previewId()); }
        }, success, failure);
    }

    void command(String pluginId, String name, Command command) {
        action.execute(TaskSpec.io("插件-" + name), task -> {
            command.execute();
            return new Result(service.details(workspace, pluginId), false, name + "已提交");
        }, success, failure);
    }

    void selectVersion(PluginDetails current, PluginVersion target) {
        action.execute(TaskSpec.io("切换工作区插件版本"), task -> {
            if (!confirm("plugin.version", "工作区版本切换", "将当前工作区绑定到 " + target.version()
                    + "\n发布者：" + target.publisher() + "\n签名：" + (target.signatureVerified() ? "已验证" : "未验证")
                    + "\nSHA-256：" + target.artifactHash() + "\n已有执行继续使用原工件；新执行使用所选版本。")) {
                return new Result(null, false, "已取消版本切换");
            }
            task.cancellation().throwIfCancellationRequested();
            return new Result(service.selectVersion(workspace, current.summary().id(), target.artifactHash(),
                    current.configuration().revision()), true, "当前工作区已绑定版本 " + target.version());
        }, success, failure);
    }

    void save(String pluginId, ConfigurationUpdate update, boolean permissionsOnly) {
        action.execute(TaskSpec.io("插件配置保存"), task -> {
            if (permissionsOnly && !confirm("plugin.permissions", "权限变更",
                    "将更新当前工作区的授权：\n" + String.join("\n", update.grantedPermissions())
                            + "\n撤销的权限立即失效；新增权限仅供新实例使用。")) {
                return new Result(null, false, "已取消权限变更");
            }
            return new Result(service.updateConfiguration(workspace, pluginId, update), !permissionsOnly,
                    permissionsOnly ? "权限已应用；撤权立即停止旧权限实例，新执行使用新配置"
                            : "配置已保存并应用；新执行使用新版本，已有执行继续使用原实例", true);
        }, success, failure);
    }

    void enable(PluginDetails details) {
        String id = details.summary().id();
        if (details.summary().enabled()) {
            command(id, "停用", () -> service.setEnabled(workspace, id, false));
            return;
        }
        action.execute(TaskSpec.io("插件启用"), task -> {
            if (!details.configuration().grantedPermissions().containsAll(details.requiredPermissions())) {
                throw new IllegalStateException("请先到权限页授权插件需要的权限");
            }
            service.setEnabled(workspace, id, true);
            return new Result(service.details(workspace, id), false, "已启用；进程状态单独显示");
        }, success, failure);
    }

    void revoke(String pluginId) {
        action.execute(TaskSpec.io("立即撤销插件权限"), task -> {
            if (!confirm("plugin.revoke", "立即撤权", "撤销当前工作区该插件的全部权限，停止后续调用。已有执行不能继续使用这些权限。")) {
                return new Result(null, false, "已取消撤权");
            }
            service.revoke(workspace, pluginId);
            return new Result(service.details(workspace, pluginId), false, "权限已立即撤销", true);
        }, success, failure);
    }

    void forceStop(String pluginId) {
        action.execute(TaskSpec.io("强制结束插件"), task -> {
            var approval = dialogs.confirm(new ConfirmRequest("plugin.forceStop", "强制结束进程",
                    "将立即终止 " + pluginId + " 的所有活跃执行。已提交的外部操作可能结果未知，不会自动重放。",
                    ConfirmKind.DOUBLE_CONFIRM, 0, pluginId, true));
            if (!approval.isAllow()) return new Result(null, false, "已取消强制结束");
            task.cancellation().throwIfCancellationRequested(); service.forceStop(workspace, pluginId);
            return new Result(service.details(workspace, pluginId), false, "进程已强制结束；请检查运行日志和业务记录");
        }, success, failure);
    }

    void uninstall(String pluginId) {
        action.execute(TaskSpec.io("插件卸载预览"), task -> {
            var preview = service.previewUninstall(workspace, pluginId);
            String references = preview.workspaceReferences().stream().map(reference ->
                    reference.workspaceId() + " · 活跃执行 " + reference.activeRuns() + " · 配置 " + reference.revision())
                    .collect(java.util.stream.Collectors.joining("\n"));
            String option = dialogs.choose(new ChoiceRequest("卸载插件", "将卸载该插件的全部版本，影响以下工作区：\n"
                    + references + "\n" + String.join("\n", preview.blockers()) + "\n已有执行结束后完成卸载。", List.of(
                    new ChoiceOption("keep", "卸载并保留数据（默认）", "保留工作区配置、秘密与私有数据"),
                    new ChoiceOption("delete", "卸载并永久删除数据", "删除所有工作区的配置与私有数据"),
                    new ChoiceOption("cancel", "取消", "保留插件")), 0));
            if (!"keep".equals(option) && !"delete".equals(option)) return new Result(null, false, "已取消卸载");
            boolean deleteData = option.equals("delete");
            if (deleteData && !dialogs.confirm(new ConfirmRequest("plugin.uninstall", "永久删除",
                    "永久删除 " + pluginId + " 在全部工作区的配置和私有数据", ConfirmKind.DOUBLE_CONFIRM,
                    0, pluginId, true)).isAllow()) return new Result(null, false, "已取消删除");
            task.cancellation().throwIfCancellationRequested();
            service.uninstall(workspace, pluginId, deleteData);
            return new Result(null, false, "卸载已提交；存在活跃执行时等待结束" + (deleteData ? "，完成后删除数据" : "，保留数据"));
        }, success, failure);
    }

    void leave(boolean dirty, Runnable accepted, Runnable rejected) {
        if (!dirty) { accepted.run(); return; }
        leaveAction.execute(TaskSpec.io("确认未保存配置"), task -> confirm("plugin.discard", "未保存的修改",
                        "配置有未保存的修改。离开将放弃这些修改，是否继续？"),
                allowed -> { if (allowed) accepted.run(); else rejected.run(); }, failure);
    }

    private boolean confirm(String tool, String label, String message) {
        return dialogs.confirm(new ConfirmRequest(tool, label, message, ConfirmKind.CONFIRM,
                0, "", true)).isAllow();
    }
    private static String previewText(PackagePreview preview) {
        return preview.name() + "\n插件 ID：" + preview.id() + "\n版本：" + preview.version()
                + "\n发布者：" + preview.publisher() + "\n签名：" + (preview.signatureVerified() ? "已验证" : "未验证")
                + "\n签名指纹：" + preview.signerFingerprint() + "\nSHA-256：" + preview.artifactHash()
                + "\n来源：" + preview.source() + "\n大小：" + preview.sizeBytes() + " 字节 / " + preview.files().size() + " 个文件"
                + "\n能力：" + String.join("、", preview.capabilities()) + "\n申请权限：\n"
                + String.join("\n", preview.requiredPermissions()) + "\n\n"
                + "插件在独立进程中以当前用户权限运行。确认仅安装此工件，默认不启用。";
    }

    void cancel() { generation.incrementAndGet(); action.cancel(); }
    @Override public void close() { closed = true; generation.incrementAndGet(); action.close(); leaveAction.close(); }
}
