package com.javaclaw.extension.spi;

/** 一个 Extension Job 工作单元的持久提交状态。 */
public enum ExtensionJobUnitState {
    /** 副作用执行前已持久化确定性意图。 */
    INTENT_RECORDED,
    /** 结果、checkpoint 与可选 EffectReceipt 已原子提交。 */
    COMPLETED,
    /** 工作单元失败且 Job 已进入失败终态。 */
    FAILED
}
