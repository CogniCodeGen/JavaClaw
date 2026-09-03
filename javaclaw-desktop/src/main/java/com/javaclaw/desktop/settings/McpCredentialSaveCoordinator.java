package com.javaclaw.desktop.settings;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import com.javaclaw.api.CredentialMetadata;
import com.javaclaw.api.CredentialRef;
import com.javaclaw.api.McpAuthType;
import com.javaclaw.api.McpEndpointSpec;
import com.javaclaw.client.CommandOptions;

/** 为 MCP 保存操作准备 Vault 引用；敏感字符不进入页面状态。 */
final class McpCredentialSaveCoordinator {
    private final McpSettingsGateway gateway;

    /** @param gateway 强类型 SDK 设置边界 */
    McpCredentialSaveCoordinator(McpSettingsGateway gateway) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
    }

    /**
     * 创建或轮换所需凭据并生成可写配置。
     *
     * @param draft MCP 草稿
     * @param secret PasswordField 临时字符；调用返回前会清零
     * @return 准备结果；新建凭据在业务写失败时必须清理
     */
    CompletionStage<PreparedSave> prepare(McpEndpointDraft draft, char[] secret) {
        McpEndpointDraft checked = Objects.requireNonNull(draft, "draft");
        char[] owned = secret == null ? new char[0] : Arrays.copyOf(secret, secret.length);
        if (secret != null) {
            Arrays.fill(secret, '\0');
        }
        boolean requiresSecret = checked.authType() == McpAuthType.BEARER || checked.authType() == McpAuthType.API_KEY;
        if (!requiresSecret) {
            Arrays.fill(owned, '\0');
            if (owned.length > 0) {
                return failed(new IllegalArgumentException("当前认证方式不接收密钥"));
            }
            return CompletableFuture.completedFuture(
                    new PreparedSave(checked.withCredential(Optional.empty()).toSpec(), Optional.empty()));
        }
        if (checked.credential().isEmpty()) {
            return create(checked, owned);
        }
        if (owned.length == 0) {
            return CompletableFuture.completedFuture(new PreparedSave(checked.toSpec(), Optional.empty()));
        }
        return rotate(checked, owned);
    }

    private CompletionStage<PreparedSave> create(McpEndpointDraft draft, char[] secret) {
        if (secret.length == 0) {
            return failed(new IllegalArgumentException("Bearer 与 API Key 必须填写密钥"));
        }
        CompletionStage<CredentialMetadata> request = gateway.createCredential("mcp", secret, CommandOptions.create(0));
        Arrays.fill(secret, '\0');
        return request.thenApply(metadata -> new PreparedSave(
                draft.withCredential(Optional.of(metadata.reference())).toSpec(), Optional.of(metadata)));
    }

    private CompletionStage<PreparedSave> rotate(McpEndpointDraft draft, char[] secret) {
        CredentialRef reference = draft.credential().orElseThrow();
        CompletionStage<PreparedSave> result = gateway.credential(reference).thenCompose(metadata -> {
            if (metadata.isEmpty()) {
                return failed(new IllegalStateException("CredentialRef 已失效"));
            }
            return gateway.rotateCredential(
                            reference,
                            secret,
                            CommandOptions.create(metadata.orElseThrow().revision()))
                    .thenApply(ignored -> new PreparedSave(draft.toSpec(), Optional.empty()));
        });
        return result.whenComplete((ignored, failure) -> Arrays.fill(secret, '\0'));
    }

    private static <T> CompletionStage<T> failed(Throwable failure) {
        return CompletableFuture.failedFuture(failure);
    }

    /**
     * @param spec 可提交的完整配置
     * @param createdCredential 本次新建且失败时应清理的凭据元数据
     */
    record PreparedSave(McpEndpointSpec spec, Optional<CredentialMetadata> createdCredential) {
        PreparedSave {
            Objects.requireNonNull(spec, "spec");
            createdCredential = Objects.requireNonNull(createdCredential, "createdCredential");
        }
    }
}
