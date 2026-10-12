package com.javaclaw.application.inference;

import com.javaclaw.inference.api.InferenceModelProfile;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class PluginScopedInferenceManagementTest {
    @Test void fixesProviderIdentityAndPreservesOtherPluginBindings() throws Exception {
        var owned = profile("plugin-a"); var other = profile("plugin-b");
        var snapshot = new InferenceManagementApplicationService.Snapshot(List.of(), List.of(), List.of(owned, other),
                Map.of(InferenceCatalogPort.ModelTier.HIGH, other.id(), InferenceCatalogPort.ModelTier.NORMAL, owned.id()),
                List.of(), InferenceCatalogPort.GatewayConfiguration.defaults(), List.of());
        AtomicReference<HuggingFaceModelCatalogPort.SearchRequest> search = new AtomicReference<>();
        AtomicReference<Map<InferenceCatalogPort.ModelTier, UUID>> bindings = new AtomicReference<>();
        AtomicReference<InferenceCatalogPort.GatewayConfiguration> gateway = new AtomicReference<>();
        InferenceManagementApplicationService delegate = (InferenceManagementApplicationService) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{InferenceManagementApplicationService.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "snapshot" -> snapshot;
                    case "searchOnlineModels" -> { search.set((HuggingFaceModelCatalogPort.SearchRequest) args[0]); yield new HuggingFaceModelCatalogPort.SearchPage(List.of(), ""); }
                    case "saveBindings" -> { bindings.set((Map<InferenceCatalogPort.ModelTier, UUID>) args[1]); yield null; }
                    case "saveGateway" -> { gateway.set((InferenceCatalogPort.GatewayConfiguration) args[0]); yield null; }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        var service = new PluginScopedInferenceManagement(delegate, "workspace-a", "plugin-a");

        assertEquals(List.of(owned), service.snapshot("workspace-a").profiles());
        service.searchOnlineModels(new HuggingFaceModelCatalogPort.SearchRequest("model", "", 10, "spoofed-provider"), ignored -> { }, () -> false);
        assertEquals("plugin-a", search.get().pluginId());
        service.saveBindings("workspace-a", Map.of(InferenceCatalogPort.ModelTier.LIGHT, owned.id()));
        assertEquals(Map.of(InferenceCatalogPort.ModelTier.HIGH, other.id(), InferenceCatalogPort.ModelTier.LIGHT, owned.id()), bindings.get());
        assertThrows(IllegalArgumentException.class, () -> service.stopProfile(other.id()));
        assertThrows(IllegalArgumentException.class, () -> service.snapshot("workspace-b"));
        service.saveGateway(InferenceCatalogPort.GatewayConfiguration.defaults().withProvider("spoofed-provider"));
        assertEquals("plugin-a", gateway.get().providerId());
        assertThrows(IllegalStateException.class, () -> service.setInvocationLogging(true));
    }
    private InferenceModelProfile profile(String provider) {
        return new InferenceModelProfile(UUID.randomUUID(), provider, InferenceModelProfile.Kind.GENERATION, UUID.randomUUID(),
                provider, Map.of(), Map.of(), 4096, 0, InferenceModelProfile.State.READY, "", Instant.now(), Instant.now());
    }
}
