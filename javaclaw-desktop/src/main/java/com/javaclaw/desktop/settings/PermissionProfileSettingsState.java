package com.javaclaw.desktop.settings;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.PermissionProfileRef;

/**
 * PermissionProfile 页面的不可变状态。
 *
 * @param phase 异步阶段
 * @param profiles 最新目录
 * @param selected 当前权威配置；clone 新建时为空
 * @param baseline 草稿比较基线
 * @param draft 当前草稿
 * @param cloneSource clone 新建时的精确源版本
 * @param message 状态或错误说明
 * @param revisionConflict 是否发生 revision 冲突
 * @param epoch 请求代次
 */
public record PermissionProfileSettingsState(
        SettingsLoadState phase,
        List<PermissionProfile> profiles,
        Optional<PermissionProfile> selected,
        PermissionProfileDraft baseline,
        PermissionProfileDraft draft,
        Optional<PermissionProfileRef> cloneSource,
        String message,
        boolean revisionConflict,
        long epoch) {
    /** 复制目录并校验状态。 */
    public PermissionProfileSettingsState {
        phase = Objects.requireNonNull(phase, "phase");
        profiles = List.copyOf(profiles);
        selected = Objects.requireNonNull(selected, "selected");
        baseline = Objects.requireNonNull(baseline, "baseline");
        draft = Objects.requireNonNull(draft, "draft");
        cloneSource = Objects.requireNonNull(cloneSource, "cloneSource");
        message = Objects.requireNonNullElse(message, "");
    }

    /** @return 页面初始状态 */
    public static PermissionProfileSettingsState initial() {
        PermissionProfileDraft empty = new PermissionProfileDraft(
                "",
                "",
                "",
                false,
                false,
                "",
                "",
                true,
                "",
                false,
                30,
                java.util.Set.of(),
                com.javaclaw.api.ToolRisk.READ_ONLY,
                com.javaclaw.api.ApprovalRequirement.RISKY,
                256,
                16,
                1,
                32);
        return new PermissionProfileSettingsState(
                SettingsLoadState.INITIAL, List.of(), Optional.empty(), empty, empty, Optional.empty(), "", false, 0);
    }

    /** @return 草稿是否尚未保存 */
    public boolean dirty() {
        return !baseline.equals(draft);
    }

    /** @return 是否正在执行后台动作 */
    public boolean pending() {
        return phase == SettingsLoadState.LOADING || phase == SettingsLoadState.SAVING;
    }

    /** @return 当前选中的内置 standard 是否只读 */
    public boolean standardReadOnly() {
        return selected.map(profile -> profile.id().equals("standard")).orElse(false);
    }

    /** @return 当前是否只允许填写 clone 新 ID */
    public boolean cloning() {
        return cloneSource.isPresent();
    }
}
