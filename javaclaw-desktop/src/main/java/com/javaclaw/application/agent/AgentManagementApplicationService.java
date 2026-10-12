package com.javaclaw.application.agent;

import java.util.List;

/**
 * 智能体设置、Shell 和工具可共享的工作区应用入口。
 *
 * <p>实例隶属工作区 Spring Context，可被并发调用。目录查询只读取内存快照；创建、保存、
 * 删除会访问 H2，提示词优化会阻塞等待外部模型，因此这些操作必须从托管 I/O 任务调用。
 * 工作区 Context 关闭后不得继续使用实例。返回对象均为不可变快照。</p>
 */
public interface AgentManagementApplicationService {

    Catalog catalog();

    ChangeResult create();

    Catalog save(SaveAgentCommand command);

    Catalog delete(String agentId);

    String optimize(OptimizePromptCommand command);

    record Agent(
            String id,
            String name,
            String toolName,
            String description,
            String systemPrompt,
            int maxIters,
            boolean enabled,
            boolean builtIn,
            java.util.Map<com.javaclaw.framework.api.CapabilityId,
                    com.fasterxml.jackson.databind.JsonNode> capabilityBindings) {

        public Agent {
            id = required(id, "智能体 id");
            name = text(name);
            toolName = text(toolName);
            description = text(description);
            systemPrompt = text(systemPrompt);
            if (maxIters < 1) throw new IllegalArgumentException("maxIters 必须大于 0");
            java.util.LinkedHashMap<com.javaclaw.framework.api.CapabilityId,
                    com.fasterxml.jackson.databind.JsonNode> copied = new java.util.LinkedHashMap<>();
            if (capabilityBindings != null) capabilityBindings.forEach((capabilityId, value) ->
                    copied.put(capabilityId, value.deepCopy()));
            capabilityBindings = java.util.Map.copyOf(copied);
        }

        public Agent(String id, String name, String toolName, String description,
                     String systemPrompt, int maxIters, boolean enabled, boolean builtIn) {
            this(id, name, toolName, description, systemPrompt, maxIters, enabled, builtIn,
                    java.util.Map.of());
        }
    }

    record Catalog(List<Agent> agents, List<com.javaclaw.framework.api.CapabilityForm> forms) {
        public Catalog {
            agents = List.copyOf(agents == null ? List.of() : agents);
            forms = List.copyOf(forms == null ? List.of() : forms);
        }

        public Catalog(List<Agent> agents) { this(agents, List.of()); }

        public Agent require(String id) {
            return agents.stream()
                    .filter(agent -> agent.id().equals(id))
                    .findFirst()
                    .orElseThrow(() -> new com.javaclaw.application.error.NotFoundException(
                            "未找到智能体：" + id));
        }
    }

    record ChangeResult(Agent agent, Catalog catalog) {
        public ChangeResult {
            agent = java.util.Objects.requireNonNull(agent, "agent");
            catalog = java.util.Objects.requireNonNull(catalog, "catalog");
        }
    }

    record SaveAgentCommand(
            String id,
            String name,
            String toolName,
            String description,
            String systemPrompt,
            int maxIters,
            boolean enabled,
            java.util.Map<com.javaclaw.framework.api.CapabilityId,
                    com.fasterxml.jackson.databind.JsonNode> capabilityBindings) {
        public SaveAgentCommand {
            capabilityBindings = java.util.Map.copyOf(
                    capabilityBindings == null ? java.util.Map.of() : capabilityBindings);
        }

        public SaveAgentCommand(String id, String name, String toolName, String description,
                                String systemPrompt, int maxIters, boolean enabled) {
            this(id, name, toolName, description, systemPrompt, maxIters, enabled,
                    java.util.Map.of());
        }
    }

    record OptimizePromptCommand(String name, String description, String draft) {}

    private static String required(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + "不能为空");
        }
        return value;
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }
}
