package com.javaclaw.desktop.settings;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.ProviderAdapter;
import com.javaclaw.api.ProviderAuthentication;
import com.javaclaw.api.ProviderEndpoint;
import com.javaclaw.api.ProviderLifecycle;
import com.javaclaw.api.ProviderModelPurpose;
import com.javaclaw.api.ProviderModelSpec;
import com.javaclaw.api.ProviderReasoningSummary;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.WorkspaceId;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderSetupWorkflowTest {
    private static final ProviderSetupTarget TARGET =
            new ProviderSetupTarget(Optional.of(WorkspaceId.random()), Optional.of(ThreadId.random()), "项目 A");
    private static final List<ProviderModelSpec> MODELS = List.of(model("model-a"), model("model-b"));

    @Test
    void 保存并使用按顺序绑定密钥启用多模型并应用保存返回的精确版本() {
        ApplyingGateway gateway = new ApplyingGateway();
        ProviderSetupWorkflow workflow = new ProviderSetupWorkflow(gateway, "new-provider");
        char[] secret = "temporary-key".toCharArray();

        workflow.connect(draft(ProviderAuthentication.API_KEY), secret)
                .toCompletableFuture()
                .join();
        workflow.discover().toCompletableFuture().join();
        ProviderRef reference = workflow.save(MODELS, "model-b", true, TARGET)
                .toCompletableFuture()
                .join();

        assertArrayEquals(new char[secret.length], secret);
        assertArrayEquals(new char[gateway.lastProviderSecret.length], gateway.lastProviderSecret);
        assertEquals(ProviderLifecycle.DISABLED, gateway.lastProviderCreateLifecycle);
        assertEquals(new ProviderRef("new-provider", 3, "model-b"), reference);
        assertEquals(reference, gateway.applied);
        assertEquals(TARGET, gateway.target);
        assertEquals(1, gateway.providerCredentialSetCalls);
        assertEquals(0, gateway.providerVerificationCalls);
        assertEquals(2, workflow.endpoint().orElseThrow().spec().models().size());
        assertFalse(workflow.pending());
    }

    @Test
    void 密钥失败后重试从已保存连接继续且不重复创建连接() {
        ApplyingGateway gateway = new ApplyingGateway();
        ProviderSetupWorkflow workflow = new ProviderSetupWorkflow(gateway, "retry-key-provider");
        gateway.nextFailure = new IllegalStateException("凭据库暂不可用");

        assertThrows(
                CompletionException.class,
                () -> workflow.connect(draft(ProviderAuthentication.API_KEY), "key".toCharArray())
                        .toCompletableFuture()
                        .join());
        assertEquals(1, workflow.endpoint().orElseThrow().revision());
        workflow.connect(draft(ProviderAuthentication.API_KEY), "key".toCharArray())
                .toCompletableFuture()
                .join();

        assertEquals(
                1,
                gateway.providers.stream()
                        .filter(value -> value.id().equals("retry-key-provider"))
                        .count());
        assertEquals(2, workflow.endpoint().orElseThrow().revision());
        assertEquals(2, gateway.providerCredentialSetCalls);
    }

    @Test
    void 应用失败重试只应用同一模型版本而不重复保存或绑定密钥() {
        ApplyingGateway gateway = new ApplyingGateway();
        ProviderSetupWorkflow workflow = connected(gateway);
        gateway.applyFailure = new IllegalStateException("对话创建暂不可用");

        assertThrows(
                CompletionException.class,
                () -> workflow.save(MODELS, "model-a", true, TARGET)
                        .toCompletableFuture()
                        .join());
        ProviderEndpoint saved = workflow.endpoint().orElseThrow();
        ProviderRef reference = workflow.save(MODELS, "model-a", true, TARGET)
                .toCompletableFuture()
                .join();

        assertEquals(saved.revision(), reference.endpointRevision());
        assertEquals(saved, workflow.endpoint().orElseThrow());
        assertEquals(1, gateway.providerCredentialSetCalls);
        assertEquals(2, gateway.applyCalls);
        assertTrue(workflow.phase().contains("模型已保存"));
    }

    @Test
    void 目录失败可手填并保存且仅保存不应用模型() {
        ApplyingGateway gateway = new ApplyingGateway();
        ProviderSetupWorkflow workflow = connected(gateway);
        gateway.discoveryResponses.add(CompletableFuture.failedFuture(new IllegalStateException("目录不支持")));

        assertThrows(
                CompletionException.class,
                () -> workflow.discover().toCompletableFuture().join());
        ProviderRef saved = workflow.save(MODELS, "model-a", false, TARGET)
                .toCompletableFuture()
                .join();

        assertEquals("model-a", saved.model());
        assertEquals(ProviderLifecycle.ACTIVE, workflow.endpoint().orElseThrow().lifecycle());
        assertEquals(0, gateway.applyCalls);
        assertFalse(workflow.pending());
    }

    @Test
    void 缺工作区先保留已保存模型补充目标后不重复保存() {
        ApplyingGateway gateway = new ApplyingGateway();
        ProviderSetupWorkflow workflow = connected(gateway);
        ProviderSetupTarget missing = new ProviderSetupTarget(Optional.empty(), Optional.empty(), "");

        assertThrows(
                CompletionException.class,
                () -> workflow.save(MODELS, "model-a", true, missing)
                        .toCompletableFuture()
                        .join());
        long revision = workflow.endpoint().orElseThrow().revision();
        ProviderRef applied = workflow.save(MODELS, "model-a", true, TARGET)
                .toCompletableFuture()
                .join();

        assertEquals(revision, applied.endpointRevision());
        assertEquals(1, gateway.applyCalls);
    }

    @Test
    void 多模型未指定当前模型时拒绝保存并保留连接版本() {
        ApplyingGateway gateway = new ApplyingGateway();
        ProviderSetupWorkflow workflow = connected(gateway);
        long before = workflow.endpoint().orElseThrow().revision();

        assertThrows(
                CompletionException.class,
                () -> workflow.save(MODELS, "", true, TARGET)
                        .toCompletableFuture()
                        .join());

        assertEquals(before, workflow.endpoint().orElseThrow().revision());
        assertEquals(0, gateway.applyCalls);
        assertFalse(workflow.pending());
    }

    @Test
    void 无鉴权连接无需密钥且关闭时取消目录读取() {
        ApplyingGateway gateway = new ApplyingGateway();
        ProviderSetupWorkflow workflow = new ProviderSetupWorkflow(gateway, "local-provider");
        workflow.connect(draft(ProviderAuthentication.NONE), new char[0])
                .toCompletableFuture()
                .join();
        CompletableFuture<com.javaclaw.api.ProviderModelDiscoveryResult> pending = new CompletableFuture<>();
        gateway.discoveryResponses.add(pending);
        workflow.discover();
        assertTrue(workflow.pending());

        workflow.close();

        assertTrue(gateway.discoveryCancellations.getFirst().isCancelled());
        assertEquals(0, gateway.providerCredentialSetCalls);
    }

    @Test
    void 未填写必需密钥时不创建连接也不保留临时字符() {
        ApplyingGateway gateway = new ApplyingGateway();
        ProviderSetupWorkflow workflow = new ProviderSetupWorkflow(gateway, "missing-key-provider");

        assertThrows(
                CompletionException.class,
                () -> workflow.connect(draft(ProviderAuthentication.API_KEY), new char[0])
                        .toCompletableFuture()
                        .join());

        assertTrue(workflow.endpoint().isEmpty());
        assertEquals(1, gateway.providers.size());
        assertFalse(workflow.pending());
    }

    private static ProviderSetupWorkflow connected(ApplyingGateway gateway) {
        ProviderSetupWorkflow workflow = new ProviderSetupWorkflow(gateway, "new-provider");
        workflow.connect(draft(ProviderAuthentication.API_KEY), "temporary-key".toCharArray())
                .toCompletableFuture()
                .join();
        return workflow;
    }

    private static ProviderModelSpec model(String id) {
        return new ProviderModelSpec(id, id, Set.of(ProviderModelPurpose.CHAT), OptionalInt.empty());
    }

    private static ProviderDraft draft(ProviderAuthentication authentication) {
        return new ProviderDraft(
                "",
                "我的模型服务",
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

    private static final class ApplyingGateway extends TestCoreSettingsGateway {
        private ProviderRef applied;
        private ProviderSetupTarget target;
        private RuntimeException applyFailure;
        private int applyCalls;

        @Override
        public CompletionStage<Void> useModel(
                Optional<WorkspaceId> workspace, Optional<ThreadId> thread, ProviderRef model) {
            applyCalls++;
            applied = model;
            target = new ProviderSetupTarget(workspace, thread, "项目 A");
            if (applyFailure != null) {
                RuntimeException failure = applyFailure;
                applyFailure = null;
                return CompletableFuture.failedFuture(failure);
            }
            return CompletableFuture.completedFuture(null);
        }
    }
}
