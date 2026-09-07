package com.javaclaw.api;

import java.util.Objects;

/**
 * Role 固定的模型选择，优先于临时 Turn 或 spawn 选择。
 *
 * @param provider 精确 Provider 端点版本和模型，不可为空
 */
public record ModelPreference(ProviderRef provider) {
    /** 校验模型引用；名称映射必须在导入确认前完成。 */
    public ModelPreference {
        Objects.requireNonNull(provider, "provider");
    }
}
