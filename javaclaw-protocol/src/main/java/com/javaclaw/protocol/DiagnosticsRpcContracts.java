package com.javaclaw.protocol;

import java.util.Objects;
import java.util.Optional;

/** Diagnostics 与后台生命周期管理的 Protocol v2 payload。 */
public final class DiagnosticsRpcContracts {
    private DiagnosticsRpcContracts() {}

    /** 修复登录启动项的空业务 payload；幂等身份由外层 {@link WriteCommand} 提供。 */
    public record LoginStartupRepairPayload() {}

    /** 查询 launcher 与托盘活动状态的空参数。 */
    public record LauncherStatusQuery() {}

    /** 请求安全停止 App Server 的空业务 payload。 */
    public record ServerStopPayload() {}

    /**
     * 发行 launcher 与托盘 supervisor 的脱敏状态。
     *
     * @param launcherConfigured App Server 是否配置了发行版 launcher
     * @param trayActive 是否存在新鲜且进程存活的托盘心跳
     * @param serverControlAvailable 托盘是否能执行启动、停止和重启
     * @param unavailableReason 不可控制时的通俗原因
     */
    public record LauncherStatus(
            boolean launcherConfigured,
            boolean trayActive,
            boolean serverControlAvailable,
            Optional<String> unavailableReason) {
        /** 校验可用性和原因一致。 */
        public LauncherStatus {
            unavailableReason = Objects.requireNonNull(unavailableReason, "unavailableReason");
            if ((trayActive && !launcherConfigured)
                    || serverControlAvailable != (launcherConfigured && trayActive)
                    || serverControlAvailable == unavailableReason.isPresent()) {
                throw new IllegalArgumentException("launcher status fields disagree");
            }
        }
    }

    /**
     * 受客户端和持久 lease 门禁的停止决策。
     *
     * @param accepted 是否已安排响应完成后的协作式退出
     * @param connectedClients 决策时的客户端数，包含当前控制连接
     * @param activeLeases 决策时的持久活动 lease 数
     * @param reason 被拒绝时的通俗原因
     */
    public record ServerStopResult(boolean accepted, int connectedClients, int activeLeases, Optional<String> reason) {
        /** 校验计数和原因。 */
        public ServerStopResult {
            if (connectedClients < 0 || activeLeases < 0) {
                throw new IllegalArgumentException("lifecycle counts must not be negative");
            }
            reason = Objects.requireNonNull(reason, "reason");
            if (accepted == reason.isPresent() || (accepted && (connectedClients != 1 || activeLeases != 0))) {
                throw new IllegalArgumentException("server stop result and reason disagree");
            }
        }
    }
}
