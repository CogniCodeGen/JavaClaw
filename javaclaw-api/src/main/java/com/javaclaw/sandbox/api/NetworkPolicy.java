package com.javaclaw.sandbox.api;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Network permissions are independent from filesystem permissions and approvals.
 *
 * @param mode 非空网络权限模式
 * @param allowedHosts ALLOWLIST 主机集合；其他网络模式必须为空
 */
public record NetworkPolicy(Mode mode, Set<String> allowedHosts) {
    /** 网络授权级别；ALLOWLIST 由 Broker 实施，不表示向普通沙箱进程开放原始网络。 */
    public enum Mode {
        DISABLED,
        LOOPBACK,
        ALLOWLIST,
        FULL
    }

    /** 归一主机名并复制 allowlist；拒绝在非 ALLOWLIST 模式携带主机集合。 */
    public NetworkPolicy {
        mode = Objects.requireNonNull(mode, "mode");
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        if (allowedHosts != null) {
            allowedHosts.stream()
                    .filter(Objects::nonNull)
                    .map(String::strip)
                    .filter(value -> !value.isEmpty())
                    .map(String::toLowerCase)
                    .forEach(normalized::add);
        }
        allowedHosts = Set.copyOf(normalized);
        if (mode != Mode.ALLOWLIST && !allowedHosts.isEmpty()) {
            throw new IllegalArgumentException("allowedHosts require ALLOWLIST mode");
        }
    }

    /** 返回不允许网络且无 allowlist 的策略。 */
    public static NetworkPolicy disabled() {
        return new NetworkPolicy(Mode.DISABLED, Set.of());
    }
}
