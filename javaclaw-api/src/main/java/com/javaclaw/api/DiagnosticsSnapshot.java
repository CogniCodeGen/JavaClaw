package com.javaclaw.api;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * 不包含凭据和用户内容的本地 App Server 诊断快照。
 *
 * @param build 构建与协议身份
 * @param health 当前运行健康数据
 * @param subsystems Provider、Vault、Extension、MCP、Worker、Job、Schedule 与 launcher 的脱敏状态
 * @param startedAt 进程组件启动时间
 * @param observedAt 快照采集时间
 */
public record DiagnosticsSnapshot(
        BuildIdentity build, RuntimeHealth health, SubsystemHealth subsystems, Instant startedAt, Instant observedAt) {
    /** 校验诊断分组和时间顺序。 */
    public DiagnosticsSnapshot {
        Objects.requireNonNull(build, "build");
        Objects.requireNonNull(health, "health");
        Objects.requireNonNull(subsystems, "subsystems");
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(observedAt, "observedAt");
        if (observedAt.isBefore(startedAt)) {
            throw new IllegalArgumentException("observedAt must not be before startedAt");
        }
    }

    /**
     * 构建与持久格式身份。
     *
     * @param applicationVersion JavaClaw 版本
     * @param protocolVersion Protocol 主版本
     * @param dataSchemaVersion data-v6 Core schema 版本
     */
    public record BuildIdentity(String applicationVersion, int protocolVersion, int dataSchemaVersion) {
        /** 校验版本身份。 */
        public BuildIdentity {
            applicationVersion = Preconditions.text(applicationVersion, "applicationVersion");
            if (protocolVersion < 1 || dataSchemaVersion < 1) {
                throw new IllegalArgumentException("protocol and schema versions must be positive");
            }
        }
    }

    /**
     * 当前进程的非敏感健康信息。
     *
     * @param databaseHealthy H2 探针是否成功
     * @param workspaceCount Workspace 数量
     * @param extensionCount 已注册扩展数量
     * @param connectedClients 当前连接数
     * @param activeLeases 当前未过期后台 lease 数
     * @param operatingSystem 操作系统名称
     * @param javaVersion Java 运行时版本
     */
    public record RuntimeHealth(
            boolean databaseHealthy,
            int workspaceCount,
            int extensionCount,
            int connectedClients,
            int activeLeases,
            String operatingSystem,
            String javaVersion) {
        /** 校验计数和运行时标识。 */
        public RuntimeHealth {
            if (workspaceCount < 0 || extensionCount < 0 || connectedClients < 0 || activeLeases < 0) {
                throw new IllegalArgumentException("diagnostic counts must not be negative");
            }
            operatingSystem = Preconditions.text(operatingSystem, "operatingSystem");
            javaVersion = Preconditions.text(javaVersion, "javaVersion");
        }
    }

    /**
     * 诊断子系统分组，避免把内部 Service、路径或用户内容暴露到 Protocol。
     *
     * @param providers Provider 与 Vault 状态
     * @param extensions 内置与第三方扩展的脱敏状态计数
     * @param integrations MCP 与隔离 Worker 状态
     * @param jobs 可恢复后台 Job 计数
     * @param schedule Schedule lease 与登录启动项状态
     * @param launcher 发行 launcher 与托盘状态
     */
    public record SubsystemHealth(
            ProviderVaultHealth providers,
            ExtensionHealth extensions,
            IntegrationHealth integrations,
            JobHealth jobs,
            ScheduleHealth schedule,
            LauncherHealth launcher) {
        /** 校验全部子系统分组。 */
        public SubsystemHealth {
            Objects.requireNonNull(providers, "providers");
            Objects.requireNonNull(extensions, "extensions");
            Objects.requireNonNull(integrations, "integrations");
            Objects.requireNonNull(jobs, "jobs");
            Objects.requireNonNull(schedule, "schedule");
            Objects.requireNonNull(launcher, "launcher");
        }
    }

    /**
     * Extension Registry 的脱敏聚合状态。
     *
     * @param registered 已注册扩展数
     * @param enabled 已启用扩展数
     * @param disabled 已禁用扩展数
     * @param quarantined 已隔离扩展数
     * @param thirdParty 第三方扩展数
     */
    public record ExtensionHealth(int registered, int enabled, int disabled, int quarantined, int thirdParty) {
        /** 校验扩展计数关系。 */
        public ExtensionHealth {
            if (registered < 0 || enabled < 0 || disabled < 0 || quarantined < 0 || thirdParty < 0) {
                throw new IllegalArgumentException("extension diagnostic counts must not be negative");
            }
            if (enabled + disabled + quarantined > registered || thirdParty > registered) {
                throw new IllegalArgumentException("extension diagnostic counts exceed registered extensions");
            }
        }
    }

    /**
     * Provider 与 Secret Vault 脱敏状态。
     *
     * @param configuredProviders 已保存 Provider 数
     * @param activeProviders 当前启用 Provider 数
     * @param vaultState Vault 是否可用
     * @param credentialCount Vault 中的 Secret 数量
     */
    public record ProviderVaultHealth(
            int configuredProviders, int activeProviders, VaultState vaultState, long credentialCount) {
        /** 校验计数和 Vault 状态。 */
        public ProviderVaultHealth {
            if (configuredProviders < 0
                    || activeProviders < 0
                    || activeProviders > configuredProviders
                    || credentialCount < 0) {
                throw new IllegalArgumentException("provider and credential counts are invalid");
            }
            Objects.requireNonNull(vaultState, "vaultState");
        }
    }

    /**
     * 外部集成和隔离 Worker 状态。
     *
     * @param configuredMcpEndpoints 已配置 MCP Endpoint 数
     * @param enabledMcpEndpoints 当前启用 MCP Endpoint 数
     * @param unhealthyMcpEndpoints 最近探测明确异常的 Endpoint 数
     * @param browserWorkerAvailable Browser Worker 发行镜像是否可用
     * @param knowledgeWorkerAvailable Knowledge Worker 发行镜像是否可用
     * @param skillExecutionAvailable Skill Java/JShell Native Sandbox 是否可用
     */
    public record IntegrationHealth(
            int configuredMcpEndpoints,
            int enabledMcpEndpoints,
            int unhealthyMcpEndpoints,
            boolean browserWorkerAvailable,
            boolean knowledgeWorkerAvailable,
            boolean skillExecutionAvailable) {
        /** 校验 MCP 计数。 */
        public IntegrationHealth {
            if (configuredMcpEndpoints < 0
                    || enabledMcpEndpoints < 0
                    || unhealthyMcpEndpoints < 0
                    || enabledMcpEndpoints > configuredMcpEndpoints
                    || unhealthyMcpEndpoints > configuredMcpEndpoints) {
                throw new IllegalArgumentException("MCP diagnostic counts are invalid");
            }
        }
    }

    /**
     * 可恢复 Extension Job 状态计数。
     *
     * @param queued 等待 Supervisor 领取
     * @param running 正在推进工作单元
     * @param waiting 等待审批或输入
     * @param paused 用户暂停
     */
    public record JobHealth(int queued, int running, int waiting, int paused) {
        /** 校验 Job 计数。 */
        public JobHealth {
            if (queued < 0 || running < 0 || waiting < 0 || paused < 0) {
                throw new IllegalArgumentException("job counts must not be negative");
            }
        }
    }

    /**
     * Schedule 与登录启动投影状态。
     *
     * @param loginStartupRequired 至少一个 Workspace 存在启用 Schedule
     * @param leaseHeld 当前进程是否持有 Schedule lease
     * @param synchronizedWorkspaces 已恢复或同步的 Workspace 数
     * @param repairAvailable 当前环境是否能修复登录启动项
     * @param loginStartupInstalled 系统登录启动项是否存在
     * @param unavailableReason 不可修复时的脱敏原因
     */
    public record ScheduleHealth(
            boolean loginStartupRequired,
            boolean leaseHeld,
            int synchronizedWorkspaces,
            boolean repairAvailable,
            boolean loginStartupInstalled,
            Optional<String> unavailableReason) {
        /** 校验启动项状态组合。 */
        public ScheduleHealth {
            if (synchronizedWorkspaces < 0) {
                throw new IllegalArgumentException("synchronizedWorkspaces must not be negative");
            }
            unavailableReason = Objects.requireNonNull(unavailableReason, "unavailableReason");
            if (repairAvailable == unavailableReason.isPresent()) {
                throw new IllegalArgumentException("repair availability and reason disagree");
            }
        }
    }

    /**
     * 发行 launcher 与系统托盘的脱敏状态。
     *
     * @param launcherConfigured 是否由发行 launcher 启动
     * @param trayActive 是否存在新鲜且进程存活的托盘心跳
     * @param serverControlAvailable 托盘是否能控制 App Server
     * @param unavailableReason 不可控制时的脱敏原因
     */
    public record LauncherHealth(
            boolean launcherConfigured,
            boolean trayActive,
            boolean serverControlAvailable,
            Optional<String> unavailableReason) {
        /** 校验 launcher、托盘与原因的一致性。 */
        public LauncherHealth {
            unavailableReason = Objects.requireNonNull(unavailableReason, "unavailableReason");
            if ((trayActive && !launcherConfigured)
                    || (serverControlAvailable && (!launcherConfigured || !trayActive))
                    || (serverControlAvailable == unavailableReason.isPresent())) {
                throw new IllegalArgumentException("launcher diagnostic state is inconsistent");
            }
        }
    }
}
