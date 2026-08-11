package com.javaclaw.application.agent;

import com.javaclaw.application.error.ConflictException;
import com.javaclaw.application.error.RejectedException;
import com.javaclaw.application.error.ValidationException;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/** 智能体目录、编辑和提示词优化用例；不持有任何页面状态。 */
public final class AgentManagementUseCase implements AgentManagementApplicationService {

    private static final Pattern TOOL_NAME = Pattern.compile("[a-zA-Z_][a-zA-Z0-9_]*");

    private final AgentDefinitionPort definitions;
    private final AgentPromptOptimizationPort optimizer;

    public AgentManagementUseCase(
            AgentDefinitionPort definitions,
            AgentPromptOptimizationPort optimizer) {
        this.definitions = Objects.requireNonNull(definitions, "definitions");
        this.optimizer = Objects.requireNonNull(optimizer, "optimizer");
    }

    @Override
    public Catalog catalog() {
        // 适配器定义稳定的内置智能体展示顺序；应用层不能按名称重排并改变既有 UI 语义。
        return new Catalog(definitions.list());
    }

    @Override
    public ChangeResult create() {
        Agent created = definitions.create("新智能体");
        return new ChangeResult(created, catalog());
    }

    @Override
    public Catalog save(SaveAgentCommand command) {
        Objects.requireNonNull(command, "command");
        Agent existing = catalog().require(command.id());
        if (existing.builtIn()) {
            throw new ConflictException("内置智能体不可编辑：" + existing.name());
        }
        String name = required(command.name(), "名称不能为空");
        String toolName = required(command.toolName(), "工具名不能为空");
        if (!TOOL_NAME.matcher(toolName).matches()) {
            throw new ValidationException(
                    "工具名须为英文字母/数字/下划线，且以字母或下划线开头");
        }
        if (command.maxIters() < 1 || command.maxIters() > 30) {
            throw new ValidationException("最大迭代须在 1 到 30 之间");
        }
        String normalizedToolName = toolName.toLowerCase(Locale.ROOT);
        boolean duplicate = catalog().agents().stream()
                .anyMatch(agent -> !agent.id().equals(command.id())
                        && agent.toolName().toLowerCase(Locale.ROOT)
                        .equals(normalizedToolName));
        if (duplicate) {
            throw new ConflictException("工具名已被其他智能体使用：" + toolName);
        }
        definitions.update(new Agent(
                existing.id(), name, toolName, command.description(), command.systemPrompt(),
                command.maxIters(), command.enabled(), false));
        return catalog();
    }

    @Override
    public Catalog delete(String agentId) {
        Agent existing = catalog().require(agentId);
        if (existing.builtIn()) {
            throw new ConflictException("内置智能体不可删除：" + existing.name());
        }
        if (!definitions.delete(agentId)) {
            throw new ConflictException("智能体删除失败：" + agentId);
        }
        return catalog();
    }

    @Override
    public String optimize(OptimizePromptCommand command) {
        Objects.requireNonNull(command, "command");
        String name = required(command.name(), "请先填写智能体名称");
        String result = optimizer.optimize(name, command.description(), command.draft());
        if (result == null || result.isBlank()) {
            throw new RejectedException("模型未返回有效内容，请稍后重试");
        }
        return result;
    }

    private static String required(String value, String message) {
        if (value == null || value.isBlank()) throw new ValidationException(message);
        return value.trim();
    }
}
