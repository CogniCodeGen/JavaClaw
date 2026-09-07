package com.javaclaw.nativehost.sandbox;

import java.util.List;

import com.javaclaw.nativehost.ffm.LinuxSocketPolicy;
import com.javaclaw.nativehost.ffm.NativeResourceLimits;

/** namespace 内固定命令入口；安装可继承网络与调试过滤后立即 exec。 */
public final class LinuxCommandExecMain {
    private LinuxCommandExecMain() {}

    /**
     * 把本进程替换为已批准的命令，不解析 shell，不保留可供命令利用的 Unix socket。
     *
     * @param arguments 已验证的完整 argv
     */
    public static void main(String[] arguments) {
        if (arguments.length == 0) {
            throw new IllegalArgumentException("Linux command argv is required");
        }
        LinuxSocketPolicy.install();
        NativeResourceLimits.exec(List.of(arguments));
    }
}
