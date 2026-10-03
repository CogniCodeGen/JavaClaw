package com.javaclaw.task.sdd.verify;

import com.javaclaw.task.sdd.spec.Criterion;
import com.javaclaw.task.sdd.spec.Scenario;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * 统一的场景验收核验器 —— SDD 架构里唯一的"做到没有"判定机制。
 *
 * <p>取代 v5 三层验证（执行体自检 + 代码 fact-check 闸门 + ChallengerAgent 阶段），
 * 把验证收敛为：对每个场景的 {@link Criterion} 求值。同一机制服务三种粒度——
 * 单个 task 的判据、单个能力的场景、整个变更的全部场景。</p>
 *
 * <ul>
 *   <li>{@code artifact_exists}：产物路径在工作目录内存在 → 确定性</li>
 *   <li>{@code command_exit_zero}：命令在工作目录执行退出码 0 → 确定性</li>
 *   <li>{@code output_contains}：仅当宿主显式注入用户原始结构化谓词且格式完整时运行命令；
 *       缺授权或缺分隔符均保守判不通过</li>
 *   <li>{@code external_check} / {@code freeform}：交 {@link CriticJudge} 判定</li>
 * </ul>
 *
 * <p>{@code CommandRunner} / {@code CriticJudge} 缺省（null）时，对应类型保守判为<b>不通过</b>
 * 并在 detail 注明，绝不"默认放行"——避免重蹈 v5"无证据即通过"的漏洞。</p>
 *
 * @author JavaClaw
 */
public final class ScenarioVerifier {

    private static final Logger log = LoggerFactory.getLogger(ScenarioVerifier.class);
    public static final String OUTPUT_CONTAINS_SEP = "|||";

    private final String workDir;
    private final CommandRunner commandRunner;
    private final CriticJudge criticJudge;
    private final Set<Criterion> userAuthorizedContentPredicates;

    public ScenarioVerifier(String workDir, CommandRunner commandRunner, CriticJudge criticJudge) {
        this(workDir, commandRunner, criticJudge, Set.of());
    }

    /** Only a host-provided, user-authored criterion may authorize literal output matching. */
    public ScenarioVerifier(String workDir, CommandRunner commandRunner, CriticJudge criticJudge,
                            Set<Criterion> userAuthorizedContentPredicates) {
        this.workDir = workDir;
        this.commandRunner = commandRunner;
        this.criticJudge = criticJudge;
        this.userAuthorizedContentPredicates = Set.copyOf(userAuthorizedContentPredicates);
    }

    /** 批量核验；保持入参顺序。 */
    public List<VerificationOutcome> verifyAll(List<Scenario> scenarios) {
        if (scenarios == null) return List.of();
        return scenarios.stream().map(this::verify).toList();
    }

    /** Literal output checks must be re-evaluated and cannot inherit an earlier authorization. */
    public boolean cacheEligible(Scenario scenario) {
        return scenario != null && scenario.criterion() != null
                && !Criterion.OUTPUT_CONTAINS.equals(scenario.criterion().normalizedType());
    }

    /** 核验单个场景。 */
    public VerificationOutcome verify(Scenario s) {
        Criterion c = s.criterion();
        String type = c == null ? Criterion.FREEFORM : c.normalizedType();
        String pred = c == null ? "" : (c.predicate() == null ? "" : c.predicate().trim());
        try {
            return switch (type) {
                case Criterion.ARTIFACT_EXISTS -> verifyArtifactExists(s, pred);
                case Criterion.COMMAND_EXIT_ZERO -> verifyCommandExitZero(s, pred);
                case Criterion.OUTPUT_CONTAINS -> userAuthorizedContentPredicates.contains(c)
                        ? verifyOutputContains(s, pred)
                        : VerificationOutcome.fail(s, true,
                                "output_contains 未经用户结构化授权，拒绝执行内容匹配命令");
                default -> judgeByCritic(s); // external_check / freeform / 未知
            };
        } catch (Exception e) {
            log.warn("[Verify] 场景核验异常 [{}]: {}", s.title(), e.getMessage());
            return VerificationOutcome.fail(s, true, "核验过程异常：" + e.getMessage());
        }
    }

    private VerificationOutcome verifyArtifactExists(Scenario s, String pred) {
        if (pred.isBlank()) return VerificationOutcome.fail(s, true, "artifact_exists 谓词为空");
        Path p = resolveInWorkDir(pred);
        boolean exists = p != null && Files.exists(p);
        return new VerificationOutcome(s, exists, true,
                (exists ? "产物存在：" : "产物缺失：") + (p == null ? pred : p));
    }

    private VerificationOutcome verifyCommandExitZero(Scenario s, String pred) {
        if (pred.isBlank()) return VerificationOutcome.fail(s, true, "command_exit_zero 谓词为空");
        if (commandRunner == null) return VerificationOutcome.fail(s, true, "无命令执行器，无法核验：" + pred);
        CommandRunner.Result r = commandRunner.run(pred, workDir);
        return new VerificationOutcome(s, r.success(), true,
                "命令 [" + pred + "] 退出码=" + r.exitCode());
    }

    private VerificationOutcome verifyOutputContains(Scenario s, String pred) {
        int sep = pred.indexOf(OUTPUT_CONTAINS_SEP);
        if (sep < 0) {
            return VerificationOutcome.fail(s, true, "output_contains 缺少命令与期望子串分隔符");
        }
        String cmd = pred.substring(0, sep).trim();
        String needle = pred.substring(sep + OUTPUT_CONTAINS_SEP.length()).trim();
        if (cmd.isBlank() || needle.isBlank()) {
            return VerificationOutcome.fail(s, true, "output_contains 命令或期望子串为空");
        }
        if (commandRunner == null) return VerificationOutcome.fail(s, true, "无命令执行器，无法核验：" + cmd);
        CommandRunner.Result r = commandRunner.run(cmd, workDir);
        boolean contains = r.output() != null && r.output().contains(needle);
        boolean passed = r.success() && contains;
        return new VerificationOutcome(s, passed, true,
                "命令 [" + cmd + "] 退出码=" + r.exitCode()
                        + "，输出" + (contains ? "包含" : "不含") + " [" + needle + "]");
    }

    private VerificationOutcome judgeByCritic(Scenario s) {
        if (criticJudge == null) {
            return VerificationOutcome.fail(s, false, "无 critic 判定器，描述性场景无法核验（保守判为不通过）");
        }
        CriticJudge.Verdict v = criticJudge.judge(s);
        return new VerificationOutcome(s, v.pass(), false, v.reason());
    }

    /** 把谓词路径解析为工作目录内的绝对路径；绝对路径原样、相对路径接 workDir。 */
    private Path resolveInWorkDir(String pathLike) {
        try {
            Path p = Path.of(pathLike);
            if (p.isAbsolute()) return p.normalize();
            if (workDir == null || workDir.isBlank()) return null;
            return Path.of(workDir).resolve(p).normalize();
        } catch (Exception e) {
            return null;
        }
    }
}
