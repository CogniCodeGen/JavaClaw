package com.javaclaw.desktop.settings;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.EmbeddingBinding;
import com.javaclaw.api.ProviderAdapter;
import com.javaclaw.api.ProviderEndpoint;
import com.javaclaw.api.ProviderModelDiscoveryCandidate;
import com.javaclaw.api.ProviderModelSpec;
import com.javaclaw.api.ProviderStatus;
import com.javaclaw.api.ProviderVerificationResult;

/**
 * 结构化 Provider 模型编辑器的不可变渲染状态。
 *
 * @param adapter 当前 Adapter
 * @param models 待保存的逐模型目录
 * @param endpoint 当前已保存的精确 Provider；新建草稿时为空
 * @param candidates 远程发现候选；未经确认不会进入 models
 * @param embeddingBinding 当前本地安装默认 Embedding 绑定
 * @param providerStatus 最近一次本地检查
 * @param verificationResults 按用途保留的最近显式计费验证
 * @param pending 是否正在读取目录或写入绑定
 * @param message 发现或绑定状态
 */
record ProviderModelCatalogState(
        ProviderAdapter adapter,
        List<ProviderModelSpec> models,
        Optional<ProviderEndpoint> endpoint,
        List<ProviderModelDiscoveryCandidate> candidates,
        Optional<EmbeddingBinding> embeddingBinding,
        Optional<ProviderStatus> providerStatus,
        List<ProviderVerificationResult> verificationResults,
        boolean pending,
        String message) {
    ProviderModelCatalogState {
        Objects.requireNonNull(adapter, "adapter");
        models = List.copyOf(models);
        endpoint = Objects.requireNonNull(endpoint, "endpoint");
        candidates = List.copyOf(candidates);
        embeddingBinding = Objects.requireNonNull(embeddingBinding, "embeddingBinding");
        providerStatus = Objects.requireNonNull(providerStatus, "providerStatus");
        verificationResults = List.copyOf(verificationResults);
        message = Objects.requireNonNullElse(message, "");
    }
}
