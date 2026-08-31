package com.javaclaw.launcher;

import com.javaclaw.desktop.JavaClawDesktop;

/**
 * JavaClaw 唯一的桌面产品入口，IDE 可直接运行并设置断点，不依赖启动脚本或预生成发行目录。
 *
 * <p>入口只解析当前 IDE/classpath 或安装包布局，再把进程配置交给 SDK 和 Desktop；App Server 仍由 SDK 以独立进程管理，从而保持客户端与运行时的架构边界。此类不能继承 JavaFX
 * Application，否则 JDK 会在 classpath 启动阶段错误地要求模块化 JavaFX runtime。
 */
public final class JavaClawLauncher {
    private JavaClawLauncher() {}

    /**
     * 自动定位运行依赖后启动桌面，直到窗口退出；--help 仅打印用法，不启动 JavaFX 或后台服务。
     *
     * @param args 非空参数数组，除 --help 外原样交给 JavaFX
     * @throws Exception 依赖定位或启动失败；详细原因保留在异常链，进程以失败状态退出
     */
    public static void main(String[] args) throws Exception {
        if (args.length == 1 && ("--help".equals(args[0]) || "-h".equals(args[0]))) {
            System.out.println("""
                    JavaClaw 4.0
                    Development: run com.javaclaw.launcher.JavaClawLauncher directly with the
                                 javaclaw-packaging module classpath (JDK 25); no script or distribution required.
                    Terminal helper: ./run.sh or run.cmd; --no-build reuses an existing distribution.
                    Default storage: <program>/.javaclaw/{data-v4,config-v4,cache-v4}.
                    Overrides: JAVACLAW_PROGRAM_DIR, JAVACLAW_DATA_DIR, JAVACLAW_CONFIG_DIR,
                               JAVACLAW_CACHE_DIR.
                    """);
            return;
        }
        var layout = RuntimeLayout.discover();
        var logger = DesktopProductLogging.initialize(layout.infrastructureEnvironment());
        try {
            JavaClawDesktop.launch(layout, args);
            logger.info("Desktop 正常停止");
        } catch (Exception | LinkageError failure) {
            logger.error("Desktop 启动或运行失败：{}", DesktopProductLogging.summarize(failure));
            System.err.println("JavaClaw 启动失败：" + failure.getMessage());
            System.err.println("开发调试请直接运行 com.javaclaw.launcher.JavaClawLauncher，并选择 JDK 25 和 "
                    + "javaclaw-packaging 模块 classpath；脚本仅是终端便捷入口。详细原因见异常链和服务端日志。");
            throw failure;
        }
    }
}
