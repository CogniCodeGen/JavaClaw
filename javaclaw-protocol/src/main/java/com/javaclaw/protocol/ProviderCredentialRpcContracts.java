package com.javaclaw.protocol;

import java.util.Objects;

import com.javaclaw.api.CredentialRef;

/** Provider 配置与 Secret Vault 原子复合写入的 Protocol v2 payload。 */
public final class ProviderCredentialRpcContracts {
    /** Provider Secret 的固定会话密封用途。 */
    public static final String SET_PURPOSE = "provider/credential/set";

    private ProviderCredentialRpcContracts() {}

    /**
     * 创建并绑定或原地轮换 Provider Secret。
     *
     * @param providerId Provider 稳定标识
     * @param providerExpectedRevision Provider 当前版本
     * @param credentialExpectedRevision 未绑定时为 0，轮换时为当前凭据版本
     * @param secret 当前会话封装后的 Secret
     */
    public record SetPayload(
            String providerId, long providerExpectedRevision, long credentialExpectedRevision, SealedSecret secret) {
        /** 校验两个独立 revision 和密文 envelope。 */
        public SetPayload {
            providerId = identifier(providerId, "providerId");
            providerExpectedRevision = positive(providerExpectedRevision, "providerExpectedRevision");
            credentialExpectedRevision = nonNegative(credentialExpectedRevision, "credentialExpectedRevision");
            secret = Objects.requireNonNull(secret, "secret");
            if (!SET_PURPOSE.equals(secret.purpose())) {
                throw new IllegalArgumentException("sealed secret purpose does not match provider credential set");
            }
        }
    }

    /**
     * 原子解除 Provider 引用并清除 Secret。
     *
     * @param providerId Provider 稳定标识
     * @param providerExpectedRevision Provider 当前版本
     * @param credential 待清除的精确引用
     * @param credentialExpectedRevision 凭据当前版本
     */
    public record ClearPayload(
            String providerId,
            long providerExpectedRevision,
            CredentialRef credential,
            long credentialExpectedRevision) {
        /** 校验两个独立 revision 与 Provider namespace。 */
        public ClearPayload {
            providerId = identifier(providerId, "providerId");
            providerExpectedRevision = positive(providerExpectedRevision, "providerExpectedRevision");
            credential = Objects.requireNonNull(credential, "credential");
            if (!"provider".equals(credential.namespace())) {
                throw new IllegalArgumentException("Provider credential namespace must be provider");
            }
            credentialExpectedRevision = positive(credentialExpectedRevision, "credentialExpectedRevision");
        }
    }

    private static String identifier(String value, String name) {
        String checked = Objects.requireNonNull(value, name).strip();
        if (!checked.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,239}")) {
            throw new IllegalArgumentException(name + " contains unsupported characters");
        }
        return checked;
    }

    private static long positive(long value, String name) {
        if (value < 1) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static long nonNegative(long value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        return value;
    }
}
