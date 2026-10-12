package com.javaclaw.plugin.runner;

/** Runner 先发布受控回环端口，再由鉴权 Initialize 请求加载插件。 */
public final class PluginRunnerMain {
    private PluginRunnerMain() { }
    public static void main(String[] args) throws Exception {
        if (args.length != 0) throw new IllegalArgumentException("Runner 不接受命令行凭据或入口参数");
        RunnerBootstrap bootstrap = RunnerBootstrap.fromEnvironment(System.getenv());
        try (PluginRunnerServer server = new PluginRunnerServer(bootstrap).start()) {
            Runtime.getRuntime().addShutdownHook(Thread.ofPlatform().unstarted(server::close));
            System.out.println("JAVACLAW_PLUGIN_V2_READY {\"port\":" + server.port() + ",\"protocolMajor\":2}");
            System.out.flush();
            // 插件日志不能污染启动握手通道，宿主只从 stderr 收集后续日志。
            System.setOut(System.err);
            String parent = System.getenv("JAVACLAW_PLUGIN_PARENT_PID");
            if (parent != null && !parent.isBlank()) {
                ProcessHandle.of(Long.parseLong(parent)).ifPresentOrElse(handle ->
                        handle.onExit().thenRun(server::close), server::close);
            }
            server.awaitTermination();
        }
    }
}
