package com.javaclaw.server.bootstrap;

/** Headless process entry point. stdout remains reserved for JSONL or mux frames. */
public final class AppServerMain {
    private AppServerMain() {}

    /**
     * 解析启动参数、装配组件图并进入本地传输循环；结束时按依赖顺序关闭资源，不开放远程 RPC。
     *
     * @throws Exception 数据根、组件装配或传输启动失败
     */
    public static void main(String[] args) throws Exception {
        // 第三方适配器首次加载时可能使用 stdout 诊断；协议独占原始流，普通输出一律进入 stderr。
        // 必须在组件装配前切换，避免初始化日志破坏 JSONL 并触发 SDK 的幂等重连循环。
        var protocolOutput = System.out;
        System.setOut(System.err);
        var logger = AppServerLogging.initialize(args);
        try {
            try (ServerComponentGraph graph = ServerComponentGraph.create(args)) {
                graph.serve(System.in, protocolOutput);
            }
            logger.info("App Server 正常停止");
        } catch (Exception | LinkageError failure) {
            // 只记录脱敏后的原因链；Provider 响应体和完整凭据不得进入产品日志。
            logger.error("App Server 异常退出：{}", AppServerLogging.summarize(failure));
            throw failure;
        } finally {
            System.setOut(protocolOutput);
        }
    }
}
