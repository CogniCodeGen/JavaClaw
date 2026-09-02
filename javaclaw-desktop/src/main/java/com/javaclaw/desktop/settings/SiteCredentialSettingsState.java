package com.javaclaw.desktop.settings;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.CredentialMetadata;

/**
 * Site HTTP Secret 管理分区的不可变状态。
 *
 * @param phase 异步阶段
 * @param credentials 仅含 opaque 引用、revision 和更新时间的脱敏目录
 * @param selected 当前选中的脱敏元数据
 * @param message 操作结果或错误
 * @param epoch 请求代次
 */
record SiteCredentialSettingsState(
        SettingsLoadState phase,
        List<CredentialMetadata> credentials,
        Optional<CredentialMetadata> selected,
        String message,
        long epoch) {
    SiteCredentialSettingsState {
        phase = Objects.requireNonNull(phase, "phase");
        credentials = List.copyOf(credentials);
        selected = Objects.requireNonNull(selected, "selected");
        message = Objects.requireNonNullElse(message, "");
    }

    static SiteCredentialSettingsState initial() {
        return new SiteCredentialSettingsState(SettingsLoadState.INITIAL, List.of(), Optional.empty(), "", 0);
    }
}
