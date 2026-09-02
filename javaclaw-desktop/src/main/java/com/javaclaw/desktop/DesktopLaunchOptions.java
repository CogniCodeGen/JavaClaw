package com.javaclaw.desktop;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.javaclaw.client.sdk.JavaClawClient;
import com.javaclaw.client.transport.StdioProcessTransport;
import com.javaclaw.client.transport.UnixDomainSocketTransport;
import com.javaclaw.nativehost.transport.WindowsNamedPipeTransport;
import com.javaclaw.nativehost.transport.WindowsPipeName;
import com.javaclaw.protocol.ClientInfo;
import com.javaclaw.protocol.LocalTransport;

/**
 * 从显式 JVM 属性解析 Desktop transport；不会调用 shell 或猜测安装目录。
 *
 * @param transport 用于创建 SDK 连接的本地 transport，非空
 * @param startupTimeout 首次连接失败后的重试窗口，零表示立即返回失败
 */
public record DesktopLaunchOptions(LocalTransport transport, Duration startupTimeout) {
    private static final Duration MAXIMUM_STARTUP_TIMEOUT = Duration.ofMinutes(1);

    /** 校验 transport 和有界启动等待时间。 */
    public DesktopLaunchOptions {
        Objects.requireNonNull(transport, "transport");
        Objects.requireNonNull(startupTimeout, "startupTimeout");
        if (startupTimeout.isNegative() || startupTimeout.compareTo(MAXIMUM_STARTUP_TIMEOUT) > 0) {
            throw new IllegalArgumentException("startupTimeout 必须在 PT0S 到 PT1M 之间");
        }
    }

    /**
     * 读取当前进程属性。
     *
     * <p>{@code javaclaw.server.socket}、{@code javaclaw.server.pipe} 与 {@code javaclaw.server.executable}
     * 必须且只能配置一个。可执行文件必须是绝对普通文件，Desktop 仅追加零个参数并通过 stdio 通信。可选的 {@code javaclaw.server.startup-timeout} 使用 ISO-8601
     * Duration，重试窗口限定在零到一分钟。
     *
     * @return 启动选项
     */
    public static DesktopLaunchOptions fromSystemProperties() {
        Optional<Path> socket = pathProperty("javaclaw.server.socket");
        Optional<WindowsPipeName> pipe = pipeProperty("javaclaw.server.pipe");
        Optional<Path> executable = pathProperty("javaclaw.server.executable");
        Duration startupTimeout = startupTimeoutProperty();
        int configured = (socket.isPresent() ? 1 : 0) + (pipe.isPresent() ? 1 : 0) + (executable.isPresent() ? 1 : 0);
        if (configured != 1) {
            throw new IllegalStateException(
                    "必须且只能配置 javaclaw.server.socket、javaclaw.server.pipe 或 javaclaw.server.executable");
        }
        if (socket.isPresent()) {
            return new DesktopLaunchOptions(new UnixDomainSocketTransport(socket.orElseThrow()), startupTimeout);
        }
        if (pipe.isPresent()) {
            return new DesktopLaunchOptions(new WindowsNamedPipeTransport(pipe.orElseThrow()), startupTimeout);
        }
        Path server = executable.orElseThrow();
        if (!Files.isRegularFile(server)) {
            throw new IllegalStateException("App Server 可执行文件不存在: " + server);
        }
        return new DesktopLaunchOptions(new StdioProcessTransport(List.of(server.toString())), startupTimeout);
    }

    /**
     * 创建可重复连接的 SDK connector；只有显式配置启动等待时间时才重试连接。
     *
     * @return Desktop connector
     */
    public DesktopClientConnector connector() {
        DesktopClientConnector direct = notifications -> JavaClawClient.connect(
                transport, new ClientInfo("javaclaw-desktop", "5.0.0-SNAPSHOT"), Set.of(), notifications);
        return StartupWaitingConnector.wrap(direct, startupTimeout);
    }

    private static Duration startupTimeoutProperty() {
        String configured =
                System.getProperty("javaclaw.server.startup-timeout", "").strip();
        if (configured.isEmpty()) {
            return Duration.ZERO;
        }
        try {
            return Duration.parse(configured);
        } catch (DateTimeParseException invalid) {
            throw new IllegalStateException("javaclaw.server.startup-timeout 必须是 ISO-8601 Duration", invalid);
        }
    }

    private static Optional<Path> pathProperty(String name) {
        return Optional.ofNullable(System.getProperty(name))
                .map(String::strip)
                .filter(value -> !value.isEmpty())
                .map(Path::of)
                .map(path -> {
                    if (!path.isAbsolute()) {
                        throw new IllegalStateException(name + " 必须是绝对路径");
                    }
                    return path.normalize();
                });
    }

    private static Optional<WindowsPipeName> pipeProperty(String name) {
        return Optional.ofNullable(System.getProperty(name))
                .map(String::strip)
                .filter(value -> !value.isEmpty())
                .map(WindowsPipeName::parse);
    }
}
