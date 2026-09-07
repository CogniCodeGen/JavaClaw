package com.javaclaw.nativehost.ffm;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** 固定 Windows 守护 Pipe 的有界控制通道；不复用公共 RPC 或接受任意 Pipe 名称。 */
public final class WindowsGuardPipe implements AutoCloseable {
    private final MemorySegment handle;
    private final AtomicBoolean closed = new AtomicBoolean();

    private WindowsGuardPipe(MemorySegment handle) {
        this.handle = handle;
    }

    /**
     * 连接并验证 SCM 身份；组件首次缺失时请求系统安装授权。
     *
     * @return 调用方独占的控制通道
     * @throws IOException 连接、系统授权或服务身份核验失败
     */
    public static WindowsGuardPipe connect() throws IOException {
        MemorySegment handle;
        try {
            handle = connectHandle(Duration.ofSeconds(2));
        } catch (IOException unavailable) {
            if (!WindowsNetworkGuardNative.installed()) {
                WindowsNetworkGuardNative.requestInstallation();
            }
            handle = connectHandle(Duration.ofMinutes(1));
        }
        try {
            WindowsNetworkGuardNative.verifyServer(handle);
            return new WindowsGuardPipe(handle);
        } catch (IOException failure) {
            WindowsGuardConnection.close(handle);
            throw failure;
        }
    }

    /**
     * 执行一次固定协议请求；超时关闭通道，使服务撤销网络并终止其根 Job。
     *
     * @param request 单行 ASCII 请求，最多 1024 字符，不含换行或 NUL
     * @throws IOException 超时、服务拒绝或 Pipe 关闭
     */
    public synchronized void request(String request) throws IOException {
        if (closed.get()) {
            throw new IOException("NETWORK_GUARD_CLOSED");
        }
        String value = Objects.requireNonNull(request, "request");
        if (value.length() > 1024 || !value.matches("[\\t\\x20-\\x7e]+")) {
            throw new IllegalArgumentException("network guard request must be one bounded ASCII line");
        }
        try {
            if (!"OK".equals(WindowsNetworkGuardNative.exchange(handle, value))) {
                throw new IOException("NETWORK_GUARD_REJECTED");
            }
        } catch (IOException failure) {
            try {
                close();
            } catch (IOException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    /** 关闭控制连接；服务据此撤销动态允许并终止独占根 Job。 */
    @Override
    public synchronized void close() throws IOException {
        if (closed.compareAndSet(false, true)) {
            WindowsGuardConnection.close(handle);
        }
    }

    private static MemorySegment connectHandle(Duration timeout) throws IOException {
        return WindowsGuardConnection.connect(timeout);
    }
}
