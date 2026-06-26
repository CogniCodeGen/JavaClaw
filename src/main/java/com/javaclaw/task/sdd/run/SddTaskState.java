package com.javaclaw.task.sdd.run;

/**
 * SDD 托管任务的运行态 —— 取代 v5 八态 TaskStatus 机器。
 *
 * <p>注意：阶段进度（澄清/规格/实现…）不在这里——它由 tasks.md 勾选折叠成百分比，
 * 是 change 的派生属性。本枚举只表达任务的<b>运行生命周期</b>这一粗粒度状态。</p>
 *
 * @author JavaClaw
 */
public enum SddTaskState {

    /** 已创建未启动。 */
    PENDING("待启动"),
    /** 正在跑（SddOrchestrator 推进中）。 */
    RUNNING("运行中"),
    /** 已暂停/取消运行（可恢复——从 change 目录首个未勾 task 续跑）。 */
    PAUSED("已暂停"),
    /** 验收通过并归档。 */
    COMPLETED("已完成"),
    /** 多轮未收敛或无法自动推进，等人工介入（非失败终态）。 */
    NEEDS_HUMAN("待人工"),
    /** 编排过程异常失败。 */
    FAILED("失败"),
    /** 已取消。 */
    CANCELLED("已取消");

    private final String label;

    SddTaskState(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** 是否为可恢复/可继续操作的活动态。 */
    public boolean isActive() {
        return this == PENDING || this == RUNNING || this == PAUSED;
    }

    /** 是否终态（不再自动推进）。 */
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }
}
