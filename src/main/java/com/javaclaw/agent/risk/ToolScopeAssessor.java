package com.javaclaw.agent.risk;

/**
 * 工具影响范围评估端口（领域抽象）。
 *
 * <p>在托管任务执行期间，{@code ToolConfirmationManager} 遇到目录作用域的高风险工具时，
 * 调用本端口判定该工具操作的"副作用范围"是否完全限定在任务设置的工作目录内；若是则可
 * 自动放行、免去人工确认。具体实现（{@link LlmToolScopeAssessor}）用轻量模型单次调用完成，
 * 与确认管理器解耦，便于替换/测试/无头降级。</p>
 *
 * <p>实现约定：<b>绝不默认放行</b>——任何不确定、解析失败、模型异常都应返回
 * {@link ScopeVerdict#outOfScope(String)}（withinScope=false），把决定权交回人工。</p>
 */
@FunctionalInterface
public interface ToolScopeAssessor {

    /**
     * 评估一次高风险工具操作的影响范围。
     *
     * @param toolName    工具名（如 {@code sys_file_write} / {@code cmd_execute}）
     * @param description 工具自带的人类可读操作描述（含路径/命令等关键信息）
     * @param workDir     任务设置的工作目录绝对路径（影响范围基准）
     * @return 判定结果；任何异常/不确定均应返回 withinScope=false
     */
    ScopeVerdict assess(String toolName, String description, String workDir);
}
