package com.javaclaw.desktop.settings;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.ModelContextLimits;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.client.CommandOptions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderContextPresenterTest {
    @Test
    void 旧选择异步返回不能覆盖当前模型且空容量保持未知() {
        Gateway gateway = new Gateway();
        var presenter = new ProviderContextPresenter(gateway, () -> {});
        var first = new ProviderRef("provider", 1, "first");
        var second = new ProviderRef("provider", 1, "second");
        presenter.bind(Optional.of(first));
        presenter.bind(Optional.of(second));
        gateway.reads.removeFirst().complete(ModelContextLimits.unknown(first));
        assertTrue(presenter.state().pending());
        gateway.reads.removeFirst().complete(ModelContextLimits.unknown(second));
        assertEquals(second, presenter.state().limits().orElseThrow().provider());
        assertTrue(
                presenter.state().limits().orElseThrow().contextWindowTokens().isEmpty());
    }

    @Test
    void 保存通过SDK创建新版本且本地非法容量不发请求() {
        Gateway gateway = new Gateway();
        AtomicInteger reloads = new AtomicInteger();
        var presenter = new ProviderContextPresenter(gateway, reloads::incrementAndGet);
        var reference = new ProviderRef("provider", 1, "model");
        presenter.bind(Optional.of(reference));
        gateway.reads.removeFirst().complete(ModelContextLimits.unknown(reference));
        presenter.save("100", "200");
        assertEquals(0, gateway.writes);
        presenter.save("128000", "8000");
        assertEquals(1, gateway.writes);
        assertEquals(
                OptionalLong.of(128000),
                presenter.state().limits().orElseThrow().contextWindowTokens());
        assertEquals(2, presenter.state().provider().orElseThrow().endpointRevision());
        assertEquals(1, reloads.get());
        assertFalse(presenter.state().pending());
    }

    private static final class Gateway implements ProviderContextSettingsGateway {
        private final Deque<CompletableFuture<ModelContextLimits>> reads = new ArrayDeque<>();
        private int writes;

        @Override
        public CompletionStage<ModelContextLimits> modelContextLimits(ProviderRef provider) {
            var result = new CompletableFuture<ModelContextLimits>();
            reads.add(result);
            return result;
        }

        @Override
        public CompletionStage<ModelContextLimits> updateModelContextLimits(
                ModelContextLimits limits, CommandOptions options) {
            writes++;
            assertEquals(1, options.expectedRevision());
            return CompletableFuture.completedFuture(new ModelContextLimits(
                    new ProviderRef(
                            limits.provider().endpointId(), 2, limits.provider().model()),
                    limits.contextWindowTokens(),
                    limits.maximumOutputTokens()));
        }
    }
}
