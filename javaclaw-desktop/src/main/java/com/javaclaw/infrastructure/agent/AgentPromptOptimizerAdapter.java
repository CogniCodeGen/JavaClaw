package com.javaclaw.infrastructure.agent;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.javaclaw.application.agent.AgentPromptOptimizationPort;
import com.javaclaw.framework.api.AgentClient;
import com.javaclaw.framework.api.AgentDefinitionRef;
import com.javaclaw.framework.api.InputBlock;
import com.javaclaw.framework.api.InvocationSource;
import com.javaclaw.framework.api.PermissionSet;
import com.javaclaw.framework.api.RunLinkage;
import com.javaclaw.framework.api.RunProfileRef;
import com.javaclaw.framework.api.RunRequest;
import com.javaclaw.framework.api.RunScope;
import com.javaclaw.runtime.WorkspaceContext;

import java.util.Map;
import java.util.Objects;

/** Agent Studio prompt optimization implemented as an ordinary tool-free Agent Run. */
public final class AgentPromptOptimizerAdapter implements AgentPromptOptimizationPort {
    private static final String SYSTEM_PROMPT = """
            你是智能体提示词工程师。根据名称、用途与草稿生成可直接发布的系统提示词。
            明确角色、能力、工作流程、行为边界和输出风格；使用简体中文；不要代码围栏，
            不要附加解释。不得创建工具循环或声称拥有未配置的能力。
            """;

    private final AgentClient agents;
    private final WorkspaceContext workspace;

    public AgentPromptOptimizerAdapter(AgentClient agents, WorkspaceContext workspace) {
        this.agents = Objects.requireNonNull(agents, "agents");
        this.workspace = Objects.requireNonNull(workspace, "workspace");
    }

    @Override
    public String optimize(String name, String description, String draft) {
        if (name == null || name.isBlank()) return null;
        String input = "智能体名称：" + name.strip()
                + "\n用途描述：" + Objects.requireNonNullElse(description, "").strip()
                + "\n现有草稿：\n" + Objects.requireNonNullElse(draft, "").strip();
        var system = JsonNodeFactory.instance.textNode(SYSTEM_PROMPT);
        var disableTools = JsonNodeFactory.instance.booleanNode(true);
        RunRequest request = RunRequest.builder()
                .agent(AgentDefinitionRef.latest("system.default"))
                .profile(RunProfileRef.latest("subagent"))
                .source(new InvocationSource("studio", "prompt-optimizer"))
                .scope(new RunScope(workspace.workspaceId(), "local-user", "agent-studio"))
                .input(InputBlock.text(input))
                .linkage(RunLinkage.root(null))
                .permissionCeiling(PermissionSet.NONE)
                .attributes(Map.of(
                        "framework.systemPrompt", system,
                        "framework.disableTools", disableTools))
                .build();
        try {
            var outcome = agents.start(request).completion().toCompletableFuture().join();
            return outcome.successful() && outcome.output() != null
                    ? outcome.output().path("text").asText(null) : null;
        } catch (RuntimeException failure) {
            return null;
        }
    }
}
