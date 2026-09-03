package com.javaclaw.server.rpc;

import java.util.Objects;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.ProviderProfileRpcContracts;
import com.javaclaw.server.profile.ProfilePresetCatalog;

/** 将只读 Agent Profile 预设目录映射为 Protocol v2 查询。 */
public final class ProfilePresetRpcHandlers {
    private final ProfilePresetCatalog presets;
    private final CanonicalJson json;

    /**
     * 创建 Profile 预设 handler。
     *
     * @param presets 应用代码维护的预设目录
     * @param json 共享 JSON codec
     */
    public ProfilePresetRpcHandlers(ProfilePresetCatalog presets, CanonicalJson json) {
        this.presets = Objects.requireNonNull(presets, "presets");
        this.json = Objects.requireNonNull(json, "json");
    }

    /**
     * 注册 Profile 预设查询。
     *
     * @param routes Router Builder
     */
    public void register(RpcRouter.Builder routes) {
        Objects.requireNonNull(routes, "routes").register("profile/preset/list", this::list);
    }

    private CanonicalPayload list(CanonicalPayload params) {
        requireEmpty(params);
        return json.encode(new ProviderProfileRpcContracts.AgentProfilePresetListResult(presets.list()));
    }

    private static void requireEmpty(CanonicalPayload params) {
        if (!"{}".equals(params.json())) {
            throw new IllegalArgumentException("params 必须为空对象");
        }
    }
}
