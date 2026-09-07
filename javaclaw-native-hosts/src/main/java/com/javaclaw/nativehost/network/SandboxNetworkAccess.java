package com.javaclaw.nativehost.network;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * 由服务端已批准的命令网络租约生成的 OS 网络边界，不能由工具 JSON 反序列化取得。
 *
 * <p>PROXY_ONLY 只允许连接服务端拥有的精确 IPv4 loopback 端点；目标仓库、预算、授权撤销与 CONNECT 隧道生命周期仍由服务端 Broker 校验。代理环境变量不构成访问控制。
 *
 * @param mode 断网或仅代理模式，不可空
 * @param grantId 断网时为空，代理模式为非空服务端租约 ID
 * @param proxyEndpoint 断网时为空，代理模式为已解析的 127.0.0.1 与非零端口
 * @param controlDirectory 应用拥有的 IPC 根，Linux 代理模式必须提供；不由模型指定
 * @param closeTunnels 服务端持有的幂等隧道关闭回调，仅本地调用，不可序列化到进程参数
 */
public record SandboxNetworkAccess(
        Mode mode,
        Optional<String> grantId,
        Optional<InetSocketAddress> proxyEndpoint,
        Optional<Path> controlDirectory,
        Runnable closeTunnels) {
    /** OS 网络模式；不存在允许任意宿主网络的模式。 */
    public enum Mode {
        /** 无网络访问。 */
        OFFLINE,
        /** 仅可连接命令租约的代理。 */
        PROXY_ONLY
    }

    /** 校验模式与端点一致，拒绝 DNS 名称及整个 loopback 地址范围授权。 */
    public SandboxNetworkAccess {
        Objects.requireNonNull(mode, "mode");
        grantId = Objects.requireNonNull(grantId, "grantId");
        proxyEndpoint = Objects.requireNonNull(proxyEndpoint, "proxyEndpoint");
        controlDirectory = Objects.requireNonNull(controlDirectory, "controlDirectory");
        Objects.requireNonNull(closeTunnels, "closeTunnels");
        if (controlDirectory.filter(path -> !path.isAbsolute()).isPresent()) {
            throw new IllegalArgumentException("proxy control directory must be absolute");
        }
        if (mode == Mode.OFFLINE
                && (grantId.isPresent() || proxyEndpoint.isPresent() || controlDirectory.isPresent())) {
            throw new IllegalArgumentException("offline access cannot contain a proxy grant");
        }
        if (mode == Mode.PROXY_ONLY) {
            String id = grantId.orElseThrow(() -> new IllegalArgumentException("proxy grant is required"));
            if (!id.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,199}")) {
                throw new IllegalArgumentException("invalid proxy grant ID");
            }
            InetSocketAddress endpoint =
                    proxyEndpoint.orElseThrow(() -> new IllegalArgumentException("proxy endpoint is required"));
            if (endpoint.isUnresolved()
                    || !endpoint.getAddress().getHostAddress().equals("127.0.0.1")
                    || endpoint.getPort() == 0) {
                throw new IllegalArgumentException("proxy endpoint must be resolved 127.0.0.1 with an exact port");
            }
        }
    }

    /** 创建默认断网配置。 */
    public static SandboxNetworkAccess offline() {
        return new SandboxNetworkAccess(Mode.OFFLINE, Optional.empty(), Optional.empty(), Optional.empty(), () -> {});
    }

    /**
     * 创建仅代理配置；调用方必须持有覆盖完整命令子进程树生命周期的有效租约。
     *
     * @param grantId 服务端拥有的租约 ID
     * @param endpoint 已启动并保持监听的精确 Broker 端点
     * @return 不含任意目标网络授权的配置
     */
    public static SandboxNetworkAccess proxyOnly(String grantId, InetSocketAddress endpoint) {
        return new SandboxNetworkAccess(
                Mode.PROXY_ONLY, Optional.of(grantId), Optional.of(endpoint), Optional.empty(), () -> {});
    }

    /**
     * 创建带应用 IPC 根的代理配置，Linux 在该目录内创建私有 Unix socket。
     *
     * @param grantId 有效命令网络租约
     * @param endpoint 精确 Broker 端点
     * @param controlDirectory 服务端拥有且已创建的绝对目录，需满足平台 socket 路径长度限制
     * @return 受信任配置，执行器拥有并清理其下临时资源
     */
    public static SandboxNetworkAccess proxyOnly(String grantId, InetSocketAddress endpoint, Path controlDirectory) {
        return new SandboxNetworkAccess(
                Mode.PROXY_ONLY, Optional.of(grantId), Optional.of(endpoint), Optional.of(controlDirectory), () -> {});
    }

    /**
     * 创建由 Broker 拥有隧道关闭回调的代理配置，便于原生撤销时先切断隧道再结束命令树。
     *
     * @param grantId 有效租约 ID
     * @param endpoint 精确代理端点
     * @param controlDirectory 应用拥有的 IPC 根
     * @param closeTunnels 幂等且有界的 Broker 隧道关闭操作，不包含任意用户命令
     * @return 只能在本地平台层传递的受信任配置
     */
    public static SandboxNetworkAccess proxyOnly(
            String grantId, InetSocketAddress endpoint, Path controlDirectory, Runnable closeTunnels) {
        return new SandboxNetworkAccess(
                Mode.PROXY_ONLY,
                Optional.of(grantId),
                Optional.of(endpoint),
                Optional.of(controlDirectory),
                closeTunnels);
    }
}
