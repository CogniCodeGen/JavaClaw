package com.javaclaw.server.lifecycle;

import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import com.javaclaw.nativehost.tray.LauncherSupervisorProbe;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.DiagnosticsRpcContracts;
import com.javaclaw.server.persistence.CommandIdentity;
import com.javaclaw.server.persistence.H2Database;
import com.javaclaw.server.persistence.H2Transactions;
import com.javaclaw.server.persistence.IdempotentCommandStore;
import com.javaclaw.server.persistence.PersistenceException;

/** 提供发行 launcher 状态和受 lease 门禁的 App Server 协作式停止。 */
public final class LauncherLifecycleService {
    private final H2Transactions transactions;
    private final IdempotentCommandStore idempotency = new IdempotentCommandStore();
    private final LifecycleCoordinator lifecycle;
    private final Supplier<LauncherSupervisorProbe.Status> supervisorStatus;
    private final CanonicalJson json;
    private final Clock clock;

    /**
     * 创建生产生命周期服务。
     *
     * @param database 核心 H2 数据库
     * @param lifecycle 客户端与活动 lease 协调器
     * @param supervisor launcher 与托盘心跳探针
     * @param json 规范 JSON codec
     * @param clock 平台时钟
     */
    public LauncherLifecycleService(
            H2Database database,
            LifecycleCoordinator lifecycle,
            LauncherSupervisorProbe supervisor,
            CanonicalJson json,
            Clock clock) {
        this(database, lifecycle, Objects.requireNonNull(supervisor, "supervisor")::status, json, clock);
    }

    /**
     * 创建可注入 launcher 状态读取器的生命周期服务。
     *
     * @param database 核心 H2 数据库
     * @param lifecycle 生命周期协调器
     * @param supervisorStatus launcher 状态读取器
     * @param json 规范 JSON codec
     * @param clock 平台时钟
     */
    public LauncherLifecycleService(
            H2Database database,
            LifecycleCoordinator lifecycle,
            Supplier<LauncherSupervisorProbe.Status> supervisorStatus,
            CanonicalJson json,
            Clock clock) {
        transactions = new H2Transactions(Objects.requireNonNull(database, "database"));
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.supervisorStatus = Objects.requireNonNull(supervisorStatus, "supervisorStatus");
        this.json = Objects.requireNonNull(json, "json");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * 读取发行 launcher 与托盘的真实可用状态。
     *
     * @return 不包含路径与进程标识的状态
     */
    public DiagnosticsRpcContracts.LauncherStatus readStatus() {
        LauncherSupervisorProbe.Status status = supervisorStatus.get();
        return new DiagnosticsRpcContracts.LauncherStatus(
                status.launcherConfigured(),
                status.trayActive(),
                status.serverControlAvailable(),
                status.unavailableReason());
    }

    /**
     * 请求 App Server 在 RPC 响应发出后协作式停止。
     *
     * <p>实现说明：同一幂等键不会在服务重启后重新执行旧停止意图；活动 Turn、交互、Schedule lease 或其他客户端均会阻止停止。
     *
     * @param identity expected revision 必须为 0 的幂等命令
     * @return 接受或拒绝决策
     */
    public synchronized DiagnosticsRpcContracts.ServerStopResult stop(CommandIdentity identity) {
        CommandIdentity checked = Objects.requireNonNull(identity, "identity");
        requireExpectedRevision(checked);
        Optional<DiagnosticsRpcContracts.ServerStopResult> replay = recover(checked);
        if (replay.isPresent()) {
            return replay.orElseThrow();
        }
        DiagnosticsRpcContracts.ServerStopResult result = decideStop();
        record(checked, result);
        return result;
    }

    private DiagnosticsRpcContracts.ServerStopResult decideStop() {
        DiagnosticsRpcContracts.LauncherStatus launcher = readStatus();
        if (!launcher.serverControlAvailable()) {
            LifecycleCoordinator.Status current = lifecycle.status();
            return rejected(current, launcher.unavailableReason().orElse("launcher supervisor 不可用"));
        }
        LifecycleCoordinator.ShutdownDecision decision = lifecycle.requestControlledShutdown();
        if (!decision.accepted()) {
            return rejected(decision.status(), decision.reason());
        }
        return new DiagnosticsRpcContracts.ServerStopResult(
                true, decision.status().connectedClients(), decision.status().activeLeases(), Optional.empty());
    }

    private Optional<DiagnosticsRpcContracts.ServerStopResult> recover(CommandIdentity identity) {
        return execute(connection -> idempotency
                .recover(connection, identity)
                .map(payload -> json.decode(payload, DiagnosticsRpcContracts.ServerStopResult.class)));
    }

    private void record(CommandIdentity identity, DiagnosticsRpcContracts.ServerStopResult result) {
        execute(connection -> {
            idempotency.record(connection, identity, json.encode(result), clock.instant());
            return null;
        });
    }

    private static DiagnosticsRpcContracts.ServerStopResult rejected(
            LifecycleCoordinator.Status status, String reason) {
        return new DiagnosticsRpcContracts.ServerStopResult(
                false, status.connectedClients(), status.activeLeases(), Optional.of(reason));
    }

    private static void requireExpectedRevision(CommandIdentity identity) {
        if (identity.expectedRevision() != 0) {
            throw PersistenceException.invalidRequest("停止 App Server 的 expected revision 必须为 0");
        }
    }

    private <T> T execute(H2Transactions.SqlWork<T> work) {
        try {
            return transactions.execute(work);
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new PersistenceException("launcher 生命周期命令事务失败", failure);
        }
    }
}
