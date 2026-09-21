package com.javaclaw.desktop.settings;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderSetupWorkflowRecoveryTest {
    @Test
    void 未知保存重连恢复连接分类但保持原密封身份且只查询原回执() {
        var gateway = new ProviderConfigurationTestGateway();
        try (var workflow = ProviderSetupWorkflowTest.connected(gateway)) {
            gateway.saveResponses.add(CompletableFuture.failedFuture(new IllegalStateException("connection lost")));
            assertThrows(
                    CompletionException.class,
                    () -> workflow.save(ProviderSetupWorkflowTest.MODELS, true)
                            .toCompletableFuture()
                            .join());
            var submitted = gateway.saved.getFirst();
            gateway.invalidated.run();
            assertEquals(ProviderConfigurationCapability.DISCONNECTED, workflow.capability());

            workflow.reconnect().toCompletableFuture().join();

            assertEquals(ProviderConfigurationCapability.AVAILABLE, workflow.capability());
            assertTrue(workflow.unknown());
            assertFalse(workflow.hasPreparedSecret());
            assertTrue(workflow.checkResult().toCompletableFuture().join().isEmpty());
            assertTrue(workflow.unknown());
            assertThrows(
                    CompletionException.class,
                    () -> workflow.save(ProviderSetupWorkflowTest.MODELS, true)
                            .toCompletableFuture()
                            .join());
            gateway.committed = gateway.result(gateway.configuration);
            assertTrue(workflow.checkResult().toCompletableFuture().join().isPresent());
            assertFalse(workflow.unknown());
            assertEquals(1, gateway.preparations);
            assertEquals(1, gateway.saved.size());
            assertEquals(2, gateway.queried.size());
            gateway.queried.forEach(value -> assertSame(submitted, value));
        }
    }

    @Test
    void 能力检测失败重连可重新检测且只有明确缺失时才报告不支持() {
        var gateway = new ProviderConfigurationTestGateway();
        gateway.supported = CompletableFuture.failedFuture(new IllegalStateException("capability read failed"));
        try (var workflow = new ProviderSetupWorkflow(gateway)) {
            assertEquals(ProviderConfigurationCapability.FAILED, workflow.capability());
            assertFalse(workflow.phase().contains("升级"));
            var capability = new CompletableFuture<Boolean>();
            gateway.supported = capability;

            workflow.reconnect().toCompletableFuture().join();

            assertEquals(ProviderConfigurationCapability.CHECKING, workflow.capability());
            assertFalse(workflow.supported());
            capability.complete(false);
            assertEquals(ProviderConfigurationCapability.UNSUPPORTED, workflow.capability());
            assertTrue(workflow.phase().contains("升级"));
            assertTrue(gateway.saved.isEmpty());
            assertEquals(0, gateway.preparations);
        }
    }
}
