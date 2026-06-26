package com.javaclaw.task.sdd.verify;

import com.javaclaw.task.sdd.spec.Scenario;

/**
 * 单个场景的验收核验结果。
 *
 * @param scenario      被核验的场景
 * @param passed        是否通过
 * @param deterministic 是否由代码确定性判定（false = 经 critic 判定）
 * @param detail        判定依据（命令退出码、文件路径、critic 理由等），用于写回日志/审计
 * @author JavaClaw
 */
public record VerificationOutcome(Scenario scenario, boolean passed, boolean deterministic, String detail) {

    public static VerificationOutcome pass(Scenario s, boolean det, String detail) {
        return new VerificationOutcome(s, true, det, detail);
    }

    public static VerificationOutcome fail(Scenario s, boolean det, String detail) {
        return new VerificationOutcome(s, false, det, detail);
    }
}
