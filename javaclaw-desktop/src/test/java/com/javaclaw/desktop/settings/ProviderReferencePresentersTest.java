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

import com.javaclaw.api.AgentProfile;
import com.javaclaw.api.AgentProfileSpec;
import com.javaclaw.api.EmbeddingBinding;
import com.javaclaw.api.ProviderEndpoint;
import com.javaclaw.api.ProviderEndpointSpec;
import com.javaclaw.api.ProviderLifecycle;
import com.javaclaw.api.ProviderModelPurpose;
import com.javaclaw.api.ProviderModelSpec;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.client.CommandOptions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderReferencePresentersTest {
    @Test
    void 旧智能体引用只筛选同一Provider的不同版本且支持清空选择() {
        TestCoreSettingsGateway gateway = new TestCoreSettingsGateway();
        ProviderEndpoint oldProvider = gateway.providers.getFirst();
        AgentProfile stale = createProfile(gateway, "stale", reference(oldProvider, "fake-model"));
        ProviderEndpoint current = gateway.updateProvider(
                        oldProvider.id(),
                        oldProvider.spec(),
                        ProviderLifecycle.ACTIVE,
                        CommandOptions.create(oldProvider.revision()))
                .toCompletableFuture()
                .join();
        createProfile(gateway, "current", reference(current, "fake-model"));
        createProfile(gateway, "other", new ProviderRef("other-provider", 1, "fake-model"));
        ProviderProfileReferencePresenter presenter = new ProviderProfileReferencePresenter(gateway);

        presenter.bind(Optional.of(current));

        assertEquals(List.of(stale), presenter.state().staleProfiles());
        long epoch = presenter.state().epoch();
        presenter.bind(Optional.of(current));
        assertEquals(epoch, presenter.state().epoch(), "重复绑定相同精确版本不应重新读取目录");

        presenter.bind(Optional.empty());
        assertTrue(presenter.state().staleProfiles().isEmpty());
        assertEquals("保存模型服务后可检查引用", presenter.state().message());
    }

    @Test
    void 新版本缺少原对话模型或仅保留向量用途时拒绝更新引用() {
        TestCoreSettingsGateway removedGateway = new TestCoreSettingsGateway();
        ProviderEndpoint oldProvider = removedGateway.providers.getFirst();
        createProfile(removedGateway, "removed", reference(oldProvider, "removed-model"));
        ProviderEndpoint current = removedGateway
                .updateProvider(
                        oldProvider.id(),
                        oldProvider.spec(),
                        ProviderLifecycle.ACTIVE,
                        CommandOptions.create(oldProvider.revision()))
                .toCompletableFuture()
                .join();
        ProviderProfileReferencePresenter removed = new ProviderProfileReferencePresenter(removedGateway);
        removed.bind(Optional.of(current));
        removed.updateSelected();
        assertEquals("新版本中没有该对话模型，无法更新引用", removed.state().message());

        TestCoreSettingsGateway embeddingGateway = new TestCoreSettingsGateway();
        ProviderEndpoint previous = embeddingGateway.providers.getFirst();
        createProfile(embeddingGateway, "embedding-only", reference(previous, "fake-model"));
        ProviderEndpoint embeddingOnly = endpointWithPurpose(previous, 2, ProviderModelPurpose.EMBEDDING);
        embeddingGateway.providers.clear();
        embeddingGateway.providers.add(embeddingOnly);
        ProviderProfileReferencePresenter wrongPurpose = new ProviderProfileReferencePresenter(embeddingGateway);
        wrongPurpose.bind(Optional.of(embeddingOnly));
        wrongPurpose.updateSelected();
        assertEquals("新版本中没有该对话模型，无法更新引用", wrongPurpose.state().message());
    }

    @Test
    void 智能体旧引用忽略过期目录响应并呈现读取与更新失败() {
        TestCoreSettingsGateway gateway = new TestCoreSettingsGateway();
        ProviderEndpoint first = gateway.providers.getFirst();
        ProviderEndpoint second = new ProviderEndpoint(
                first.id(), 2, first.lifecycle(), first.spec(), first.createdAt(), first.updatedAt());
        ArrayDeque<CompletableFuture<List<AgentProfile>>> loads = new ArrayDeque<>();
        CompletableFuture<List<AgentProfile>> staleLoad = new CompletableFuture<>();
        CompletableFuture<List<AgentProfile>> failedLoad = new CompletableFuture<>();
        loads.add(staleLoad);
        loads.add(failedLoad);
        ProviderProfileReferencePresenter loading = new ProviderProfileReferencePresenter(
                overrides(gateway, Map.of("profiles", ignored -> loads.removeFirst())));

        loading.bind(Optional.of(first));
        loading.bind(Optional.of(second));
        staleLoad.complete(List.of());
        assertEquals(SettingsLoadState.LOADING, loading.state().phase());
        failedLoad.completeExceptionally(new IllegalStateException("profile catalog unavailable"));
        assertEquals(SettingsLoadState.ERROR, loading.state().phase());

        AgentProfile stale = createProfile(gateway, "update-failure", reference(first, "fake-model"));
        ProviderProfileReferencePresenter updating = new ProviderProfileReferencePresenter(overrides(
                gateway,
                Map.of(
                        "updateProfile",
                        ignored -> CompletableFuture.failedFuture(
                                new IllegalStateException("profile update unavailable")))));
        updating.bind(Optional.of(second));
        assertEquals(stale.id(), updating.state().selected().orElseThrow().id());
        updating.updateSelected();
        assertEquals(SettingsLoadState.ERROR, updating.state().phase());
        assertTrue(updating.state().message().contains("profile update unavailable"));
    }

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

    private static AgentProfile createProfile(TestCoreSettingsGateway gateway, String id, ProviderRef provider) {
        AgentProfileSpec previous = TestCoreSettingsGateway.profileSpec();
        AgentProfileSpec spec = new AgentProfileSpec(
                previous.displayName(),
                previous.systemInstruction(),
                provider,
                previous.permissionProfile(),
                previous.visibleTools(),
                previous.budget());
        return gateway.createProfile(id, spec, CommandOptions.create(0))
                .toCompletableFuture()
                .join();
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
