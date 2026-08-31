package com.javaclaw.sdk;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.javaclaw.sdk.model.ProfileInfo;
import com.javaclaw.sdk.model.ProviderInfo;
import com.javaclaw.sdk.model.SecretMetadata;

/** 版本化 Profile、云 Provider 和凭据管理客户端；不暴露 Provider 实现，远程失败通过 Future 异常返回。 */
public final class ModelClient {
    private final ProtocolClient protocol;
    private final SdkProtocolMapper mapper;

    ModelClient(ProtocolClient protocol, SdkProtocolMapper mapper) {
        this.protocol = protocol;
        this.mapper = mapper;
    }

    /** 查询模型配置能力，不发起云模型请求或返回凭据。 */
    public CompletableFuture<List<com.javaclaw.sdk.model.ModelCapabilityInfo>> listModels() {
        return protocol.listModels().thenApply(mapper::modelCapabilities);
    }

    /** 查询工具元数据，实际可见工具与可执行权限由每个 Turn 的快照决定。 */
    public CompletableFuture<List<com.javaclaw.sdk.model.ToolCapabilityInfo>> listTools() {
        return protocol.listTools().thenApply(mapper::toolCapabilities);
    }

    /** 列出已保存的执行 Profile，不返回凭据。 */
    public CompletableFuture<List<ProfileInfo>> listProfiles() {
        return protocol.listProfiles()
                .thenApply(values -> values.stream().map(mapper::profile).toList());
    }

    /** 读取完整 Profile 快照；不存在时 Future 以 RPC 错误完成。 */
    public CompletableFuture<ProfileInfo> readProfile(String id) {
        return protocol.readProfile(id).thenApply(mapper::profile);
    }

    /** 预览人设编辑层、内置模板版本与能力匹配；不会调用模型或改变配置。 */
    public CompletableFuture<com.javaclaw.sdk.model.PromptPreview> previewPrompt(String profileId, String workspaceId) {
        return protocol.previewPrompt(profileId, workspaceId)
                .thenApply(value -> new com.javaclaw.sdk.model.PromptPreview(
                        value.profileId(),
                        value.revision(),
                        value.editablePrompt(),
                        value.purpose(),
                        value.layers().stream()
                                .map(layer -> new com.javaclaw.sdk.model.PromptPreview.Layer(
                                        layer.id(), layer.version(), layer.sha256()))
                                .toList(),
                        value.tools(),
                        value.warnings()));
    }

    /** 创建无工具草稿 Turn，返回执行引用；只读其 PromptDraftItemContent 不会保存 Profile，需用户确认后显式 putProfile。 */
    public CompletableFuture<com.javaclaw.sdk.model.TurnInfo> optimizePrompt(
            String threadId, String profileId, String draft, long expectedRevision, String idempotencyKey) {
        return protocol.optimizePrompt(threadId, profileId, draft, expectedRevision, idempotencyKey)
                .thenApply(mapper::turn);
    }

    /** 按 expectedRevision 创建或更新 Profile；服务端保留权限和预算校验，幂等键用于去重。 */
    public CompletableFuture<ProfileInfo> putProfile(
            ProfileInfo profile, long expectedRevision, String idempotencyKey) {
        return protocol.putProfile(mapper.profile(profile), expectedRevision, idempotencyKey)
                .thenApply(mapper::profile);
    }

    /** 按版本删除 Profile，不改变已经开始的 Turn 配置快照。 */
    public CompletableFuture<Boolean> deleteProfile(String id, long expectedRevision, String idempotencyKey) {
        return protocol.deleteProfile(id, expectedRevision, idempotencyKey);
    }

    /** 读取云 Provider 配置与 credential metadata，不返回 API Key。 */
    public CompletableFuture<List<ProviderInfo>> listProviders() {
        return protocol.listProviders()
                .thenApply(values -> values.stream().map(mapper::provider).toList());
    }

    /** 按版本保存非敏感模型配置；凭据必须通过 setCredential 独立提交。 */
    public CompletableFuture<ProviderInfo> configureProvider(
            String id, Map<String, String> config, long expectedRevision, String idempotencyKey) {
        return protocol.configureProvider(id, config, expectedRevision, idempotencyKey)
                .thenApply(mapper::provider);
    }

    /** 设置云模型凭据并只返回 metadata；调用方应在请求完成后清空自己持有的 char[]，不得记录请求内容。 */
    public CompletableFuture<SecretMetadata> setCredential(String id, char[] credential, String idempotencyKey) {
        return protocol.setProviderCredential(id, credential, idempotencyKey).thenApply(mapper::secret);
    }

    /** 按预期凭据版本清除 Provider 凭据；相同幂等键可安全重试。 */
    public CompletableFuture<Boolean> clearCredential(String id, long expectedRevision, String idempotencyKey) {
        return protocol.clearProviderCredential(id, expectedRevision, idempotencyKey);
    }
}
