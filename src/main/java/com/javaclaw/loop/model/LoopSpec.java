package com.javaclaw.loop.model;

import com.javaclaw.agent.goal.SuccessCriterion;

import java.util.List;

/**
 * 循环任务规格：一次循环的全部输入。
 *
 * <p>由服务层组装——{@code goalPrompt} 来自用户，{@code criteria} 由 {@code GoalManager}
 * 对目标分解得到（可能为空，表示纯描述性目标，只能靠执行体自评 + 验收员）。</p>
 *
 * @param goalPrompt     目标原文
 * @param workDir        工作目录绝对路径（命令/文件类准则核验的基准；可为 null）
 * @param criteria       成功准则清单（可空）
 * @param cadence        循环节奏
 * @param stopConditions 停止预算
 * @param carryForward   接力上下文策略
 * @param useJudge       是否启用模型验收员（描述性准则/主观目标的兜底判定）
 */
public record LoopSpec(
        String goalPrompt,
        String workDir,
        List<SuccessCriterion> criteria,
        Cadence cadence,
        StopConditions stopConditions,
        CarryForwardMode carryForward,
        boolean useJudge) {

    public LoopSpec {
        if (goalPrompt == null) goalPrompt = "";
        criteria = criteria == null ? List.of() : List.copyOf(criteria);
        if (cadence == null) cadence = Cadence.selfPaced();
        if (stopConditions == null) stopConditions = StopConditions.defaults();
        if (carryForward == null) carryForward = CarryForwardMode.LAST_RESULT_ONLY;
    }
}
