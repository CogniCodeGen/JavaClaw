package com.javaclaw.extension.spi;

/** 多 Turn 业务状态机；Kernel 不感知任何实现的阶段或领域状态。 */
@FunctionalInterface
public interface TurnOrchestrator {
    /**
     * 执行编排。
     *
     * @param request 扩展请求
     * @param context 托管事务、单 Turn 和取消等受限平台端口
     * @return 编排结果
     * @throws Exception 编排失败
     */
    ExtensionResponse orchestrate(ExtensionRequest request, ExtensionExecutionContext context) throws Exception;
}
