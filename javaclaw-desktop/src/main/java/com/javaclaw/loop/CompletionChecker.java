package com.javaclaw.loop;

import com.javaclaw.agent.goal.SuccessCriterion;
import com.javaclaw.framework.api.TaskOutcome;
import com.javaclaw.loop.model.CompletionCheck;
import com.javaclaw.loop.model.IterationResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 完成判定器：Harness 任务结果与显式客观谓词共同裁决。
 *
 * <p>轮次汇报仅提供进度和延迟；正文与汇报里的 done 字段均不能授予完成。</p>
 *
 * <p><b>核验缓存</b>：命令类核验（可能是 {@code mvn test} 这种分钟级开销）与验收员核验
 * （一次模型调用）不必每轮重跑——只在「本轮有工具行动」「等待外部条件」或「执行体提议完成
 * （终审）」时重测，其余轮次复用上一次核验结论。等待轮信号由控制器统一裁定后传入
 * {@link #check}（含执行体自报延迟与 INTERVAL 定时轮询两种来源——轮询语义下每轮
 * 都在等外部变化，缓存必须逐轮失效，否则外部条件达成也永远测不到）。
 * {@code output_contains} 对累计输出匹配、开销可忽略，每轮都测。
 * 有状态（缓存），每个循环一个实例。</p>
 */
public final class CompletionChecker {

    private final List<SuccessCriterion> criteria;
    private final CriterionVerifier verifier;
    private final CompletionJudge judge;
    private final boolean useJudge;

    /** 各准则（按下标）最近一次核验结论的缓存。 */
    private final Map<Integer, Boolean> verdictCache = new HashMap<>();

    public CompletionChecker(List<SuccessCriterion> criteria, CriterionVerifier verifier,
                             CompletionJudge judge, boolean useJudge) {
        this.criteria = criteria == null ? List.of() : criteria;
        this.verifier = verifier;
        this.judge = judge == null ? CompletionJudge.CONSERVATIVE_DENY : judge;
        this.useJudge = useJudge;
    }

    /**
     * 核验本轮是否达成完成。
     *
     * @param result    本轮产出
     * @param context   接力上下文（已 record 本轮结果）
     * @param waitRound 本轮是否为等待轮（执行体自报延迟或 INTERVAL 定时轮询，由控制器裁定）——
     *                  等待轮意味着世界状态可能被外部改变，命令/文件类核验缓存需失效重测
     */
    public CompletionCheck check(IterationResult result, CarryContext context, boolean waitRound) {
        boolean harnessDone = result.taskResult() != null
                && (result.taskResult().outcome() == TaskOutcome.VERIFIED_COMPLETE
                    || result.taskResult().outcome() == TaskOutcome.DELIVERED);

        // 无额外 Loop 准则时，当前子 Run 的 Harness TaskResult 即为唯一完成判据。
        if (criteria.isEmpty()) {
            return harnessDone
                    ? new CompletionCheck(true, 0, 0, List.of())
                    : new CompletionCheck(false, 0, 0, List.of("Harness 尚未确认任务完成"));
        }

        // 有准则：逐条核验（带缓存），freeform/external 交验收员
        boolean acted = !result.toolCalls().isEmpty();
        boolean waiting = waitRound;
        int satisfied = 0;
        List<String> missing = new ArrayList<>();
        for (int i = 0; i < criteria.size(); i++) {
            SuccessCriterion c = criteria.get(i);
            boolean verdict;
            if (shouldReverify(c, i, acted, waiting, harnessDone)) {
                verdict = verifier.verify(c, context);
                verdictCache.put(i, verdict);
            } else {
                verdict = verdictCache.get(i);
            }
            if (verdict) {
                satisfied++;
            } else {
                missing.add(c.toString());
            }
        }
        boolean allPass = missing.isEmpty();
        boolean done = harnessDone && allPass;
        return new CompletionCheck(done, satisfied, criteria.size(), missing);
    }

    /**
     * 是否需要重新核验该准则（缓存失效策略）：
     * <ul>
     *   <li>首次核验——必测；</li>
     *   <li>{@code output_contains}——对累计输出的纯文本匹配，开销可忽略，每轮都测；</li>
     *   <li>{@code command_exit_zero}/{@code artifact_exists}——世界状态核验，在本轮有
     *       工具行动（世界可能被改了）、等待轮（自报延迟 / INTERVAL 轮询，等的就是外部变化，
     *       如轮询 CI）或执行体提议完成（终审——世界状态可能被<b>外部</b>进程改变，如
     *       「等某文件出现」的目标由外部构建产出该文件，执行体无事可做只报 done，缓存的
     *       否定结论若不失效将令客观已达成的目标永远判不完、循环以 NO_PROGRESS 误终）时重测；</li>
     *   <li>{@code freeform}/{@code external_check}——验收员核验（一次模型调用），在有行动/
     *       等待外部变化/执行体提议完成（终审）时重测。</li>
     * </ul>
     */
    private boolean shouldReverify(SuccessCriterion c, int index,
                                   boolean acted, boolean waiting, boolean proposedDone) {
        if (!verdictCache.containsKey(index)) {
            return true;
        }
        String type = c.normalizedType();
        return switch (type) {
            case LoopConstants.CRITERION_OUTPUT_CONTAINS -> true;
            default -> acted || waiting || proposedDone;
        };
    }
}
