package com.javaclaw.protocol;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.CredentialMetadata;
import com.javaclaw.api.CredentialRef;

/** Secret Vault Protocol v2 方法的请求与响应 payload。 */
public final class CredentialRpcContracts {
    private CredentialRpcContracts() {}

    /**
     * CredentialRef 非敏感元数据查询。
     *
     * @param reference 待查询的 Vault 引用
     */
    public record ReadPayload(CredentialRef reference) {
        /** 校验引用。 */
        public ReadPayload {
            Objects.requireNonNull(reference, "reference");
        }
    }

    /**
     * 按精确命名空间列出 CredentialRef 非敏感元数据。
     *
     * @param namespace 凭据隔离命名空间
     */
    public record ListPayload(String namespace) {
        /** 校验命名空间。 */
        public ListPayload {
            namespace = identifier(namespace);
        }
    }

    /**
     * 使用当前会话 SealedSecret 创建 Vault 凭据。
     *
     * @param namespace 凭据隔离命名空间
     * @param secret 当前会话封装后的 Secret
     */
    public record CreatePayload(String namespace, SealedSecret secret) {
        /** 校验命名空间与密文 envelope。 */
        public CreatePayload {
            namespace = identifier(namespace);
            Objects.requireNonNull(secret, "secret");
        }
    }

    /**
     * 保持 CredentialRef 不变并轮换 Secret。
     *
     * @param reference 待轮换的 Vault 引用
     * @param secret 当前会话封装后的新 Secret
     */
    public record RotatePayload(CredentialRef reference, SealedSecret secret) {
        /** 校验引用与密文 envelope。 */
        public RotatePayload {
            Objects.requireNonNull(reference, "reference");
            Objects.requireNonNull(secret, "secret");
        }
    }

    /**
     * 清除一个 CredentialRef。
     *
     * @param reference 待清除的 Vault 引用
     */
    public record ClearPayload(CredentialRef reference) {
        /** 校验引用。 */
        public ClearPayload {
            Objects.requireNonNull(reference, "reference");
        }
    }

    /** Vault 主密钥轮换命令的显式空 payload。 */
    public record MasterKeyRotatePayload() {}

    /**
     * Vault 永久重置的危险确认。
     *
     * @param confirmation 必须精确为 {@code RESET VAULT}
     */
    public record ResetPayload(String confirmation) {
        /** 校验调用方已提交明确且不可本地化的危险确认。 */
        public ResetPayload {
            confirmation = Objects.requireNonNull(confirmation, "confirmation");
            if (!confirmation.equals("RESET VAULT")) {
                throw new IllegalArgumentException("Vault reset confirmation is invalid");
            }
        }
    }

    /**
     * 可空 CredentialRef 元数据查询结果。
     *
     * @param credential 查询到的脱敏凭据元数据
     */
    public record ReadResult(Optional<CredentialMetadata> credential) {
        /** 校验可选结果。 */
        public ReadResult {
            credential = Objects.requireNonNull(credential, "credential");
        }
    }

    /**
     * 一个命名空间中的 CredentialRef 非敏感元数据。
     *
     * @param credentials 按 opaque ID 排序的不可变列表
     */
    public record ListResult(List<CredentialMetadata> credentials) {
        /** 复制元数据列表。 */
        public ListResult {
            credentials = List.copyOf(credentials);
        }
    }

    private static String identifier(String value) {
        String normalized = Objects.requireNonNull(value, "namespace").strip();
        if (!normalized.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,239}")) {
            throw new IllegalArgumentException("namespace contains unsupported characters");
        }
        return normalized;
    }
}
