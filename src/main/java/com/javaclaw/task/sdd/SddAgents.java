package com.javaclaw.task.sdd;

import com.javaclaw.task.sdd.spec.Capability;
import com.javaclaw.task.sdd.spec.OpenSpecChange;
import com.javaclaw.task.sdd.spec.Proposal;
import com.javaclaw.task.sdd.spec.Scenario;
import com.javaclaw.task.sdd.spec.TaskItem;

import java.util.List;

/**
 * SDD 六阶段中模型驱动的行为端口 —— 把 LLM 调用从编排器的确定性控制流里隔离出去。
 *
 * <p>编排器只依赖本接口；AgentScope 实现（B5 接线 ReActAgent + 结构化输出 + 各 superpowers
 * 子技能提示）单独提供。所有方法以领域类型进出，不泄漏 AgentScope 类型。实现应在内部完成
 * 重试/异常处理，失败时返回空集合或抛运行时异常（编排器会按阶段兜底）。</p>
 *
 * @author JavaClaw
 */
public interface SddAgents {

    /**
     * 阶段 1-2（brainstorm + propose）：把用户需求澄清并产出变更提案。
     *
     * @param feedback 上一轮人机评审驳回时的修改意见（首轮为 null）
     */
    Proposal clarifyAndPropose(TaskContext ctx, String feedback);

    /**
     * 阶段 3（specify）：为提案产出受影响能力的规格 —— 每条需求挂 Given/When/Then 场景
     * 及可验收的 {@code Criterion}（优先给确定性谓词）。
     */
    List<Capability> specify(TaskContext ctx, Proposal proposal);

    /**
     * 阶段 4（design，按需）：仅当存在非平凡技术权衡时产出设计说明 markdown；否则返回 null。
     */
    String design(TaskContext ctx, Proposal proposal, List<Capability> capabilities);

    /**
     * 阶段 5（tasks）：把实现拆成有序、细粒度（2–5 分钟可完成）、可勾选的 {@link TaskItem}，
     * 每项写明动作 / 涉及文件 / 完成判据。
     *
     * @param feedback 计划评审驳回或验收未过的反馈（首轮为 null）
     */
    List<TaskItem> planTasks(TaskContext ctx, Proposal proposal, List<Capability> capabilities,
                             String design, String feedback);

    /**
     * 阶段 6（implement）：执行单个实现项。实现智能体内部按需走 tdd/系统化调试/代码评审纪律。
     *
     * @param current    当前要做的实现项
     * @param doneItems  已完成的上游项（供注入精简上下文）
     * @param specs      本变更的能力规格（执行体据此对齐对外行为）
     * @return 执行结果（摘要；或请求懒拆解的子项）
     */
    ExecutionResult executeTask(TaskContext ctx, TaskItem current, List<TaskItem> doneItems,
                                List<Capability> specs);

    /**
     * 验收阶段：综合场景核验有未通过项时，产出补做的实现项动作（追加到 tasks.md，不动已完成项）。
     *
     * <p>这是重规划闭环的"补救"入口——保留已完成工作，只针对未达标的场景补做修复，
     * 取代 v5"推倒重来 / 采纳优化"两分支。</p>
     *
     * @param failedScenarios 综合核验中未通过的场景
     * @param change          当前变更全貌（含已完成 tasks，供智能体避免重复）
     * @return 补做动作描述列表；空表示无可补做（编排器据此升级人工）
     */
    List<String> remediate(TaskContext ctx, List<Scenario> failedScenarios, OpenSpecChange change);

    /**
     * 单个实现项的执行结果。
     *
     * @param summary   执行摘要（写回日志/审计）
     * @param splitInto 非空表示该项太大、请求就地拆解为这些子项；空表示已就地完成
     */
    record ExecutionResult(String summary, List<String> splitInto) {
        public ExecutionResult {
            splitInto = splitInto == null ? List.of() : List.copyOf(splitInto);
        }
        public static ExecutionResult done(String summary) {
            return new ExecutionResult(summary, List.of());
        }
        public static ExecutionResult split(List<String> children) {
            return new ExecutionResult("（实现项过大，请求拆解）", children);
        }
        public boolean wantsSplit() {
            return !splitInto.isEmpty();
        }
    }
}
