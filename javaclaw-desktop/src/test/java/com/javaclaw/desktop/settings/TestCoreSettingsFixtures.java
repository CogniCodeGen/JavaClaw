package com.javaclaw.desktop.settings;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

import com.javaclaw.api.AgentRoleSpec;
import com.javaclaw.api.CoreTools;
import com.javaclaw.api.CredentialRef;
import com.javaclaw.api.DiagnosticsSnapshot;
import com.javaclaw.api.ProviderAdapter;
import com.javaclaw.api.ProviderAdapterOptions;
import com.javaclaw.api.ProviderAuthentication;
import com.javaclaw.api.ProviderCapabilities;
import com.javaclaw.api.ProviderEndpoint;
import com.javaclaw.api.ProviderEndpointSpec;
import com.javaclaw.api.ProviderLifecycle;
import com.javaclaw.api.ProviderModelPurpose;
import com.javaclaw.api.ProviderModelSpec;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.api.ProviderVerificationResult;
import com.javaclaw.api.ProviderVerificationState;
import com.javaclaw.api.ProviderVerificationUsage;
import com.javaclaw.api.VaultState;
import com.javaclaw.protocol.DiagnosticsRpcContracts;

/** 设置中心内存 Gateway 使用的稳定测试值，避免 Gateway 再次成为巨型夹具。 */
final class TestCoreSettingsFixtures {
    private TestCoreSettingsFixtures() {}

    static ProviderEndpoint provider(long revision, ProviderEndpointSpec spec, ProviderLifecycle lifecycle) {
        Instant now = Instant.parse("2026-09-01T01:00:00Z");
        return new ProviderEndpoint("provider-main", revision, lifecycle, spec, now, now);
    }

    static ProviderEndpointSpec providerSpec(Optional<CredentialRef> credential) {
        return new ProviderEndpointSpec(
                "Local fake",
                ProviderAdapter.OPENAI_COMPATIBLE,
                Optional.of(URI.create("https://models.example.test/v1")),
                ProviderAuthentication.API_KEY,
                List.of(new ProviderModelSpec(
                        "fake-model", "Fake model", Set.of(ProviderModelPurpose.CHAT), OptionalInt.empty())),
                credential,
                Duration.ofSeconds(30),
                0,
                ProviderAdapterOptions.defaults(ProviderAdapter.OPENAI_COMPATIBLE));
    }

    static AgentRoleSpec profileSpec() {
        ProviderEndpoint provider = provider(1, providerSpec(Optional.empty()), ProviderLifecycle.ACTIVE);
        return new AgentRoleSpec(
                "Workspace Profile",
                "角色测试",
                "使用 Workspace 默认配置。",
                Optional.of(new com.javaclaw.api.ModelPreference(new ProviderRef(
                        provider.id(),
                        provider.revision(),
                        provider.spec().models().getFirst().modelId()))),
                Optional.empty(),
                new com.javaclaw.api.CapabilityNarrowing(Optional.of(Set.of(CoreTools.SEARCH_NAME)), Optional.empty()),
                com.javaclaw.api.PermissionConstraint.INHERIT,
                java.util.Map.of());
    }

    static ProviderVerificationResult verification(ProviderRef provider, ProviderModelPurpose purpose, Instant now) {
        boolean chat = purpose == ProviderModelPurpose.CHAT;
        return new ProviderVerificationResult(
                provider,
                purpose,
                ProviderVerificationState.SUCCEEDED,
                12,
                chat ? Optional.of(new ProviderVerificationUsage(2, 1, 0, 0)) : Optional.empty(),
                new ProviderCapabilities(Set.of(purpose), chat, chat, chat, false, false, false, false),
                Optional.empty(),
                now);
    }

    static ConnectionSummary connection() {
        return new ConnectionSummary(
                "javaclaw-app-server", "6.0.0-SNAPSHOT", 3, Set.of("core.item-envelope"), Set.of());
    }

    static DiagnosticsSnapshot diagnostics(Instant now) {
        DiagnosticsSnapshot.BuildIdentity build = new DiagnosticsSnapshot.BuildIdentity("6.0.0-SNAPSHOT", 3, 1);
        DiagnosticsSnapshot.RuntimeHealth health =
                new DiagnosticsSnapshot.RuntimeHealth(true, 1, 9, 1, 0, "test", "25");
        DiagnosticsSnapshot.SubsystemHealth subsystems = new DiagnosticsSnapshot.SubsystemHealth(
                new DiagnosticsSnapshot.ProviderVaultHealth(1, 1, VaultState.READY, 0),
                new DiagnosticsSnapshot.ExtensionHealth(9, 9, 0, 0, 0),
                new DiagnosticsSnapshot.IntegrationHealth(0, 0, 0, false, false, false),
                new DiagnosticsSnapshot.JobHealth(0, 0, 0, 0),
                new DiagnosticsSnapshot.ScheduleHealth(false, false, 1, true, false, Optional.empty()),
                new DiagnosticsSnapshot.LauncherHealth(
                        false, false, false, Optional.of("IDEA 调试未配置 launcher supervisor")));
        return new DiagnosticsSnapshot(build, health, subsystems, now, now);
    }

    static DiagnosticsRpcContracts.LauncherStatus launcherStatus() {
        return new DiagnosticsRpcContracts.LauncherStatus(
                false, false, false, Optional.of("IDEA 调试未配置 launcher supervisor"));
    }
}
