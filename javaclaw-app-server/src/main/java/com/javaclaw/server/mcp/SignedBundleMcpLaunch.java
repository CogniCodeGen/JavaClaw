package com.javaclaw.server.mcp;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.ResourceLimits;

/**
 * 已验签 Bundle 中 MCP stdio 贡献的不可变启动快照。
 *
 * @param bundleId Bundle 标识
 * @param bundleRevision 当前安装 revision
 * @param contributionId MCP 贡献标识
 * @param descriptor 已签名 MCP 描述
 * @param bundleRoot 已验证安装根
 * @param argv 不经过 shell 的入口参数
 * @param lifetime Worker 最长生命周期
 * @param limits Worker 资源上限
 */
public record SignedBundleMcpLaunch(
        String bundleId,
        long bundleRevision,
        String contributionId,
        CanonicalPayload descriptor,
        Path bundleRoot,
        List<String> argv,
        Duration lifetime,
        ResourceLimits limits) {
    /** 校验所有启动边界并复制集合。 */
    public SignedBundleMcpLaunch {
        bundleId = text(bundleId, "bundleId");
        if (bundleRevision < 1) {
            throw new IllegalArgumentException("bundleRevision must be positive");
        }
        contributionId = text(contributionId, "contributionId");
        Objects.requireNonNull(descriptor, "descriptor");
        bundleRoot = Objects.requireNonNull(bundleRoot, "bundleRoot")
                .toAbsolutePath()
                .normalize();
        argv = List.copyOf(Objects.requireNonNull(argv, "argv"));
        if (argv.isEmpty() || argv.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException("argv must contain non-blank values");
        }
        Objects.requireNonNull(lifetime, "lifetime");
        Objects.requireNonNull(limits, "limits");
    }

    private static String text(String value, String name) {
        String checked = Objects.requireNonNull(value, name).strip();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return checked;
    }
}
