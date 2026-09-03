package com.javaclaw.desktop.settings;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.CredentialMetadata;
import com.javaclaw.api.ProviderAuthentication;
import com.javaclaw.api.ProviderEndpoint;
import com.javaclaw.api.ProviderLifecycle;
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

    /** @return 根据权威 Provider 与凭据元数据推导的首次配置下一步 */
    ProviderSetupPhase setupPhase() {
        if (selected.isEmpty()) {
            return ProviderSetupPhase.CONNECTION;
        }
        if (draft.authentication() == ProviderAuthentication.API_KEY && credential.isEmpty()) {
            return ProviderSetupPhase.CREDENTIAL;
        }
        ProviderEndpoint endpoint = selected.orElseThrow();
        if (endpoint.spec().models().isEmpty()) {
            return ProviderSetupPhase.MODELS;
        }
        return endpoint.lifecycle() == ProviderLifecycle.ACTIVE
                ? ProviderSetupPhase.COMPLETE
                : ProviderSetupPhase.ENABLE;
    }

    /** @return 当前首次配置进度的用户可读说明 */
    String setupMessage() {
        return switch (setupPhase()) {
            case CONNECTION -> "第 1/4 步：保存禁用的连接壳；模型和凭据不会在此步写入。";
            case CREDENTIAL -> "第 2/4 步：配置访问密钥；无鉴权兼容端点会自动跳过。";
            case MODELS -> draft.models().isEmpty() ? "第 3/4 步：读取模型目录或手工添加模型，并确认每个模型的用途。" : "第 4/4 步：保存模型目录并启用此模型服务。";
            case ENABLE -> "第 4/4 步：确认配置后启用此模型服务。";
            case COMPLETE -> "配置完成；新智能体方案可以显式引用这个精确版本。";
        };
    }
}
