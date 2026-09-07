package com.javaclaw.server.security;

import java.io.IOException;
import java.net.Proxy;
import java.net.Socket;
import java.time.Clock;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.javaclaw.api.CancellationToken;

/**
 * 依赖准备使用的 CONNECT Broker；每个租约拥有独立入口，只有 OS 允许的进程树可以连接该入口。
 *
 * <p>TLS 保持端到端，不提供 HTTP 方法、路径或禁止上传保证。代理配置负责兼容工具，OS 沙箱负责禁止旁路。
 */
public final class CommandProxyBroker implements AutoCloseable {
    private final Clock clock;
    private final BrokerTarget.HostResolver resolver;
    private final SocketFactory sockets;
    private final Set<CommandProxyLease> leases = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    /**
     * 创建使用受限系统 DNS 的代理。
     *
     * @param clock 平台时钟
     */
    public CommandProxyBroker(Clock clock) {
        this(clock, new BrokerTarget.SystemHostResolver());
    }

    CommandProxyBroker(Clock clock, BrokerTarget.HostResolver resolver) {
        this(clock, resolver, () -> new Socket(Proxy.NO_PROXY));
    }

    CommandProxyBroker(Clock clock, BrokerTarget.HostResolver resolver, SocketFactory sockets) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.sockets = Objects.requireNonNull(sockets, "sockets");
    }

    /**
     * 为已持久化的准备操作创建单独代理入口。
     *
     * @param grant 已批准的固定目标和资源范围
     * @param cancellation Turn 取消信号
     * @param authorization 每次连接和传输期间的实时撤权检查
     * @return 调用方拥有的租约，准备结束时必须关闭
     * @throws IOException 无法创建代理入口
     */
    public synchronized CommandProxyLease open(
            CommandNetworkGrant grant, CancellationToken cancellation, RealtimeAuthorization authorization)
            throws IOException {
        if (closed) {
            throw new IllegalStateException("命令代理已经关闭");
        }
        CommandProxyLease lease = new CommandProxyLease(grant, cancellation, authorization, clock, resolver, sockets);
        leases.add(lease);
        lease.onClose(() -> leases.remove(lease));
        lease.start();
        return lease;
    }

    /** 关闭所有入口及既有隧道；重复关闭安全。 */
    @Override
    public synchronized void close() {
        closed = true;
        for (CommandProxyLease lease : Set.copyOf(leases)) {
            lease.close();
        }
    }

    /** 仅替换建连副作用；返回未连接 Socket，生产始终禁用系统代理。 */
    @FunctionalInterface
    interface SocketFactory {
        Socket create() throws IOException;
    }

    /** 服务端权威身份的实时权限检查，扩展和模型不能替换此回调。 */
    @FunctionalInterface
    public interface RealtimeAuthorization {
        /** @throws Exception Turn、权限或扩展状态已失效 */
        void check() throws Exception;
    }
}
