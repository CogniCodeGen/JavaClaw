package com.javaclaw.nativehost.ffm;

import java.time.Duration;
import java.util.Objects;

import com.javaclaw.api.ResourceLimits;

/** 与签名系统守护共用的固定 ASCII v1 协议；字段不携带命令、路径或可由模型替换的管理员操作。 */
final class WindowsGuardProtocol {
    private WindowsGuardProtocol() {}

    static String attach(
            long processId, String profile, String sid, int port, Duration timeout, ResourceLimits limits) {
        if (processId < 1 || processId > 0xffff_ffffL || port < 1 || port > 65535) {
            throw new IllegalArgumentException("network guard process or endpoint is invalid");
        }
        if (!Objects.requireNonNull(profile, "profile").matches("JavaClaw\\.Sandbox\\.v6\\.[0-9a-f]{32}")
                || !Objects.requireNonNull(sid, "sid").matches("S-1-15-2(?:-[0-9]{1,10}){1,15}")) {
            throw new IllegalArgumentException("network guard AppContainer identity is invalid");
        }
        long millis = Objects.requireNonNull(timeout, "timeout").toMillis();
        if (millis < 1
                || millis > 86_400_000
                || limits.memoryBytes() > 64L * 1024 * 1024 * 1024
                || limits.childProcesses() >= 1024) {
            throw new IllegalArgumentException("network guard resource ceiling exceeded");
        }
        return "JCG1\tATTACH\t" + processId + "\t" + port + "\t" + millis + "\t" + limits.memoryBytes() + "\t"
                + Math.addExact(limits.childProcesses(), 1) + "\t" + profile + "\t" + sid;
    }
}
