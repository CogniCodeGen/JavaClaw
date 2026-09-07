package com.javaclaw.launcher.tray;

import java.io.IOException;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;

import com.javaclaw.client.CommandOptions;
import com.javaclaw.client.sdk.JavaClawClient;
import com.javaclaw.protocol.ClientInfo;
import com.javaclaw.protocol.DiagnosticsRpcContracts;

/** 使用 Protocol v3 协作式停止、禁止强制杀进程的托盘 Server 控制器。 */
public final class ProtocolTrayServerControl implements TrayServerControl {
    private static final Duration STOP_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(50);

    private final TrayServerProcess process;
    private final StopCommand stopCommand;
    private final Duration stopTimeout;

    /**
     * 创建发行 supervisor 控制器。
     *
     * @param process 平台进程与 transport 端口
     */
    public ProtocolTrayServerControl(TrayServerProcess process) {
        this(process, () -> requestStop(process), STOP_TIMEOUT);
    }

    ProtocolTrayServerControl(TrayServerProcess process, StopCommand stopCommand, Duration stopTimeout) {
        this.process = Objects.requireNonNull(process, "process");
        this.stopCommand = Objects.requireNonNull(stopCommand, "stopCommand");
        this.stopTimeout = positive(stopTimeout);
    }

    @Override
    public boolean running() throws Exception {
        return process.running();
    }

    @Override
    public void start() throws Exception {
        process.start();
    }

    @Override
    public ControlResult stop() throws Exception {
        if (!process.running()) {
            return ControlResult.success();
        }
        DiagnosticsRpcContracts.ServerStopResult result = stopCommand.request();
        if (!result.accepted()) {
            return ControlResult.rejected(result.reason().orElse("App Server 拒绝停止"));
        }
        awaitStopped();
        return ControlResult.success();
    }

    @Override
    public ControlResult restart() throws Exception {
        ControlResult stopped = stop();
        if (!stopped.accepted()) {
            return stopped;
        }
        process.start();
        return ControlResult.success();
    }

    private void awaitStopped() throws Exception {
        long deadline = System.nanoTime() + stopTimeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (!process.running()) {
                return;
            }
            Thread.sleep(POLL_INTERVAL);
        }
        throw new IOException("App Server 未在十秒内完成协作式退出");
    }

    private static DiagnosticsRpcContracts.ServerStopResult requestStop(TrayServerProcess process) throws Exception {
        try (JavaClawClient client = JavaClawClient.connect(
                process.transport(), new ClientInfo("JavaClaw Tray", "6.0"), Set.of(), ignored -> {})) {
            return client.diagnostics().stopServer(CommandOptions.create(0));
        }
    }

    private static Duration positive(Duration value) {
        Duration checked = Objects.requireNonNull(value, "stopTimeout");
        if (checked.isNegative() || checked.isZero()) {
            throw new IllegalArgumentException("stopTimeout must be positive");
        }
        return checked;
    }

    @FunctionalInterface
    interface StopCommand {
        DiagnosticsRpcContracts.ServerStopResult request() throws Exception;
    }
}
