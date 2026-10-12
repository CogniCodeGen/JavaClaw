package com.javaclaw.application.inference;

import com.javaclaw.application.inference.InferenceManagementApplicationService.*;
import com.javaclaw.inference.api.InferenceModelAsset;
import com.javaclaw.inference.api.InferenceModelProfile;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PluginScopedInferenceBoundaryTest {
    @Test
    void projectionsFilterProfilesAssetsAndAliasesWithoutRedirectingWorkspace() {
        Fixture fixture = new Fixture();
        Snapshot snapshot = fixture.service.snapshot("workspace", Projection.MODEL_MANAGEMENT);
        assertEquals(List.of(fixture.owned), snapshot.profiles());
        assertEquals(List.of(fixture.ownedAsset), snapshot.assets());
        assertEquals(List.of("owned"), snapshot.publishedModels().stream().map(InferenceCatalogPort.PublishedModel::alias).toList());
        assertEquals(List.of(fixture.owned), fixture.service.readyProfiles(InferenceModelProfile.Kind.GENERATION));
        assertThrows(IllegalArgumentException.class, () -> fixture.service.snapshot("other", Projection.API_CONFIGURATION));
        assertThrows(IllegalArgumentException.class, () -> fixture.service.bind("other", InferenceCatalogPort.ModelTier.LIGHT, fixture.owned.id()));
        assertEquals(0, fixture.count("bind"));
    }

    @Test
    void everyProfileMutationRejectsForeignOrMissingOwnershipBeforeDelegation() throws Exception {
        Fixture fixture = new Fixture();
        for (UUID id : List.of(fixture.foreign.id(), UUID.randomUUID())) {
            assertThrows(IllegalArgumentException.class, () -> fixture.service.startProfile(id));
            assertThrows(IllegalArgumentException.class, () -> fixture.service.stopProfile(id));
            assertThrows(IllegalArgumentException.class, () -> fixture.service.deleteProfile(id));
            assertThrows(IllegalArgumentException.class, () -> fixture.service.recentLogs(id, 5));
            assertThrows(IllegalArgumentException.class, () -> fixture.service.runtimeStatuses(Set.of(id)));
        }
        assertEquals(0, fixture.count("startProfile"));
        assertEquals(0, fixture.count("deleteProfile"));
        fixture.service.startProfile(fixture.owned.id());
        fixture.service.stopProfile(fixture.owned.id());
        fixture.service.deleteProfile(fixture.owned.id());
        assertEquals(List.of("模型日志"), fixture.service.recentLogs(fixture.owned.id(), 5));
        assertEquals(Map.of(), fixture.service.runtimeStatuses(Set.of(fixture.owned.id())));
        assertEquals(1, fixture.count("runtimeStatuses"));
        fixture.service.deleteAsset(fixture.ownedAsset.id());
        assertThrows(IllegalArgumentException.class, () -> fixture.service.deleteAsset(fixture.foreignAsset.id()));
        assertEquals(1, fixture.count("deleteAsset"));
    }

    @Test
    void draftCreationAndUpdatesCannotReplaceAnotherProvidersProfile() throws Exception {
        Fixture fixture = new Fixture();
        assertThrows(IllegalArgumentException.class, () -> fixture.service.saveAndVerifyProfile(
                fixture.draft(fixture.owned.id(), "plugin-b"), () -> false));
        assertThrows(IllegalArgumentException.class, () -> fixture.service.saveAndVerifyProfile(
                fixture.draft(fixture.foreign.id(), "plugin-a"), () -> false));
        assertEquals(0, fixture.count("saveAndVerifyProfile"));
        fixture.service.saveAndVerifyProfile(fixture.draft(fixture.owned.id(), "plugin-a"), () -> false);
        fixture.service.saveAndVerifyProfile(fixture.draft(UUID.randomUUID(), "plugin-a"), () -> false);
        assertEquals(2, fixture.count("saveAndVerifyProfile"));
    }

    @Test
    void bindingsAndPublicAliasesPreserveForeignOwners() {
        Fixture fixture = new Fixture();
        assertThrows(IllegalArgumentException.class, () -> fixture.service.saveBindings("workspace",
                Map.of(InferenceCatalogPort.ModelTier.EMBEDDING, fixture.foreign.id())));
        assertEquals(0, fixture.count("saveBindings"));
        fixture.service.saveBindings("workspace", Map.of(InferenceCatalogPort.ModelTier.HIGH, fixture.foreign.id(),
                InferenceCatalogPort.ModelTier.LIGHT, fixture.owned.id()));
        assertEquals(Map.of(InferenceCatalogPort.ModelTier.HIGH, fixture.foreign.id(),
                InferenceCatalogPort.ModelTier.LIGHT, fixture.owned.id()), fixture.lastBindings);
        fixture.service.bind("workspace", InferenceCatalogPort.ModelTier.NORMAL, fixture.owned.id());
        assertThrows(IllegalArgumentException.class, () -> fixture.service.clearBinding("workspace", InferenceCatalogPort.ModelTier.HIGH));
        fixture.service.clearBinding("workspace", InferenceCatalogPort.ModelTier.NORMAL);
        fixture.service.clearBinding("workspace", InferenceCatalogPort.ModelTier.EMBEDDING);
        assertEquals(1, fixture.count("clearBinding"));
        assertThrows(IllegalArgumentException.class, () -> fixture.service.publish("foreign", fixture.owned.id()));
        assertThrows(IllegalArgumentException.class, () -> fixture.service.unpublish("foreign"));
        fixture.service.publish("owned", fixture.owned.id());
        fixture.service.publish("new-alias", fixture.owned.id());
        fixture.service.unpublish("owned");
        fixture.service.unpublish("not-present");
        assertEquals(2, fixture.count("publish"));
        assertEquals(2, fixture.count("unpublish"));
    }

    @Test
    void onlineDetailsAndRecommendationsKeepTheFixedProvider() throws Exception {
        Fixture fixture = new Fixture();
        for (String provider : List.of("", "plugin-a")) {
            fixture.service.downloadOnlineModel(fixture.detail(provider), ignored -> { }, () -> false);
            var downloaded = (HuggingFaceModelCatalogPort.ModelDetail) fixture.last("downloadOnlineModel")[0];
            assertEquals("plugin-a", downloaded.pluginId());
        }
        assertThrows(IllegalArgumentException.class, () -> fixture.service.downloadOnlineModel(
                fixture.detail("plugin-b"), ignored -> { }, () -> false));
        assertEquals(2, fixture.count("downloadOnlineModel"));
        fixture.service.onlineModelDetail("owner/model", () -> false);
        assertEquals("plugin-a", fixture.last("onlineModelDetail")[0]);
        assertEquals("plugin-a", fixture.service.recommendedProfile(fixture.owned.assetId(),
                InferenceModelProfile.Kind.GENERATION, () -> false).runtimeId());
        fixture.recommendationProvider = "plugin-b";
        assertThrows(IllegalStateException.class, () -> fixture.service.recommendedProfile(fixture.owned.assetId(),
                InferenceModelProfile.Kind.GENERATION, () -> false));
    }

    @Test
    void loggingRequiresExplicitGatewayOwnershipAndPasswordIsPassedOnlyToTheSecureOverload() throws Exception {
        Fixture fixture = new Fixture();
        assertThrows(IllegalStateException.class, () -> fixture.service.setInvocationLogging(true));
        assertEquals(0, fixture.count("setInvocationLogging"));
        char[] password = "local-test-placeholder".toCharArray();
        fixture.service.saveGateway(fixture.gateway.withProvider("spoofed"), password);
        assertEquals("plugin-a", fixture.gateway.providerId());
        assertSame(password, fixture.last("saveGateway")[1]);
        fixture.service.setInvocationLogging(true);
        assertEquals(1, fixture.count("setInvocationLogging"));
        java.util.Arrays.fill(password, '\0');
    }

    private static final class Fixture {
        final InferenceModelAsset ownedAsset = asset();
        final InferenceModelAsset foreignAsset = asset();
        final InferenceModelProfile owned = profile("plugin-a", ownedAsset.id());
        final InferenceModelProfile foreign = profile("plugin-b", foreignAsset.id());
        final List<Call> calls = new ArrayList<>();
        InferenceCatalogPort.GatewayConfiguration gateway = InferenceCatalogPort.GatewayConfiguration.defaults();
        Map<InferenceCatalogPort.ModelTier, UUID> lastBindings;
        String recommendationProvider = "plugin-a";
        final InferenceManagementApplicationService delegate = (InferenceManagementApplicationService) Proxy.newProxyInstance(
                InferenceManagementApplicationService.class.getClassLoader(), new Class<?>[]{InferenceManagementApplicationService.class},
                (proxy, method, args) -> {
                    calls.add(new Call(method.getName(), args.clone()));
                    return switch (method.getName()) {
                        case "snapshot" -> { assertEquals("workspace", args[0]); yield snapshot(); }
                        case "readyProfiles" -> List.of(owned, foreign);
                        case "runtimeStatuses" -> Map.of();
                        case "recentLogs" -> List.of("模型日志");
                        case "saveAndVerifyProfile" -> owned;
                        case "saveBindings" -> { lastBindings = (Map<InferenceCatalogPort.ModelTier, UUID>) args[1]; yield null; }
                        case "downloadOnlineModel" -> ownedAsset;
                        case "onlineModelDetail" -> detail("plugin-a");
                        case "recommendedProfile" -> {
                            assertEquals("plugin-a", args[0]);
                            var capacity = InferenceSystemProfilePort.CONSERVATIVE.recommend(null, null);
                            yield new RecommendedProfile(recommendationProvider, 4096, Map.of(), Map.of(), capacity.capacity(), capacity.memory());
                        }
                        case "saveGateway" -> { gateway = (InferenceCatalogPort.GatewayConfiguration) args[0]; yield null; }
                        case "startProfile", "stopProfile", "deleteProfile", "deleteAsset", "bind", "clearBinding",
                                "publish", "unpublish", "setInvocationLogging" -> null;
                        default -> throw new AssertionError("未声明的底层调用：" + method.getName());
                    };
                });
        final PluginScopedInferenceManagement service = new PluginScopedInferenceManagement(delegate, "workspace", "plugin-a");

        Snapshot snapshot() {
            return new Snapshot(List.of(), List.of(ownedAsset, foreignAsset), List.of(owned, foreign),
                    Map.of(InferenceCatalogPort.ModelTier.NORMAL, owned.id(), InferenceCatalogPort.ModelTier.HIGH, foreign.id()),
                    List.of(alias("owned", owned.id()), alias("foreign", foreign.id())), gateway, List.of());
        }
        ProfileDraft draft(UUID id, String provider) {
            return new ProfileDraft(id, "模型", InferenceModelProfile.Kind.GENERATION, owned.assetId(), provider, Map.of(), Map.of(), 4096);
        }
        HuggingFaceModelCatalogPort.ModelDetail detail(String provider) {
            var summary = new HuggingFaceModelCatalogPort.ModelSummary("owner/model", "", "test", "", 0, 0, Instant.EPOCH, false);
            return new HuggingFaceModelCatalogPort.ModelDetail(summary, "", List.of(), 1, 4096, "https://example.com/model", provider);
        }
        long count(String method) { return calls.stream().filter(call -> call.method().equals(method)).count(); }
        Object[] last(String method) { return calls.reversed().stream().filter(call -> call.method().equals(method)).findFirst().orElseThrow().args(); }
        private record Call(String method, Object[] args) { }
    }

    private static InferenceCatalogPort.PublishedModel alias(String alias, UUID id) {
        return new InferenceCatalogPort.PublishedModel(alias, id, true, Instant.EPOCH, Instant.EPOCH);
    }
    private static InferenceModelProfile profile(String provider, UUID asset) {
        return new InferenceModelProfile(UUID.randomUUID(), provider, InferenceModelProfile.Kind.GENERATION,
                asset, provider, Map.of(), Map.of(), 4096, 0, InferenceModelProfile.State.READY, "", Instant.EPOCH, Instant.EPOCH);
    }
    private static InferenceModelAsset asset() {
        return new InferenceModelAsset(UUID.randomUUID(), InferenceModelAsset.Source.LOCAL_DIRECTORY, "夹具", "test",
                "a".repeat(64), "/test/model", "", "", List.of(), 0, InferenceModelAsset.State.READY, "", Instant.EPOCH,
                InferenceModelAsset.ArtifactMetadata.unknown(0));
    }
}
