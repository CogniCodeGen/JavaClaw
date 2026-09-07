package com.javaclaw.protocol;

import java.util.Objects;

import com.javaclaw.api.ProviderRef;

/** 独立于旧 Provider DTO 的容量查询与版本化更新契约。 */
public final class ProviderContextRpcContracts {
    /** 容量能力协商名。 */
    public static final String CAPABILITY = "core.provider-context.v1";
    /** 查询精确版本容量。 */
    public static final String READ_METHOD = "provider/modelContext/read";
    /** 创建携带新容量的 Provider revision。 */
    public static final String UPDATE_METHOD = "provider/modelContext/update";

    private ProviderContextRpcContracts() {}

    /**
     * 容量查询。
     *
     * @param provider 精确模型引用
     */
    public record ReadPayload(ProviderRef provider) {
        /** 校验引用。 */
        public ReadPayload {
            Objects.requireNonNull(provider, "provider");
        }
    }
}
