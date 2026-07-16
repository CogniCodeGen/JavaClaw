package com.javaclaw.loop.model;

/**
 * 一轮的最终裁决：决策 + （若停止）原因 + 人类可读说明。
 *
 * @param decision   裁决出口
 * @param stopReason 停止原因（仅 {@link Decision#STOP} 时非 null）
 * @param message    可读说明（依据/剩余项/停止原因描述）
 */
public record LoopVerdict(Decision decision, StopReason stopReason, String message) {

    /** 完成。 */
    public static LoopVerdict done(String message) {
        return new LoopVerdict(Decision.DONE, null, message);
    }

    /** 继续。 */
    public static LoopVerdict continueLoop(String message) {
        return new LoopVerdict(Decision.CONTINUE, null, message);
    }

    /** 停止（采用原因自带的描述）。 */
    public static LoopVerdict stop(StopReason reason) {
        return new LoopVerdict(Decision.STOP, reason, reason.description());
    }

    /** 停止（自定义说明）。 */
    public static LoopVerdict stop(StopReason reason, String message) {
        return new LoopVerdict(Decision.STOP, reason, message);
    }
}
