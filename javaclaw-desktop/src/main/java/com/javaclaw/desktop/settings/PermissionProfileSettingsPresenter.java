package com.javaclaw.desktop.settings;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

import com.javaclaw.api.PermissionProfile;
import com.javaclaw.client.CommandOptions;

/** PermissionProfile 设置页的异步状态机；不持有 JavaFX 控件。 */
public final class PermissionProfileSettingsPresenter {
    private final CoreSettingsGateway gateway;
    private Consumer<PermissionProfileSettingsState> listener = ignored -> {};
    private PermissionProfileSettingsState state = PermissionProfileSettingsState.initial();

    /**
     * 创建 Presenter。
     *
     * @param gateway SDK 异步边界
     */
    public PermissionProfileSettingsPresenter(CoreSettingsGateway gateway) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
    }

    /**
     * 订阅完整状态并立即收到当前快照。
     *
     * @param value 页面渲染回调
     */
    public void subscribe(Consumer<PermissionProfileSettingsState> value) {
        listener = Objects.requireNonNull(value, "listener");
        listener.accept(state);
    }

    /** 异步读取最新 PermissionProfile 目录。 */
    public void reload() {
        long epoch = state.epoch() + 1;
        publish(new PermissionProfileSettingsState(
                SettingsLoadState.LOADING,
                state.profiles(),
                state.selected(),
                state.baseline(),
                state.draft(),
                state.cloneSource(),
                "正在读取权限方案…",
                false,
                epoch));
        gateway.permissionProfiles().whenComplete((profiles, failure) -> completeReload(epoch, profiles, failure));
    }

    /** 配置失效只重验脏表单目录，保留复制来源、权限草稿和原始保存版本。 */
    void refreshForConfigurationChange(boolean preserveDraft) {
        PermissionProfileSettingsState before = state;
        long epoch = before.epoch() + 1;
        publish(new PermissionProfileSettingsState(
                SettingsLoadState.LOADING,
                before.profiles(),
                before.selected(),
                before.baseline(),
                before.draft(),
                before.cloneSource(),
                "正在更新权限目录；草稿保持不变…",
                before.revisionConflict(),
                epoch));
        gateway.permissionProfiles().whenComplete((catalog, error) -> {
            if (state.epoch() != epoch) {
                return;
            }
            if (!preserveDraft && !state.dirty() && !state.revisionConflict() && !state.cloning()) {
                completeReload(epoch, catalog, error);
                return;
            }
            publish(new PermissionProfileSettingsState(
                    error == null ? SettingsLoadState.READY : SettingsLoadState.ERROR,
                    error == null ? catalog : state.profiles(),
                    state.selected(),
                    state.baseline(),
                    state.draft(),
                    state.cloneSource(),
                    error == null
                            ? "权限方案已更新；目录已刷新，当前草稿和保存版本保持不变。"
                            : "权限目录读取失败；草稿仍保留：" + SettingsFailures.message(error),
                    state.revisionConflict(),
                    epoch));
        });
    }

    /**
     * 选择权威配置；内置“受限对话”会以只读方式呈现。
     *
     * @param profile 权限配置
     */
    public void select(PermissionProfile profile) {
        PermissionProfile checked = Objects.requireNonNull(profile, "profile");
        if (state.dirty()) {
            warnUnsavedChanges();
            return;
        }
        PermissionProfileDraft draft = PermissionProfileDraft.from(checked);
        publish(new PermissionProfileSettingsState(
                SettingsLoadState.READY,
                state.profiles(),
                Optional.of(checked),
                draft,
                draft,
                Optional.empty(),
                checked.id().equals("standard") ? "“受限对话”是内置只读模板，请复制后编辑" : "",
                false,
                state.epoch() + 1));
    }

    /** 从当前选中配置创建一个尚未命名的新配置草稿。 */
    public void cloneSelected() {
        PermissionProfile selected = state.selected().orElseThrow(() -> new IllegalStateException("请先选择权限方案模板"));
        if (state.dirty()) {
            warnUnsavedChanges();
            return;
        }
        PermissionProfileDraft cloned = PermissionProfileDraft.from(selected).cloneDraft();
        publish(new PermissionProfileSettingsState(
                SettingsLoadState.READY,
                state.profiles(),
                Optional.empty(),
                cloned,
                cloned,
                Optional.of(new com.javaclaw.api.PermissionProfileRef(selected.id(), selected.version())),
                "请输入新的权限方案标识；复制操作永远不会修改模板",
                false,
                state.epoch() + 1));
    }

    /**
     * 替换当前草稿。
     *
     * @param draft 页面控件投影
     */
    public void updateDraft(PermissionProfileDraft draft) {
        if (state.standardReadOnly()) {
            return;
        }
        publish(new PermissionProfileSettingsState(
                SettingsLoadState.READY,
                state.profiles(),
                state.selected(),
                state.baseline(),
                draft,
                state.cloneSource(),
                state.message(),
                false,
                state.epoch()));
    }

    /** 保存新配置或写入自定义配置的新版本。 */
    public void save() {
        if (state.standardReadOnly()) {
            publishFailure(new IllegalStateException("“受限对话”是内置只读模板，请先复制为新方案"));
            return;
        }
        if (state.cloning()) {
            cloneProfile();
            return;
        }
        long expected = state.selected().map(PermissionProfile::version).orElse(0L);
        PermissionProfile profile;
        try {
            requireId(state.draft().id());
            profile = state.draft().toProfile(Math.addExact(expected, 1));
        } catch (RuntimeException invalid) {
            publishFailure(invalid);
            return;
        }
        publish(new PermissionProfileSettingsState(
                SettingsLoadState.SAVING,
                state.profiles(),
                state.selected(),
                state.baseline(),
                state.draft(),
                state.cloneSource(),
                "正在保存权限方案…",
                false,
                state.epoch()));
        gateway.updatePermissionProfile(profile, CommandOptions.create(expected))
                .whenComplete(this::completeWrite);
    }

    /** 丢弃本地草稿。 */
    public void discardDraft() {
        publish(new PermissionProfileSettingsState(
                SettingsLoadState.READY,
                state.profiles(),
                state.selected(),
                state.baseline(),
                state.baseline(),
                state.cloneSource(),
                "本地草稿已丢弃",
                false,
                state.epoch()));
    }

    /** 显示统一离页保护说明。 */
    public void warnUnsavedChanges() {
        publish(new PermissionProfileSettingsState(
                state.phase(),
                state.profiles(),
                state.selected(),
                state.baseline(),
                state.draft(),
                state.cloneSource(),
                "请先保存或放弃权限方案草稿",
                false,
                state.epoch()));
    }

    /** @return 当前不可变状态 */
    public PermissionProfileSettingsState state() {
        return state;
    }

    private void completeReload(long epoch, List<PermissionProfile> profiles, Throwable failure) {
        if (epoch != state.epoch()) {
            return;
        }
        if (failure != null) {
            publish(new PermissionProfileSettingsState(
                    SettingsLoadState.ERROR,
                    state.profiles(),
                    state.selected(),
                    state.baseline(),
                    state.draft(),
                    state.cloneSource(),
                    SettingsFailures.message(failure),
                    false,
                    epoch));
            return;
        }
        List<PermissionProfile> catalog = List.copyOf(profiles);
        Optional<PermissionProfile> selected = state.selected()
                .flatMap(previous -> catalog.stream()
                        .filter(profile -> profile.id().equals(previous.id()))
                        .findFirst())
                .or(() -> catalog.stream()
                        .filter(profile -> profile.id().equals("standard"))
                        .findFirst())
                .or(() -> catalog.stream().findFirst());
        PermissionProfileDraft draft =
                selected.map(PermissionProfileDraft::from).orElse(state.baseline());
        String message = selected.isEmpty()
                ? "没有可用权限方案；请检查服务端内置的“受限对话”方案"
                : selected.orElseThrow().id().equals("standard") ? "“受限对话”是内置只读模板，请复制后编辑" : "";
        publish(new PermissionProfileSettingsState(
                SettingsLoadState.READY, catalog, selected, draft, draft, Optional.empty(), message, false, epoch));
    }

    private void completeWrite(PermissionProfile profile, Throwable failure) {
        if (failure != null) {
            publishFailure(failure);
            return;
        }
        List<PermissionProfile> profiles = replace(state.profiles(), profile);
        PermissionProfileDraft draft = PermissionProfileDraft.from(profile);
        publish(new PermissionProfileSettingsState(
                SettingsLoadState.READY,
                profiles,
                Optional.of(profile),
                draft,
                draft,
                Optional.empty(),
                "权限方案已保存为版本 " + profile.version(),
                false,
                state.epoch()));
    }

    private void publishFailure(Throwable failure) {
        publish(new PermissionProfileSettingsState(
                SettingsLoadState.ERROR,
                state.profiles(),
                state.selected(),
                state.baseline(),
                state.draft(),
                state.cloneSource(),
                SettingsFailures.message(failure),
                SettingsFailures.revisionConflict(failure),
                state.epoch()));
    }

    private void cloneProfile() {
        String newId;
        try {
            newId = requireId(state.draft().id());
        } catch (RuntimeException invalid) {
            publishFailure(invalid);
            return;
        }
        publish(new PermissionProfileSettingsState(
                SettingsLoadState.SAVING,
                state.profiles(),
                state.selected(),
                state.baseline(),
                state.draft(),
                state.cloneSource(),
                "正在由服务端复制权限方案…",
                false,
                state.epoch()));
        gateway.clonePermissionProfile(state.cloneSource().orElseThrow(), newId, CommandOptions.create(0))
                .whenComplete(this::completeWrite);
    }

    private static String requireId(String value) {
        String normalized = Objects.requireNonNullElse(value, "").strip();
        if (!normalized.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) {
            throw new IllegalArgumentException("权限方案标识只能包含字母、数字、点、下划线和连字符");
        }
        return normalized;
    }

    private static List<PermissionProfile> replace(List<PermissionProfile> current, PermissionProfile updated) {
        ArrayList<PermissionProfile> result = new ArrayList<>(current);
        result.removeIf(profile -> profile.id().equals(updated.id()));
        result.add(updated);
        result.sort(Comparator.comparing(PermissionProfile::id));
        return List.copyOf(result);
    }

    private void publish(PermissionProfileSettingsState next) {
        state = next;
        listener.accept(next);
    }
}
