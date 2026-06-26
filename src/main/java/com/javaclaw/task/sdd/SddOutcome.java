package com.javaclaw.task.sdd;

/**
 * 编排运行的终态结论。
 *
 * @param result  终态类型
 * @param message 可读说明（写回任务结果/通知）
 * @author JavaClaw
 */
public record SddOutcome(Result result, String message) {

    public enum Result {
        /** 验收通过并归档。 */
        COMPLETED,
        /** 多轮评审/补做未收敛或无法自动推进，需人工介入（非失败，等用户决定）。 */
        NEEDS_HUMAN,
        /** 被取消。 */
        CANCELLED,
        /** 编排过程异常。 */
        FAILED
    }

    public static SddOutcome completed(String msg) { return new SddOutcome(Result.COMPLETED, msg); }
    public static SddOutcome needsHuman(String msg) { return new SddOutcome(Result.NEEDS_HUMAN, msg); }
    public static SddOutcome cancelled() { return new SddOutcome(Result.CANCELLED, "已取消"); }
    public static SddOutcome failed(String msg) { return new SddOutcome(Result.FAILED, msg); }

    public boolean isCompleted() { return result == Result.COMPLETED; }
}
