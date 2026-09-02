package com.javaclaw.extension.spi;

/** Extension query 与 command 的异步无关执行契约。 */
@FunctionalInterface
public interface ExtensionHandler {
    /**
     * 执行一次调用。实现不得持有 JDBC 连接或跨调用复用事务对象。
     *
     * @param request 调用输入
     * @param context 本次调用上下文
     * @return 调用结果
     * @throws Exception 业务或基础设施失败
     */
    ExtensionResponse handle(ExtensionRequest request, ExtensionExecutionContext context) throws Exception;
}
