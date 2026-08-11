package com.javaclaw.infrastructure.agent;

import com.javaclaw.agent.expert.CustomAgentConfig;
import com.javaclaw.agent.expert.CustomAgentConfig.CustomAgentDef;
import com.javaclaw.application.agent.AgentDefinitionPort;
import com.javaclaw.application.agent.AgentManagementApplicationService.Agent;
import com.javaclaw.config.AgentConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** 将工作区 CustomAgentConfig 和只读内置目录适配到应用端口。 */
public final class CustomAgentDefinitionAdapter implements AgentDefinitionPort {

    private final CustomAgentConfig customAgents;
    private final AgentConfig settings;

    public CustomAgentDefinitionAdapter(
            CustomAgentConfig customAgents,
            AgentConfig settings) {
        this.customAgents = Objects.requireNonNull(customAgents, "customAgents");
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    @Override
    public List<Agent> list() {
        List<Agent> result = new ArrayList<>(builtIns());
        customAgents.getAll().stream().map(CustomAgentDefinitionAdapter::toAgent)
                .forEach(result::add);
        return List.copyOf(result);
    }

    @Override
    public Agent create(String name) {
        return toAgent(customAgents.create(name));
    }

    @Override
    public void update(Agent agent) {
        customAgents.update(toDefinition(agent));
    }

    @Override
    public boolean delete(String id) {
        if (customAgents.get(id) == null) return false;
        customAgents.delete(id);
        return true;
    }

    private List<Agent> builtIns() {
        return List.of(
                builtIn("coding_expert", AgentConfig.CODING_AGENT_NAME,
                        AgentConfig.CODING_AGENT_DESCRIPTION, 1),
                builtIn("task_evaluator", AgentConfig.EVALUATOR_AGENT_NAME,
                        AgentConfig.EVALUATOR_AGENT_DESCRIPTION, 1),
                builtIn("knowledge_expert", AgentConfig.KNOWLEDGE_AGENT_NAME,
                        AgentConfig.KNOWLEDGE_AGENT_DESCRIPTION, 1),
                builtIn("web_expert", AgentConfig.WEB_AGENT_NAME,
                        AgentConfig.WEB_AGENT_DESCRIPTION, settings.getWebAgentMaxIters()),
                builtIn("email_expert", AgentConfig.EMAIL_AGENT_NAME,
                        AgentConfig.EMAIL_AGENT_DESCRIPTION, settings.getEmailAgentMaxIters()),
                builtIn("system_expert", AgentConfig.SYSTEM_AGENT_NAME,
                        AgentConfig.SYSTEM_AGENT_DESCRIPTION, settings.getSystemAgentMaxIters()),
                builtIn("notification_expert", AgentConfig.NOTIFICATION_AGENT_NAME,
                        AgentConfig.NOTIFICATION_AGENT_DESCRIPTION,
                        settings.getNotificationAgentMaxIters()));
    }

    private static Agent builtIn(String toolName, String name, String description, int maxIters) {
        return new Agent("builtin_" + toolName, name, toolName, description,
                "", maxIters, true, true);
    }

    private static Agent toAgent(CustomAgentDef definition) {
        return new Agent(definition.id, definition.name, definition.toolName,
                definition.description, definition.sysPrompt, definition.maxIters,
                definition.enabled, false);
    }

    private static CustomAgentDef toDefinition(Agent agent) {
        CustomAgentDef definition = new CustomAgentDef();
        definition.id = agent.id();
        definition.name = agent.name();
        definition.toolName = agent.toolName();
        definition.description = agent.description();
        definition.sysPrompt = agent.systemPrompt();
        definition.maxIters = agent.maxIters();
        definition.enabled = agent.enabled();
        return definition;
    }
}
