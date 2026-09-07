package com.javaclaw.desktop.settings;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.EmbeddingBinding;
import com.javaclaw.api.ProviderEndpoint;
import com.javaclaw.api.ProviderEndpointSpec;
import com.javaclaw.api.ProviderModelPurpose;
import com.javaclaw.api.ProviderModelSpec;
import com.javaclaw.api.ProviderRef;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderReferencePresentersTest {

    @Test
    void 向量绑定只接受Embedding模型并保存精确Provider版本() {
        TestCoreSettingsGateway gateway = new TestCoreSettingsGateway();
        ProviderEndpoint chatEndpoint = gateway.providers.getFirst();
        ProviderEmbeddingBindingPresenter presenter = new ProviderEmbeddingBindingPresenter(gateway);

        presenter.reload();
        assertFalse(presenter.state().pending());
        assertTrue(presenter.state().binding().isEmpty());
        assertThrows(
                IllegalArgumentException.class,
                () -> presenter.bind(chatEndpoint, chatEndpoint.spec().models().getFirst()));

        ProviderEndpoint embeddingEndpoint = endpointWithPurpose(chatEndpoint, 1, ProviderModelPurpose.EMBEDDING);
        ProviderModelSpec embeddingModel = embeddingEndpoint.spec().models().getFirst();
        presenter.bind(embeddingEndpoint, embeddingModel);

        EmbeddingBinding binding = presenter.state().binding().orElseThrow();
        assertEquals(embeddingEndpoint.revision(), binding.provider().endpointRevision());
        assertEquals(embeddingModel.modelId(), binding.provider().model());
        assertEquals("默认向量模型已更新", presenter.state().message());
    }

    @Test
    void 向量绑定忽略过期响应并呈现读取和写入失败() {
        TestCoreSettingsGateway gateway = new TestCoreSettingsGateway();
        ArrayDeque<CompletableFuture<Optional<EmbeddingBinding>>> loads = new ArrayDeque<>();
        CompletableFuture<Optional<EmbeddingBinding>> staleLoad = new CompletableFuture<>();
        CompletableFuture<Optional<EmbeddingBinding>> failedLoad = new CompletableFuture<>();
        loads.add(staleLoad);
        loads.add(failedLoad);
        ArrayDeque<CompletableFuture<EmbeddingBinding>> writes = new ArrayDeque<>();
        CompletableFuture<EmbeddingBinding> staleWrite = new CompletableFuture<>();
        CompletableFuture<EmbeddingBinding> failedWrite = new CompletableFuture<>();
        writes.add(staleWrite);
        writes.add(failedWrite);
        ProviderEmbeddingBindingPresenter presenter = new ProviderEmbeddingBindingPresenter(overrides(
                gateway,
                Map.of(
                        "embeddingBinding", ignored -> loads.removeFirst(),
                        "bindEmbedding", ignored -> writes.removeFirst())));

        presenter.reload();
        presenter.reload();
        staleLoad.complete(Optional.empty());
        assertTrue(presenter.state().pending());
        failedLoad.completeExceptionally(new IllegalStateException("binding unavailable"));
        assertTrue(presenter.state().message().contains("绑定读取失败"));

        ProviderEndpoint embedding =
                endpointWithPurpose(gateway.providers.getFirst(), 1, ProviderModelPurpose.EMBEDDING);
        ProviderModelSpec model = embedding.spec().models().getFirst();
        presenter.bind(embedding, model);
        presenter.bind(embedding, model);
        staleWrite.complete(new EmbeddingBinding(reference(embedding, model.modelId()), 1, embedding.updatedAt()));
        assertTrue(presenter.state().pending());
        failedWrite.completeExceptionally(new IllegalStateException("binding update unavailable"));
        assertTrue(presenter.state().message().contains("默认向量模型更新失败"));
    }

    private static ProviderRef reference(ProviderEndpoint endpoint, String model) {
        return new ProviderRef(endpoint.id(), endpoint.revision(), model);
    }

    private static ProviderEndpoint endpointWithPurpose(
            ProviderEndpoint endpoint, long revision, ProviderModelPurpose purpose) {
        ProviderEndpointSpec current = endpoint.spec();
        ProviderEndpointSpec spec = new ProviderEndpointSpec(
                current.displayName(),
                current.adapter(),
                current.baseUri(),
                current.authentication(),
                List.of(new ProviderModelSpec("fake-model", "Fake model", Set.of(purpose), OptionalInt.empty())),
                current.credential(),
                current.timeout(),
                current.maximumRetries(),
                current.options());
        return new ProviderEndpoint(
                endpoint.id(), revision, endpoint.lifecycle(), spec, endpoint.createdAt(), endpoint.updatedAt());
    }

    private static CoreSettingsGateway overrides(TestCoreSettingsGateway delegate, Map<String, Invocation> overrides) {
        return (CoreSettingsGateway) Proxy.newProxyInstance(
                CoreSettingsGateway.class.getClassLoader(),
                new Class<?>[] {CoreSettingsGateway.class},
                (proxy, method, args) -> {
                    Invocation override = overrides.get(method.getName());
                    if (override != null) {
                        return override.invoke(args);
                    }
                    try {
                        return method.invoke(delegate, args);
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
    }

    @FunctionalInterface
    private interface Invocation {
        Object invoke(Object[] arguments);
    }
}
