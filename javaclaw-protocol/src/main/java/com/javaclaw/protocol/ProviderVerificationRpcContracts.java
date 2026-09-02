package com.javaclaw.protocol;

import java.util.Objects;

import com.javaclaw.api.ProviderRef;

/** Provider 显式计费 round-trip 的 Protocol v2 契约。 */
public final class ProviderVerificationRpcContracts {
    /** 验证命令的方法名。 */
    public static final String METHOD = "provider/roundTrip/verify";

    /** UI 与服务端共同要求的精确危险确认文本。 */
    public static final String BILLING_CONFIRMATION = "我确认本次模型验证可能产生费用";

    private ProviderVerificationRpcContracts() {}

    /**
     * 显式计费验证参数。
     *
     * @param provider 已保存的精确 Provider 与模型版本
     * @param billingConfirmed 必须明确为 true
     * @param confirmation 必须精确匹配固定危险确认文本
     */
    public record VerifyPayload(ProviderRef provider, boolean billingConfirmed, String confirmation) {
        /** 校验双重危险确认，禁止模糊匹配或仅依赖 UI。 */
        public VerifyPayload {
            Objects.requireNonNull(provider, "provider");
            confirmation = Objects.requireNonNull(confirmation, "confirmation");
            if (!billingConfirmed || !BILLING_CONFIRMATION.equals(confirmation)) {
                throw new IllegalArgumentException("Provider billing confirmation is required");
            }
        }
    }
}
