package com.javaclaw.server.diagnostics;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import com.javaclaw.api.DiagnosticsSnapshot;
import com.javaclaw.api.McpEndpoint;
import com.javaclaw.api.McpEndpointState;
import com.javaclaw.api.McpHealthState;
import com.javaclaw.api.ProviderEndpoint;
import com.javaclaw.api.ProviderLifecycle;
import com.javaclaw.api.VaultStatus;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.DiagnosticsRpcContracts;
import com.javaclaw.protocol.ExtensionRpcContracts;
import com.javaclaw.protocol.ProtocolVersion;
import com.javaclaw.server.extension.BuiltinIsolatedServices;
import com.javaclaw.server.lifecycle.LifecycleCoordinator;
import com.javaclaw.server.lifecycle.ScheduleLifecycleCoordinator;
import com.javaclaw.server.mcp.McpService;
import com.javaclaw.server.persistence.CommandIdentity;
import com.javaclaw.server.persistence.CoreCommandService;
import com.javaclaw.server.persistence.ExtensionJobService;
import com.javaclaw.server.persistence.H2Database;
import com.javaclaw.server.persistence.H2Transactions;
import com.javaclaw.server.persistence.IdempotentCommandStore;
import com.javaclaw.server.persistence.PersistenceException;
import com.javaclaw.server.persistence.ProviderService;
import com.javaclaw.server.security.vault.SecretVaultService;

/** 汇总不包含凭据、路径和用户内容的 App Server 诊断信息。 */
public final class DiagnosticsService {
    private final Sources sources;
    private final H2Transactions transactions;
    private final IdempotentCommandStore idempotency = new IdempotentCommandStore();
    private final CanonicalJson json;
    private final Clock clock;
    private final Instant startedAt;

    /**
     * 创建诊断服务。
     *
     * @param sources 只读诊断来源
     * @param json 规范 JSON codec
     * @param clock 平台时钟
     * @param startedAt 组件启动时间
     */
    public DiagnosticsService(Sources sources, CanonicalJson json, Clock clock, Instant startedAt) {
        this.sources = Objects.requireNonNull(sources, "sources");
        this.json = Objects.requireNonNull(json, "json");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.startedAt = Objects.requireNonNull(startedAt, "startedAt");
        transactions = new H2Transactions(sources.core().database());
    }

    /**
     * 采集当前诊断快照。
     *
     * @return 不包含敏感内容的快照
     */
    public DiagnosticsSnapshot read() {
        LifecycleCoordinator.Status lifecycle = sources.core().lifecycle().status();
        List<ExtensionRpcContracts.Summary> extensions = List.copyOf(
                Objects.requireNonNull(sources.runtime().extensions().get(), "extension diagnostics"));
        DiagnosticsSnapshot.BuildIdentity build = new DiagnosticsSnapshot.BuildIdentity(
                applicationVersion(), ProtocolVersion.CURRENT, H2Database.CORE_SCHEMA_VERSION);
        DiagnosticsSnapshot.RuntimeHealth health = new DiagnosticsSnapshot.RuntimeHealth(
                sources.core().database().healthy(),
                sources.core().core().listWorkspaces().size(),
                extensions.size(),
                lifecycle.connectedClients(),
                lifecycle.activeLeases(),
                System.getProperty("os.name", "unknown"),
                System.getProperty("java.version", "unknown"));
        return new DiagnosticsSnapshot(build, health, subsystemHealth(extensions), startedAt, clock.instant());
    }

    /**
     * 按 Schedule 权威状态重新写入登录启动项并返回新快照。
     *
     * @param identity expected revision 必须为 0 的幂等命令
     * @return 修复后的脱敏快照
     */
    public synchronized DiagnosticsSnapshot repairLoginStartup(CommandIdentity identity) {
        CommandIdentity checked = Objects.requireNonNull(identity, "identity");
        if (checked.expectedRevision() != 0) {
            throw PersistenceException.invalidRequest("修复启动项的 expected revision 必须为 0");
        }
        Optional<DiagnosticsSnapshot> replay = execute(connection -> idempotency
                .recover(connection, checked)
                .map(payload -> json.decode(payload, DiagnosticsSnapshot.class)));
        if (replay.isPresent()) {
            return replay.orElseThrow();
        }
        sources.runtime().schedule().repairLoginStartup();
        DiagnosticsSnapshot result = read();
        execute(connection -> {
            idempotency.record(connection, checked, json.encode(result), clock.instant());
            return null;
        });
        return result;
    }

    private DiagnosticsSnapshot.SubsystemHealth subsystemHealth(List<ExtensionRpcContracts.Summary> extensions) {
        List<ProviderEndpoint> providers = sources.platform().providers().listLatest();
        VaultStatus vault = sources.platform().vault().status();
        DiagnosticsSnapshot.ProviderVaultHealth providerHealth = new DiagnosticsSnapshot.ProviderVaultHealth(
                providers.size(),
                Math.toIntExact(providers.stream()
                        .filter(provider -> provider.lifecycle() == ProviderLifecycle.ACTIVE)
                        .count()),
                vault.state(),
                vault.credentialCount());
        ExtensionJobService.JobCounts counts = sources.platform().jobs().counts();
        DiagnosticsSnapshot.JobHealth jobs =
                new DiagnosticsSnapshot.JobHealth(counts.queued(), counts.running(), counts.waiting(), counts.paused());
        return new DiagnosticsSnapshot.SubsystemHealth(
                providerHealth,
                extensionHealth(extensions),
                integrationHealth(),
                jobs,
                scheduleHealth(),
                launcherHealth());
    }

    private DiagnosticsSnapshot.ExtensionHealth extensionHealth(List<ExtensionRpcContracts.Summary> extensions) {
        int enabled = stateCount(extensions, "ENABLED");
        int disabled = stateCount(extensions, "DISABLED");
        int quarantined = stateCount(extensions, "QUARANTINED");
        int thirdParty = Math.toIntExact(extensions.stream()
                .filter(extension -> "THIRD_PARTY".equals(extension.trust()))
                .count());
        return new DiagnosticsSnapshot.ExtensionHealth(extensions.size(), enabled, disabled, quarantined, thirdParty);
    }

    private DiagnosticsSnapshot.IntegrationHealth integrationHealth() {
        List<McpEndpoint> endpoints = sources.core().core().listWorkspaces().stream()
                .flatMap(workspace -> sources.platform().mcp().list(workspace.id()).stream())
                .toList();
        int enabled = Math.toIntExact(endpoints.stream()
                .filter(endpoint -> endpoint.state() == McpEndpointState.ENABLED)
                .count());
        int unhealthy = Math.toIntExact(endpoints.stream()
                .map(endpoint -> sources.platform().mcp().health(endpoint.id()).state())
                .filter(DiagnosticsService::unhealthy)
                .count());
        BuiltinIsolatedServices.Availability workers = sources.runtime().workers();
        return new DiagnosticsSnapshot.IntegrationHealth(
                endpoints.size(), enabled, unhealthy, workers.browser(), workers.knowledge(), workers.skillExecution());
    }

    private DiagnosticsSnapshot.ScheduleHealth scheduleHealth() {
        ScheduleLifecycleCoordinator.Status status =
                sources.runtime().schedule().status();
        return new DiagnosticsSnapshot.ScheduleHealth(
                status.loginStartupRequired(),
                status.leaseHeld(),
                status.synchronizedWorkspaces(),
                status.loginStartupRepairAvailable(),
                status.loginStartupInstalled(),
                status.loginStartupUnavailableReason());
    }

    private DiagnosticsSnapshot.LauncherHealth launcherHealth() {
        DiagnosticsRpcContracts.LauncherStatus status =
                Objects.requireNonNull(sources.runtime().launcherStatus().get(), "launcher diagnostics");
        return new DiagnosticsSnapshot.LauncherHealth(
                status.launcherConfigured(),
                status.trayActive(),
                status.serverControlAvailable(),
                status.unavailableReason());
    }

    private static int stateCount(List<ExtensionRpcContracts.Summary> extensions, String state) {
        return Math.toIntExact(extensions.stream()
                .filter(extension -> state.equals(extension.state()))
                .count());
    }

    private static boolean unhealthy(McpHealthState state) {
        return state != McpHealthState.HEALTHY && state != McpHealthState.UNKNOWN;
    }

    private static String applicationVersion() {
        String implementation = DiagnosticsService.class.getPackage().getImplementationVersion();
        return implementation == null || implementation.isBlank() ? "6.0.0-SNAPSHOT" : implementation;
    }

    private <T> T execute(H2Transactions.SqlWork<T> work) {
        try {
            return transactions.execute(work);
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new PersistenceException("诊断命令事务失败", failure);
        }
    }

    /**
     * 组合诊断来源。
     *
     * @param core Core 状态来源
     * @param platform 平台状态来源
     * @param runtime 运行时状态来源
     */
    public record Sources(CoreSources core, PlatformSources platform, RuntimeSources runtime) {
        /** 校验全部诊断来源。 */
        public Sources {
            Objects.requireNonNull(core, "core");
            Objects.requireNonNull(platform, "platform");
            Objects.requireNonNull(runtime, "runtime");
        }
    }

    /**
     * Core 诊断来源。
     *
     * @param database 核心数据库
     * @param core Core 查询服务
     * @param lifecycle 生命周期状态
     */
    public record CoreSources(H2Database database, CoreCommandService core, LifecycleCoordinator lifecycle) {
        /** 校验 Core 来源。 */
        public CoreSources {
            Objects.requireNonNull(database, "database");
            Objects.requireNonNull(core, "core");
            Objects.requireNonNull(lifecycle, "lifecycle");
        }
    }

    /**
     * 平台诊断来源。
     *
     * @param providers Provider 来源
     * @param vault Vault 来源
     * @param jobs Job 来源
     * @param mcp MCP 来源
     */
    public record PlatformSources(
            ProviderService providers, SecretVaultService vault, ExtensionJobService jobs, McpService mcp) {
        /** 校验平台来源。 */
        public PlatformSources {
            Objects.requireNonNull(providers, "providers");
            Objects.requireNonNull(vault, "vault");
            Objects.requireNonNull(jobs, "jobs");
            Objects.requireNonNull(mcp, "mcp");
        }
    }

    /**
     * 运行时诊断来源。
     *
     * @param extensions 扩展实时摘要
     * @param workers 隔离 Worker 状态
     * @param schedule Schedule 状态
     * @param launcherStatus launcher 与托盘脱敏状态
     */
    public record RuntimeSources(
            Supplier<List<ExtensionRpcContracts.Summary>> extensions,
            BuiltinIsolatedServices.Availability workers,
            ScheduleLifecycleCoordinator schedule,
            Supplier<DiagnosticsRpcContracts.LauncherStatus> launcherStatus) {
        /** 校验运行时来源。 */
        public RuntimeSources {
            Objects.requireNonNull(extensions, "extensions");
            Objects.requireNonNull(workers, "workers");
            Objects.requireNonNull(schedule, "schedule");
            Objects.requireNonNull(launcherStatus, "launcherStatus");
        }
    }
}
