package com.javaclaw.loop;

import com.javaclaw.agent.goal.SuccessCriterion;
import com.javaclaw.task.sdd.verify.CommandRunner;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 单条成功准则核验器：能确定性验证的绝不问模型。
 *
 * <ul>
 *   <li>{@code command_exit_zero}——真跑命令看退出码（复用注入的 {@link CommandRunner}）；</li>
 *   <li>{@code artifact_exists}——真查文件/目录是否存在；</li>
 *   <li>{@code output_contains}——对累计输出做字面关键词子串匹配；</li>
 *   <li>{@code external_check} / {@code freeform} / 未知类型码——无法确定性核验，交验收员（且需启用）。</li>
 * </ul>
 */
public final class CriterionVerifier {

    private final CommandRunner commandRunner;
    private final CompletionJudge judge;
    private final String workDir;
    private final boolean useJudge;

    public CriterionVerifier(CommandRunner commandRunner, CompletionJudge judge,
                             String workDir, boolean useJudge) {
        this.commandRunner = commandRunner;
        this.judge = judge == null ? CompletionJudge.CONSERVATIVE_DENY : judge;
        this.workDir = workDir;
        this.useJudge = useJudge;
    }

    /**
     * 该归一化类型码是否可本地确定性核验（命令/文件/关键词三类）。
     *
     * <p>与 {@link #verify} 的 switch 分派是同一份口径的<b>单一来源</b>：LoopService 装配期
     * 据此统计「无法本地核验」的准则数以决定是否自动启用验收员——两处各写一份清单的话，
     * 新增本地可核验类型漏改一边会错烧验收员调用或让核验腿静默缺位。</p>
     */
    public static boolean isLocallyVerifiable(String normalizedType) {
        return switch (normalizedType == null ? "" : normalizedType) {
            case LoopConstants.CRITERION_COMMAND_EXIT_ZERO,
                 LoopConstants.CRITERION_ARTIFACT_EXISTS,
                 LoopConstants.CRITERION_OUTPUT_CONTAINS -> true;
            default -> false;
        };
    }

    /**
     * 核验单条准则是否满足。
     *
     * @param criterion 待核验准则
     * @param context   当前接力上下文（提供本轮输出与进展）
     */
    public boolean verify(SuccessCriterion criterion, CarryContext context) {
        if (criterion == null) {
            return false;
        }
        String type = criterion.normalizedType();
        return switch (type) {
            case LoopConstants.CRITERION_COMMAND_EXIT_ZERO ->
                    commandRunner != null
                            && criterion.predicate != null && !criterion.predicate.isBlank()
                            && commandRunner.run(criterion.predicate.trim(), workDir).success();
            case LoopConstants.CRITERION_ARTIFACT_EXISTS ->
                    artifactExists(criterion.predicate);
            case LoopConstants.CRITERION_OUTPUT_CONTAINS ->
                    // 对累计输出匹配（非仅本轮）：早前轮已产出的内容不因收尾轮未复述而丢失
                    outputContains(context.cumulativeOutput(), criterion.predicate);
            // external_check / freeform 以及未知类型码（LLM 产出五类之外的变体如 file_exists）
            // 统一交验收员：都无法本地确定性核验；未知类型若落 false 死分支，该准则永不满足、
            // done 永不可达，循环只能烧到上限以失败收场。验收员未启用则保守 false——装配层
            // （LoopService）已把这类准则计入「无法本地核验」自动启用验收员，除非用户显式 judge=off
            default -> useJudge && judge.criterionMet(criterion, context.transcript()).met();
        };
    }

    /** 产物存在性：相对路径相对工作目录解析。 */
    private boolean artifactExists(String predicate) {
        if (predicate == null || predicate.isBlank()) {
            return false;
        }
        try {
            Path target = Path.of(predicate.trim());
            Path resolved = target.isAbsolute() || workDir == null || workDir.isBlank()
                    ? target
                    : Path.of(workDir).resolve(target);
            return Files.exists(resolved);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 输出包含：predicate 作为<b>字面关键词</b>做子串匹配。
     *
     * <p>不按正则编译——predicate 由 GoalManager 分解产出，定义就是关键词（见 GoalPrompts），
     * 字面量里的元字符按正则解释会两头出错：{@code 版本 3.14} 的 {@code .} 匹配任意字符
     * （输出含 {@code 3714} 也算过 → 假完成）；含中缀 {@code $} 的合法正则永远匹配不上
     * （准则永不满足 → 烧满轮数上限）。假阳性违背「绝不默认放行」，字面匹配两种都杜绝。</p>
     */
    private boolean outputContains(String output, String predicate) {
        if (output == null || output.isEmpty() || predicate == null || predicate.isBlank()) {
            return false;
        }
        return output.contains(predicate.trim());
    }
}
