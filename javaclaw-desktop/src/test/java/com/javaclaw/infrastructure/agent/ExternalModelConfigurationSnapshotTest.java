package com.javaclaw.infrastructure.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.application.inference.InferenceCatalogPort;
import com.javaclaw.application.plugin.v2.PluginManagementService;
import com.javaclaw.application.plugin.v2.PluginManagementService.*;
import com.javaclaw.framework.api.*;
import com.javaclaw.inference.api.*;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** 模型档案和资产随 Run 冻结；恢复不能重新读取当前可变目录。 */
class ExternalModelConfigurationSnapshotTest {
    @Test void capturesOnlyOwnedModelConfigurationAndRestoresTheSameData() throws Exception {
        var json = new ObjectMapper().findAndRegisterModules();
        UUID assetId = UUID.randomUUID();
        var original = profile("provider-a", assetId, 0.3);
        var profiles = new AtomicReference<>(List.of(original, profile("provider-b", UUID.randomUUID(), 0.9)));
        InferenceCatalogPort catalog = (InferenceCatalogPort) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{InferenceCatalogPort.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "profiles" -> profiles.get();
                    case "assets" -> List.of(asset(assetId), asset(UUID.randomUUID()));
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        var closed = new AtomicInteger();
        var exact = new AtomicInteger();
        var contribution = new ContributionInfo("inference", "MODEL", "2.0", "模型", "", "{}");
        RunLease lease = new RunLease() {
            public String workspaceId() { return "workspace-a"; }
            public String pluginId() { return "provider-a"; }
            public String artifactHash() { return "a".repeat(64); }
            public long configurationRevision() { return 7; }
            public String runId() { return "lease"; }
            public String instanceId() { return "instance"; }
            public Set<String> grantedPermissions() { return Set.of(); }
            public List<ContributionInfo> contributions() { return List.of(contribution); }
            public String manifestJson() { return "{}"; }
            public void close() { closed.incrementAndGet(); }
        };
        PluginManagementService plugins = (PluginManagementService) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{PluginManagementService.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "list" -> List.of(new PluginSummary("provider-a", "Provider", "1.0", "", "", "a".repeat(64),
                            true, true, StartupPolicy.MANUAL, State.READY, 7, Set.of("MODEL"), ""));
                    case "acquireRun" -> {
                        if (args.length == 4) {
                            assertEquals("a".repeat(64), args[2]); assertEquals(7L, args[3]); exact.incrementAndGet();
                        }
                        yield lease;
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        var runtime = new RemotePluginRuntime(plugins, json, catalog);
        var request = RunRequest.builder().agent(AgentDefinitionRef.latest("test.agent"))
                .profile(RunProfileRef.latest("test.profile")).source(InvocationSource.chat())
                .scope(new RunScope("workspace-a", "user", "session")).input(InputBlock.text("测试")).build();
        var captured = runtime.capture(request);
        var locks = captured.locks();
        var frozen = json.readTree(locks.getFirst().hostConfigurationJson());
        assertEquals(1, frozen.path("profiles").size()); assertEquals(1, frozen.path("assets").size());
        assertEquals(0.3, frozen.path("profiles").get(0).path("defaultParameters").path("temperature").asDouble());
        profiles.set(List.of(profile("provider-a", assetId, 0.8)));
        captured.close();
        try (var restored = runtime.restore(locks)) {
            assertEquals(locks, restored.locks());
            assertEquals(0.3, json.readTree(restored.locks().getFirst().hostConfigurationJson())
                    .path("profiles").get(0).path("defaultParameters").path("temperature").asDouble());
        }
        assertEquals(1, exact.get()); assertEquals(2, closed.get());
    }

    private InferenceModelProfile profile(String provider, UUID asset, double temperature) {
        return new InferenceModelProfile(UUID.randomUUID(), "模型档案", InferenceModelProfile.Kind.GENERATION,
                asset, provider, Map.of(), Map.of("temperature", temperature), 4096, 0,
                InferenceModelProfile.State.READY, "", Instant.EPOCH, Instant.EPOCH);
    }
    private InferenceModelAsset asset(UUID id) {
        return new InferenceModelAsset(id, InferenceModelAsset.Source.LOCAL_DIRECTORY, "模型资产", "generic",
                "b".repeat(64), "/models/" + id, "", "", List.of(), 1,
                InferenceModelAsset.State.READY, "", Instant.EPOCH, InferenceModelAsset.ArtifactMetadata.unknown(1));
    }
}
