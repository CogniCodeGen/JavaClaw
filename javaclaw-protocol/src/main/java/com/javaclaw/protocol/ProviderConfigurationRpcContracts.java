package com.javaclaw.protocol;

import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.ProviderConfiguration;
import com.javaclaw.api.ProviderConfigurationResult;
import com.javaclaw.api.ProviderCredentialChange;
import com.javaclaw.api.ProviderModelPreviewRequest;

/** 完整 Provider 配置及无持久化草稿预览的业务协议；旧分阶段接口保持原语义。 */
public final class ProviderConfigurationRpcContracts {
    /** 原子配置和草稿预览能力。 */
    public static final String CAPABILITY = "core.provider-configuration.v1";
    /** 一次提交连接、凭据、目录和启停状态。 */
    public static final String SAVE_METHOD = "provider/configuration/save";
    /** 按原命令身份查询回执；未找到不代表未提交。 */
    public static final String RESULT_METHOD = "provider/configuration/result";
    /** 创建草稿预览；expected revision 为草稿代次。 */
    public static final String PREVIEW_START_METHOD = "provider/model/preview/start";
    /** 读取当前会话拥有的草稿预览。 */
    public static final String PREVIEW_READ_METHOD = "provider/model/preview/read";
    /** 取消当前会话拥有的草稿预览。 */
    public static final String PREVIEW_CANCEL_METHOD = "provider/model/preview/cancel";
    /** 最终保存专用密封用途，不能复用预览 envelope。 */
    public static final String SAVE_PURPOSE = SAVE_METHOD;
    /** 草稿预览专用密封用途，不能用于最终保存。 */
    public static final String PREVIEW_PURPOSE = PREVIEW_START_METHOD;

    private ProviderConfigurationRpcContracts() {}

    /**
     * 完整保存参数。
     *
     * @param configuration 非敏感完整配置
     * @param secret 仅 REPLACE 时存在的保存专用密文
     */
    public record SavePayload(ProviderConfiguration configuration, Optional<SealedSecret> secret) {
        /** 校验明确凭据意图与密封用途。 */
        public SavePayload {
            Objects.requireNonNull(configuration, "configuration");
            secret = validateSecret(configuration.credentialChange(), secret, SAVE_PURPOSE);
        }
    }

    /**
     * 草稿预览参数。
     *
     * @param request 非敏感连接与编辑来源
     * @param secret 仅 REPLACE 时存在的预览专用密文
     */
    public record PreviewPayload(ProviderModelPreviewRequest request, Optional<SealedSecret> secret) {
        /** 校验明确凭据意图与密封用途。 */
        public PreviewPayload {
            Objects.requireNonNull(request, "request");
            secret = validateSecret(request.credentialChange(), secret, PREVIEW_PURPOSE);
        }
    }

    /**
     * 原提交结果查询；服务端只查询固定的完整配置保存方法。
     *
     * @param idempotencyKey 原保存幂等键
     * @param expectedRevision 原 Provider 期望版本
     * @param requestDigest 包含原密文的完整命令 SHA-256 摘要
     */
    public record ResultPayload(String idempotencyKey, long expectedRevision, String requestDigest) {
        /** 拒绝无法对应原命令的查询身份。 */
        public ResultPayload {
            idempotencyKey =
                    Objects.requireNonNull(idempotencyKey, "idempotencyKey").strip();
            if (idempotencyKey.isEmpty() || idempotencyKey.length() > 200 || expectedRevision < 0) {
                throw new IllegalArgumentException("invalid configuration command identity");
            }
            if (!Objects.requireNonNull(requestDigest, "requestDigest").matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("requestDigest must be SHA-256 hex");
            }
        }
    }

    /**
     * 原命令的查询结果。
     *
     * @param result 找到的已提交事实；为空仅代表尚未找到回执
     */
    public record ResultResponse(Optional<ProviderConfigurationResult> result) {
        /** 禁止空容器混淆未知结果。 */
        public ResultResponse {
            result = Objects.requireNonNull(result, "result");
        }
    }

    private static Optional<SealedSecret> validateSecret(
            ProviderCredentialChange change, Optional<SealedSecret> secret, String purpose) {
        Optional<SealedSecret> checked = Objects.requireNonNull(secret, "secret");
        if ((change == ProviderCredentialChange.REPLACE) != checked.isPresent()) {
            throw new IllegalArgumentException("sealed secret must match credential change");
        }
        if (checked.isPresent() && !purpose.equals(checked.orElseThrow().purpose())) {
            throw new IllegalArgumentException("sealed secret purpose does not match operation");
        }
        return checked;
    }
}
