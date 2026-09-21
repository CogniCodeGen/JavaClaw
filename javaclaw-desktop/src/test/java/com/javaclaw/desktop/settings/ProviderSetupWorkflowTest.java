package com.javaclaw.desktop.settings;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.CredentialRef;
import com.javaclaw.api.ProviderAdapter;
import com.javaclaw.api.ProviderAuthentication;
import com.javaclaw.api.ProviderCredentialChange;
import com.javaclaw.api.ProviderEndpoint;
import com.javaclaw.api.ProviderLifecycle;
import com.javaclaw.api.ProviderModelPreviewResult;
import com.javaclaw.api.ProviderModelPurpose;
import com.javaclaw.api.ProviderModelSpec;
import com.javaclaw.api.ProviderReasoningSummary;
import com.javaclaw.api.VaultLockReason;
import com.javaclaw.api.VaultState;
import com.javaclaw.api.VaultStatus;
import com.javaclaw.client.RemoteRpcException;
import com.javaclaw.protocol.JsonRpcError;
import com.javaclaw.protocol.ProtocolErrorCode;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderSetupWorkflowTest {
    static final List<ProviderModelSpec> MODELS = List.of(new ProviderModelSpec(
            "embedding-only", "独立向量模型", Set.of(ProviderModelPurpose.EMBEDDING), OptionalInt.of(768)));

    @Test
    void 下一步和目录预览零写入且最后一次保存完整向量配置不应用模型() {
        ProviderConfigurationTestGateway gateway = new ProviderConfigurationTestGateway();
        ProviderSetupWorkflow workflow = new ProviderSetupWorkflow(gateway, "new-provider");
        char[] secret = "temporary-key".toCharArray();
        workflow.connect(draft(ProviderAuthentication.API_KEY), secret)
                .toCompletableFuture()
                .join();
        workflow.discover().toCompletableFuture().join();
        assertEquals(0, gateway.saved.size());
        assertEquals(0, gateway.preparations);
        assertEquals(0, gateway.providerCredentialSetCalls);
        assertTrue(workflow.endpoint().isEmpty());
        var result = workflow.save(MODELS, true).toCompletableFuture().join();
        assertEquals(MODELS, result.provider().spec().models());
        assertEquals(ProviderLifecycle.ACTIVE, result.provider().lifecycle());
        assertEquals(ProviderCredentialChange.REPLACE, gateway.configuration.credentialChange());
        assertEquals(1, gateway.preparations);
        assertEquals(1, gateway.saved.size());
        assertEquals(0, gateway.uses);
        assertArrayEquals(new char[secret.length], secret);
        assertArrayEquals(new char[gateway.submittedSecret.length], gateway.submittedSecret);
        assertFalse(workflow.pending());
    }

    @Test
    void 新建无鉴权使用KEEP且可保存为禁用服务() {
        var gateway = new ProviderConfigurationTestGateway();
        var workflow = new ProviderSetupWorkflow(gateway);
        workflow.connect(draft(ProviderAuthentication.NONE), new char[0])
                .toCompletableFuture()
                .join();
        workflow.discover().toCompletableFuture().join();
        workflow.save(MODELS, false).toCompletableFuture().join();
        assertEquals(ProviderCredentialChange.KEEP, gateway.previews.getFirst().credentialChange());
        assertEquals(ProviderCredentialChange.KEEP, gateway.configuration.credentialChange());
        assertEquals(ProviderLifecycle.DISABLED, gateway.configuration.lifecycle());
        assertEquals(0, gateway.vaultStatusCalls);
    }

    @Test
    void 编辑无鉴权配置不发清除凭据意图() {
        var gateway = new ProviderConfigurationTestGateway();
        var source = source(gateway, ProviderAuthentication.NONE, Optional.empty());
        var workflow = new ProviderSetupWorkflow(gateway, source, 0);
        workflow.connect(ProviderDraft.from(source), new char[0])
                .toCompletableFuture()
                .join();
        workflow.save(MODELS, true).toCompletableFuture().join();
        assertEquals(ProviderCredentialChange.KEEP, gateway.configuration.credentialChange());
        assertEquals(source.revision(), gateway.configuration.expectedRevision());
    }

    @Test
    void 目录失败仍可手动保存且读取中不锁住编辑() {
        var gateway = new ProviderConfigurationTestGateway();
        var workflow = connected(gateway);
        var read = new CompletableFuture<ProviderModelPreviewResult>();
        gateway.previewResponses.add(read);
        workflow.discover();
        assertTrue(workflow.previewing());
        assertFalse(workflow.pending());
        read.completeExceptionally(new IllegalStateException("目录不支持"));
        workflow.save(MODELS, true).toCompletableFuture().join();
        assertEquals(1, gateway.saved.size());
        assertTrue(gateway.cancellations.getFirst().isCancelled());
    }

    @Test
    void 保存前取消读取且旧预览结果不进入当前草稿() {
        var gateway = new ProviderConfigurationTestGateway();
        var workflow = connected(gateway);
        var response = new CompletableFuture<ProviderModelPreviewResult>();
        gateway.previewResponses.add(response);
        var reading = workflow.discover();
        workflow.save(MODELS, true).toCompletableFuture().join();
        response.complete(gateway.previewResult(gateway.previews.getFirst()));
        assertThrows(
                CompletionException.class, () -> reading.toCompletableFuture().join());
        assertTrue(gateway.cancellations.getFirst().isCancelled());
    }

    @Test
    void 旧服务器能力不足时不退回分阶段保存并清空输入数组() {
        var gateway = new ProviderConfigurationTestGateway();
        gateway.supported = CompletableFuture.completedFuture(false);
        var workflow = new ProviderSetupWorkflow(gateway);
        char[] secret = "secret".toCharArray();
        assertThrows(
                CompletionException.class,
                () -> workflow.connect(draft(ProviderAuthentication.API_KEY), secret)
                        .toCompletableFuture()
                        .join());
        assertArrayEquals(new char[secret.length], secret);
        assertEquals(0, gateway.saved.size());
        assertEquals(0, gateway.providerCredentialSetCalls);
    }

    @Test
    void 已有密钥默认保留且改变目的地址要求替换() {
        var gateway = new ProviderConfigurationTestGateway();
        var source =
                source(gateway, ProviderAuthentication.API_KEY, Optional.of(new CredentialRef("provider", "existing")));
        var workflow = new ProviderSetupWorkflow(gateway, source, 4);
        workflow.connect(ProviderDraft.from(source), new char[0])
                .toCompletableFuture()
                .join();
        workflow.save(MODELS, true).toCompletableFuture().join();
        assertEquals(ProviderCredentialChange.KEEP, gateway.configuration.credentialChange());
        assertEquals(4, gateway.configuration.credentialExpectedRevision());
        var changed = new ProviderSetupWorkflow(gateway, source, 4);
        ProviderDraft other = draft(ProviderAuthentication.API_KEY);
        assertThrows(
                CompletionException.class,
                () -> changed.connect(other, new char[0]).toCompletableFuture().join());
        changed.connect(other, "replacement".toCharArray())
                .toCompletableFuture()
                .join();
        changed.save(MODELS, true).toCompletableFuture().join();
        assertEquals(ProviderCredentialChange.REPLACE, gateway.configuration.credentialChange());
    }

    @Test
    void 改无鉴权必须确认且清除只随最终保存发生() {
        var gateway = new ProviderConfigurationTestGateway();
        var source =
                source(gateway, ProviderAuthentication.API_KEY, Optional.of(new CredentialRef("provider", "existing")));
        var workflow = new ProviderSetupWorkflow(gateway, source, 2);
        var draft = draft(ProviderAuthentication.NONE);
        assertThrows(
                CompletionException.class,
                () -> workflow.connect(draft, new char[0]).toCompletableFuture().join());
        workflow.connect(draft, new char[0], false, true).toCompletableFuture().join();
        assertEquals(0, gateway.saved.size());
        workflow.save(MODELS, true).toCompletableFuture().join();
        assertEquals(ProviderCredentialChange.CLEAR, gateway.configuration.credentialChange());
        assertTrue(gateway.committed.provider().spec().credential().isEmpty());
    }

    @Test
    void 版本冲突是明确失败保留非秘密草稿而替换密钥必须重输() {
        var gateway = new ProviderConfigurationTestGateway();
        var workflow = connected(gateway);
        gateway.saveResponses.add(CompletableFuture.failedFuture(new RemoteRpcException(
                new JsonRpcError(ProtocolErrorCode.REVISION_CONFLICT, "版本冲突", Optional.empty()))));
        assertThrows(
                CompletionException.class,
                () -> workflow.save(MODELS, true).toCompletableFuture().join());
        assertFalse(workflow.unknown());
        assertFalse(workflow.pending());
        assertTrue(workflow.needsSecretInput());
        assertThrows(
                CompletionException.class,
                () -> workflow.save(MODELS, true).toCompletableFuture().join());
        assertEquals(1, gateway.preparations);
        assertEquals(0, gateway.queried.size());
    }

    @Test
    void 保存结果未知仅查询同一密文回执且缺失回执不自动重放() {
        var gateway = new ProviderConfigurationTestGateway();
        var workflow = connected(gateway);
        gateway.saveResponses.add(CompletableFuture.failedFuture(new IllegalStateException("连接中断")));
        assertThrows(
                CompletionException.class,
                () -> workflow.save(MODELS, true).toCompletableFuture().join());
        assertTrue(workflow.unknown());
        assertFalse(workflow.needsSecretInput());
        assertTrue(workflow.checkResult().toCompletableFuture().join().isEmpty());
        assertTrue(workflow.unknown());
        assertThrows(
                CompletionException.class,
                () -> workflow.save(MODELS, true).toCompletableFuture().join());
        gateway.committed = gateway.result(gateway.configuration);
        assertTrue(workflow.checkResult().toCompletableFuture().join().isPresent());
        assertFalse(workflow.unknown());
        assertFalse(workflow.needsSecretInput());
        assertEquals(1, gateway.preparations);
        assertEquals(1, gateway.saved.size());
        assertSame(gateway.saved.getFirst(), gateway.queried.getFirst());
        assertSame(gateway.saved.getFirst(), gateway.queried.getLast());
    }

    @Test
    void 密钥库锁定刷新只发生于最终提交且失败不留空服务() {
        var gateway = new ProviderConfigurationTestGateway();
        gateway.vaultStatus = new VaultStatus(
                VaultState.LOCKED, VaultLockReason.MASTER_KEY_MISSING, 0, false, java.time.Instant.EPOCH);
        gateway.refreshedVaultStatus = gateway.vaultStatus;
        var workflow = connected(gateway);
        assertEquals(0, gateway.vaultStatusCalls);
        assertThrows(
                CompletionException.class,
                () -> workflow.save(MODELS, true).toCompletableFuture().join());
        assertEquals(1, gateway.vaultRefreshCalls);
        assertEquals(0, gateway.preparations);
        assertEquals(0, gateway.saved.size());
        assertTrue(workflow.endpoint().isEmpty());
    }

    @Test
    void 关闭窗口取消目录并拒绝继续保存() {
        var gateway = new ProviderConfigurationTestGateway();
        var workflow = connected(gateway);
        gateway.previewResponses.add(new CompletableFuture<>());
        workflow.discover();
        workflow.close();
        assertTrue(gateway.cancellations.getFirst().isCancelled());
        assertThrows(
                CompletionException.class,
                () -> workflow.save(MODELS, true).toCompletableFuture().join());
        assertEquals(0, gateway.preparations);
    }

    @Test
    void 会话在密封任务完成前失效时不向新会话提交旧请求() {
        var gateway = new ProviderConfigurationTestGateway();
        var pending = new CompletableFuture<com.javaclaw.client.facade.PreparedProviderConfiguration>();
        gateway.preparationResponse = pending;
        var workflow = new ProviderSetupWorkflow(gateway);
        workflow.connect(draft(ProviderAuthentication.NONE), new char[0])
                .toCompletableFuture()
                .join();
        var saving = workflow.save(MODELS, true);
        gateway.invalidated.run();
        var payload = new com.javaclaw.protocol.ProviderConfigurationRpcContracts.SavePayload(
                gateway.configuration, Optional.empty());
        pending.complete(new com.javaclaw.client.facade.PreparedProviderConfiguration(
                payload, com.javaclaw.client.CommandOptions.create(0)));
        assertThrows(
                CompletionException.class, () -> saving.toCompletableFuture().join());
        assertEquals(0, gateway.saved.size());
        assertFalse(workflow.unknown());
        assertFalse(workflow.pending());
        workflow.close();
        assertTrue(gateway.subscriptionClosed);
    }

    @Test
    void 会话失效取消读取清理草稿密钥并禁止重新预览或提交() {
        var gateway = new ProviderConfigurationTestGateway();
        var workflow = connected(gateway);
        gateway.previewResponses.add(new CompletableFuture<>());
        workflow.discover();
        gateway.invalidated.run();
        assertTrue(gateway.cancellations.getFirst().isCancelled());
        assertFalse(workflow.supported());
        assertThrows(
                CompletionException.class,
                () -> workflow.discover().toCompletableFuture().join());
        assertThrows(
                CompletionException.class,
                () -> workflow.save(MODELS, true).toCompletableFuture().join());
        assertEquals(0, gateway.preparations);
        assertEquals(0, gateway.saved.size());
    }

    static ProviderSetupWorkflow connected(ProviderConfigurationTestGateway gateway) {
        var workflow = new ProviderSetupWorkflow(gateway, "new-provider");
        workflow.connect(draft(ProviderAuthentication.API_KEY), "temporary-key".toCharArray())
                .toCompletableFuture()
                .join();
        return workflow;
    }

    static ProviderDraft draft(ProviderAuthentication authentication) {
        return new ProviderDraft(
                "",
                "模型服务",
                ProviderAdapter.OPENAI_COMPATIBLE,
                "http://localhost:11434/v1",
                authentication,
                List.of(),
                Optional.empty(),
                60,
                0,
                "",
                "",
                "",
                ProviderReasoningSummary.AUTO,
                ProviderLifecycle.DISABLED);
    }

    private static ProviderEndpoint source(
            ProviderConfigurationTestGateway gateway, ProviderAuthentication auth, Optional<CredentialRef> credential) {
        var original = gateway.providers.getFirst();
        var draft = draft(auth);
        var spec = new com.javaclaw.api.ProviderEndpointSpec(
                draft.displayName(),
                draft.adapter(),
                Optional.of(java.net.URI.create("https://existing.example/v1")),
                auth,
                MODELS,
                credential,
                java.time.Duration.ofSeconds(60),
                0,
                draft.toSpec().options());
        var value = new ProviderEndpoint(
                original.id(), 3, ProviderLifecycle.DISABLED, spec, original.createdAt(), original.updatedAt());
        gateway.providers.set(0, value);
        return value;
    }
}
