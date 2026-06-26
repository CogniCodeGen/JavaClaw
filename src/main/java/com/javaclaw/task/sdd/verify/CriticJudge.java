package com.javaclaw.task.sdd.verify;

import com.javaclaw.task.sdd.spec.Scenario;

/**
 * 描述性场景的判定端口 —— {@link ScenarioVerifier} 用它核验无法结构化确定核验的谓词
 * （{@code freeform} / {@code external_check}）。
 *
 * <p>由编排层注入：实现通常是一个带工作目录只读核验工具的 critic 智能体（取代 v5 的
 * ChallengerAgent 作为"阶段"的角色，收敛为统一验证机制下的一个判定器）。验证层只依赖
 * 此接口，不感知模型。</p>
 *
 * @author JavaClaw
 */
@FunctionalInterface
public interface CriticJudge {

    /**
     * 判定一个描述性场景是否在现实（工作目录产物等）中成立。
     *
     * @param scenario 待判定场景（含 Given/When/Then 与判据文本）
     * @return 判定结果
     */
    Verdict judge(Scenario scenario);

    /**
     * @param pass   是否判定通过
     * @param reason 判定理由（通过或不通过的依据，用于写回执行日志/审计）
     */
    record Verdict(boolean pass, String reason) {}
}
