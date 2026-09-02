package com.javaclaw.nativehost.sandbox;

import com.javaclaw.nativehost.ffm.NativeResourceLimits;

/** 在 OS Sandbox 内施加 POSIX 资源上限并原子替换为目标进程的最小 helper。 */
public final class SandboxExecMain {
    private SandboxExecMain() {}

    /**
     * 解析固定数字边界并调用 exec；该进程不承载 RPC、业务状态或降级执行路径。
     *
     * @param arguments timeoutMillis、memoryBytes、outputBytes、childProcesses、openFiles、{@code --} 与目标 argv
     */
    public static void main(String[] arguments) {
        SandboxHelperArguments parsed = SandboxHelperArguments.parse(arguments);
        NativeResourceLimits.leadProcessGroupApplyLimitsAndExec(parsed.target(), parsed.timeout(), parsed.limits());
    }
}
