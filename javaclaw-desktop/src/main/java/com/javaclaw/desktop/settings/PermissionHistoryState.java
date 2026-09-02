package com.javaclaw.desktop.settings;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.PermissionProfileDiff;
import com.javaclaw.api.PermissionProfileRef;

/**
 * PermissionProfile 版本历史和服务端 diff 的不可变页面状态。
 *
 * @param phase 异步阶段
 * @param reference 当前查询的精确配置版本
 * @param history revision 升序历史
 * @param diff 最近两个版本的服务端权威差异
 * @param message 状态或错误说明
 * @param epoch 请求代次
 */
public record PermissionHistoryState(
        SettingsLoadState phase,
        Optional<PermissionProfileRef> reference,
        List<PermissionProfile> history,
        Optional<PermissionProfileDiff> diff,
        String message,
        long epoch) {
    /** 复制集合并校验不可变状态。 */
    public PermissionHistoryState {
        phase = Objects.requireNonNull(phase, "phase");
        reference = Objects.requireNonNull(reference, "reference");
        history = List.copyOf(history);
        diff = Objects.requireNonNull(diff, "diff");
        message = Objects.requireNonNullElse(message, "");
    }

    /** @return 尚未选择配置的初始状态 */
    public static PermissionHistoryState initial() {
        return new PermissionHistoryState(
                SettingsLoadState.INITIAL, Optional.empty(), List.of(), Optional.empty(), "", 0);
    }
}
