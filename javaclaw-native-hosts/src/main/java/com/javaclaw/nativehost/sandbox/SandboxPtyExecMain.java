package com.javaclaw.nativehost.sandbox;

import com.javaclaw.nativehost.ffm.NativeResourceLimits;
import com.javaclaw.nativehost.ffm.PosixPty;

/** 为已经连接到 slave FD 的交互进程建立受控会话、资源边界与前台进程组。 */
public final class SandboxPtyExecMain {
    private static final int INITIAL_COLUMNS = 120;
    private static final int INITIAL_ROWS = 40;

    private SandboxPtyExecMain() {}

    /**
     * 解析资源边界、建立 controlling terminal，并原子替换为 OS Sandbox backend。
     *
     * @param arguments 与 {@link SandboxExecMain} 相同的固定 helper 参数
     */
    public static void main(String[] arguments) {
        SandboxHelperArguments parsed = SandboxHelperArguments.parse(arguments);
        NativeResourceLimits.applyCpuAndOpenFileLimits(parsed.timeout(), parsed.limits());
        PosixPty.attachControllingTerminalAndExec(parsed.target(), INITIAL_COLUMNS, INITIAL_ROWS, parsed.limits());
    }
}
