package com.javaclaw.desktop.settings;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.CredentialMetadata;
import com.javaclaw.api.ProviderEndpoint;
import com.javaclaw.api.ProviderStatus;

/**
 * Provider 页面一次完整渲染所需的不可变状态。
 *
 * @param phase 异步阶段
 * @param providers 最新目录
 * @param selected 当前权威 Provider
 * @param baseline 草稿比较基线
 * @param draft 当前草稿
 * @param credential 当前 Secret 脱敏元数据
 * @param providerStatus 最近探测状态
 * @param message 状态或错误说明
 * @param revisionConflict 是否发生 revision 冲突
 * @param epoch 丢弃旧响应的请求代次
 */
public record ProviderSettingsState(
        SettingsLoadState phase,
        List<ProviderEndpoint> providers,
        Optional<ProviderEndpoint> selected,
        ProviderDraft baseline,
        ProviderDraft draft,
        Optional<CredentialMetadata> credential,
        Optional<ProviderStatus> providerStatus,
        String message,
        boolean revisionConflict,
        long epoch) {
    /** 复制目录并校验状态。 */
    public ProviderSettingsState {
        phase = Objects.requireNonNull(phase, "phase");
        providers = List.copyOf(providers);
        selected = Objects.requireNonNull(selected, "selected");
        baseline = Objects.requireNonNull(baseline, "baseline");
        draft = Objects.requireNonNull(draft, "draft");
        credential = Objects.requireNonNull(credential, "credential");
        providerStatus = Objects.requireNonNull(providerStatus, "providerStatus");
        message = Objects.requireNonNullElse(message, "");
    }

    /** @return 页面初始状态 */
    public static ProviderSettingsState initial() {
        ProviderDraft empty = ProviderDraft.empty();
        return new ProviderSettingsState(
                SettingsLoadState.INITIAL,
                List.of(),
                Optional.empty(),
                empty,
                empty,
                Optional.empty(),
                Optional.empty(),
                "",
                false,
                0);
    }

    /** @return 草稿是否偏离比较基线 */
    public boolean dirty() {
        return !draft.equals(baseline);
    }

    /** @return 当前是否正在执行后台动作 */
    public boolean pending() {
        return phase == SettingsLoadState.LOADING || phase == SettingsLoadState.SAVING;
    }
}
