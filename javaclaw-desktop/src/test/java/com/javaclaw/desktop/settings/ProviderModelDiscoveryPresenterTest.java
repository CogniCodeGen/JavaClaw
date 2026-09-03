package com.javaclaw.desktop.settings;

import java.util.List;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.ProviderModelDiscoveryCandidate;
import com.javaclaw.api.ProviderModelDiscoveryResult;
import com.javaclaw.api.ProviderModelPurpose;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderModelDiscoveryPresenterTest {
    @Test
    void 再次发现会取消旧网络操作且切页会取消当前操作() {
        TestCoreSettingsGateway gateway = new TestCoreSettingsGateway();
        CompletableFuture<com.javaclaw.api.ProviderModelDiscoveryResult> first = new CompletableFuture<>();
        CompletableFuture<com.javaclaw.api.ProviderModelDiscoveryResult> second = new CompletableFuture<>();
        gateway.discoveryResponses.add(first);
        gateway.discoveryResponses.add(second);
        ProviderModelDiscoveryPresenter presenter = new ProviderModelDiscoveryPresenter(gateway);
        var endpoint = gateway.providers.getFirst();

        presenter.discover(endpoint, false);
        assertFalse(gateway.discoveryCancellations.getFirst().isCancelled());

        presenter.discover(endpoint, false);
        assertTrue(gateway.discoveryCancellations.getFirst().isCancelled());
        assertFalse(gateway.discoveryCancellations.get(1).isCancelled());

        first.complete(result(endpoint, false));
        assertTrue(presenter.state().pending(), "旧请求完成不能覆盖当前读取状态");
        second.complete(result(endpoint, true));
        assertFalse(presenter.state().pending());
        assertTrue(presenter.state().message().contains("已截断"));
        assertEquals(1, presenter.state().result().orElseThrow().candidates().size());

        presenter.reset();
        assertTrue(gateway.discoveryCancellations.get(1).isCancelled());
        assertFalse(presenter.state().pending());
    }

    @Test
    void 脏草稿与远端失败都保留当前模型目录并给出可执行说明() {
        TestCoreSettingsGateway gateway = new TestCoreSettingsGateway();
        ProviderModelDiscoveryPresenter presenter = new ProviderModelDiscoveryPresenter(gateway);
        var endpoint = gateway.providers.getFirst();

        presenter.discover(endpoint, true);

        assertTrue(gateway.discoveryCancellations.isEmpty());
        assertEquals("请先保存或放弃模型服务草稿", presenter.state().message());

        gateway.discoveryResponses.add(CompletableFuture.failedFuture(new IllegalStateException("remote unavailable")));
        presenter.discover(endpoint, false);

        assertFalse(presenter.state().pending());
        assertTrue(presenter.state().message().contains("模型目录读取失败"));
        assertTrue(presenter.state().result().isEmpty());
    }

    private static ProviderModelDiscoveryResult result(com.javaclaw.api.ProviderEndpoint endpoint, boolean truncated) {
        List<ProviderModelDiscoveryCandidate> candidates = truncated
                ? List.of(new ProviderModelDiscoveryCandidate(
                        "embedding-model",
                        "Embedding Model",
                        Set.of(ProviderModelPurpose.EMBEDDING),
                        OptionalInt.of(768)))
                : List.of();
        return new ProviderModelDiscoveryResult(
                endpoint.id(), endpoint.revision(), candidates, truncated, endpoint.updatedAt());
    }
}
