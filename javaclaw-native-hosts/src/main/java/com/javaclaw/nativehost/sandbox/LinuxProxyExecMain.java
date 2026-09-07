package com.javaclaw.nativehost.sandbox;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import com.javaclaw.nativehost.ffm.LinuxSocketPolicy;
import com.javaclaw.nativehost.ffm.NativeResourceLimits;

/** namespace 内启动独立 relay，确认就绪后把自身原子替换为已批准命令。 */
public final class LinuxProxyExecMain {
    private LinuxProxyExecMain() {}

    /**
     * 启动固定 relay，然后施加可继承 seccomp 并 exec 原始 argv；没有无代理回退。
     *
     * @param arguments IPC socket、端口、分隔符及原始命令
     * @throws IOException relay 未就绪或发行运行库不可用
     */
    public static void main(String[] arguments) throws IOException {
        if (arguments.length < 4 || !arguments[2].equals("--")) {
            throw new IllegalArgumentException("invalid Linux proxy launch arguments");
        }
        SandboxJavaRuntime runtime = SandboxJavaRuntime.forWorker(LinuxProxyRelayMain.class);
        ProcessBuilder builder =
                new ProcessBuilder(runtime.command(LinuxProxyRelayMain.class, List.of(arguments[0], arguments[1]), 32));
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process relay = builder.start();
        try {
            relay.getOutputStream().close();
            if (relay.getInputStream().read() != 1) {
                throw new IOException("Linux namespace proxy relay did not become ready");
            }
            relay.getInputStream().close();
            LinuxSocketPolicy.install();
            NativeResourceLimits.exec(Arrays.asList(Arrays.copyOfRange(arguments, 3, arguments.length)));
        } finally {
            // exec 成功不会返回；失败时显式收回已启动的独立 relay。
            SandboxProcessTerminator.terminate(relay);
        }
    }
}
