package com.javaclaw.task.sdd;

/**
 * 编排过程的进度/日志回调 —— 编排器借此向外（UI/事件流/通知）报告，不直接耦合
 * {@code TaskLifecycleEvent}。B5 接缝层把这些回调翻译成具体事件。
 *
 * <p>全为 default 空实现，调用方按需覆盖。</p>
 *
 * @author JavaClaw
 */
public interface SddProgress {

    enum LogKind { DEFAULT, INFO, OK, WARN }

    /** 进入某 SDD 阶段（澄清/提案/规格/设计/任务/实现/验收/归档）。 */
    default void phase(String phaseName) {}

    /** 追加一行执行日志。 */
    default void log(String message) {}

    /** 追加带独立语义级别的日志；显示文字不参与级别判定。 */
    default void log(LogKind kind, String message) { log(message); }

    /** 进度百分比更新（0–100，来自结构化任务快照）。 */
    default void progress(int percent) {}

    SddProgress NOOP = new SddProgress() {};
}
