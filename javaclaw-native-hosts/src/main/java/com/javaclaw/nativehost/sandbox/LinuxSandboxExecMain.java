package com.javaclaw.nativehost.sandbox;

import java.util.List;

import com.javaclaw.nativehost.ffm.LinuxSecurity;
import com.javaclaw.nativehost.ffm.NativeResourceLimits;

/** Installs seccomp inside the bubblewrap namespace and then atomically execs the target. */
public final class LinuxSandboxExecMain {
    private LinuxSandboxExecMain() {}

    /** 仅供 bubblewrap 内部调用：将 args 作为非空目标 argv，先安装 seccomp，再 exec 替换当前进程；过滤器安装失败时绝不执行目标。 */
    public static void main(String[] args) {
        List<String> target = List.of(args);
        if (target.isEmpty()) {
            throw new IllegalArgumentException("target command is missing");
        }
        LinuxSecurity.installBaselineSeccomp();
        NativeResourceLimits.exec(target);
    }
}
