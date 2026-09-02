package com.javaclaw.extension.spi;

import java.util.Optional;

import com.javaclaw.api.CancellationToken;

/** 扩展实现的单工作单元规划与执行端口；同一调用不得推进多个领域步骤。 */
public interface ExtensionJobExecutor {
    /**
     * 根据已提交 checkpoint 规划下一个确定性工作意图。
     *
     * <p>该方法不得执行外部副作用。返回空表示执行已经自然完成，Supervisor 会提交 COMPLETED 终态。
     *
     * @param job 当前 Job 快照
     * @return 下一个工作单元，完成时为空
     */
    Optional<ExtensionJobWorkUnit> plan(ExtensionJob job);

    /**
     * 执行一个已持久化的工作单元。
     *
     * <p>崩溃恢复可能用同一 unit ID 重放调用。任何副作用必须以 unit ID 或返回的 EffectReceipt key 去重；模型自述不能充当完成证据。
     *
     * @param execution 已记录意图的执行上下文
     * @param cancellation 实时取消信号
     * @return 单元提交结果
     * @throws Exception 工具、Turn 或领域执行失败
     */
    ExtensionJobStepResult execute(ExtensionJobExecution execution, CancellationToken cancellation) throws Exception;
}
