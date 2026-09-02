package com.javaclaw.client.facade;

import java.util.Map;
import java.util.Objects;

import com.javaclaw.api.DiagnosticsSnapshot;
import com.javaclaw.client.CommandOptions;
import com.javaclaw.client.RpcClientConnection;
import com.javaclaw.protocol.DiagnosticsRpcContracts;

/** App Server 非敏感诊断方法的强类型 facade。 */
public final class DiagnosticsClient {
    private final RpcClientConnection connection;

    /**
     * 创建 facade。
     *
     * @param connection 已初始化连接
     */
    public DiagnosticsClient(RpcClientConnection connection) {
        this.connection = Objects.requireNonNull(connection, "connection");
    }

    /**
     * 采集当前诊断快照。
     *
     * @return 不包含路径、凭据和用户内容的诊断
     */
    public DiagnosticsSnapshot read() {
        return connection.query("diagnostics/read", Map.of(), DiagnosticsSnapshot.class);
    }

    /**
     * 按 Schedule 权威状态修复系统登录启动项。
     *
     * @param options expected revision 必须为 0
     * @return 修复后的脱敏诊断快照
     */
    public DiagnosticsSnapshot repairLoginStartup(CommandOptions options) {
        return connection.command(
                "diagnostics/loginStartup/repair",
                new DiagnosticsRpcContracts.LoginStartupRepairPayload(),
                Objects.requireNonNull(options, "options"),
                DiagnosticsSnapshot.class);
    }

    /**
     * 读取发行 launcher 与托盘 supervisor 的真实可用状态。
     *
     * @return IDEA 直跑或托盘缺失时包含明确不可用原因
     */
    public DiagnosticsRpcContracts.LauncherStatus launcherStatus() {
        return connection.query(
                "diagnostics/launcher/read",
                new DiagnosticsRpcContracts.LauncherStatusQuery(),
                DiagnosticsRpcContracts.LauncherStatus.class);
    }

    /**
     * 请求唯一托盘控制连接安全停止 App Server。
     *
     * @param options expected revision 必须为 0
     * @return lease 或其他客户端阻断后的权威决策
     */
    public DiagnosticsRpcContracts.ServerStopResult stopServer(CommandOptions options) {
        return connection.command(
                "diagnostics/server/stop",
                new DiagnosticsRpcContracts.ServerStopPayload(),
                Objects.requireNonNull(options, "options"),
                DiagnosticsRpcContracts.ServerStopResult.class);
    }
}
