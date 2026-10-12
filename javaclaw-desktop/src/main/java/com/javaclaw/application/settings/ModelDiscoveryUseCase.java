package com.javaclaw.application.settings;

import com.javaclaw.application.settings.ModelDiscoveryApplicationService.DiscoveryRequest;
import com.javaclaw.application.settings.ModelDiscoveryApplicationService.DiscoveryResult;
import com.javaclaw.application.settings.ModelProviderCatalog.Capability;
import com.javaclaw.config.CredentialUsage;

import java.net.URI;
import java.util.Objects;

/** 校验未保存的表单输入，再向模型目录端口发起一次读取。 */
public final class ModelDiscoveryUseCase implements ModelDiscoveryApplicationService {
    private final ModelProviderCatalog providers;
    private final ModelDiscoveryPort discovery;

    public ModelDiscoveryUseCase(ModelProviderCatalog providers, ModelDiscoveryPort discovery) {
        this.providers = Objects.requireNonNull(providers, "providers");
        this.discovery = Objects.requireNonNull(discovery, "discovery");
    }

    @Override
    public DiscoveryResult discover(DiscoveryRequest request) throws InterruptedException {
        Objects.requireNonNull(request, "request");
        var provider = providers.find(request.provider());
        if (provider.isEmpty()) return DiscoveryResult.failed("请选择支持的模型提供商");
        if (provider.get().localManaged()) return DiscoveryResult.failed("本地托管模型请从档案列表选择");
        Capability capability = request.usage() == Usage.CHAT ? Capability.CHAT : Capability.EMBEDDING;
        if (!provider.get().capabilities().contains(capability)) {
            return DiscoveryResult.failed("该提供商不支持" + (request.usage() == Usage.CHAT ? "对话" : "嵌入") + "模型");
        }
        URI base;
        try {
            base = URI.create(request.baseUrl());
            if (!("http".equalsIgnoreCase(base.getScheme())
                    || "https".equalsIgnoreCase(base.getScheme()))
                    || base.getHost() == null || base.getRawUserInfo() != null
                    || base.getRawQuery() != null || base.getRawFragment() != null) {
                return DiscoveryResult.failed("API 地址需为不含账号、查询参数的 http:// 或 https:// 地址");
            }
        } catch (IllegalArgumentException invalid) {
            return DiscoveryResult.failed("API 地址需为有效的 http:// 或 https:// 地址");
        }
        try {
            CredentialUsage.requirePlaintext(request.apiKey());
        } catch (CredentialUsage.UnreadableCredentialException unreadable) {
            return DiscoveryResult.failed(unreadable.getMessage());
        }
        if (request.apiKey().chars().anyMatch(value -> value < 0x20 || value == 0x7f)) {
            return DiscoveryResult.failed("API Key 格式无效");
        }
        return discovery.discover(new DiscoveryRequest(provider.get().id(), base.toASCIIString(),
                request.apiKey(), request.usage()));
    }
}
