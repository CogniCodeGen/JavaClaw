package com.javaclaw.agent.expert;

import com.javaclaw.agent.expert.CustomAgentConfig.CustomAgentDef;
import com.javaclaw.agent.model.ToolResponse;
import io.agentscope.core.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 智能体introspection 工具（只读）—— 让编排器查看当前可用的内置专家与自定义智能体。
 *
 * <p>注意：「在对话中创建可复用能力」改由<b>技能体系</b>承担（{@code skill_create} 等，常驻 skill 组，
 * 且技能可携带 {@code scripts/} 可执行脚本、由编排器自身工具执行），不再用纯推理的自定义智能体。
 * 故本工具集只保留只读列举；自定义智能体的增删仍可在设置面板完成。</p>
 */
public final class ExpertManageTools {

    private static final Logger log = LoggerFactory.getLogger(ExpertManageTools.class);

    private final ExpertManager expertManager;

    public ExpertManageTools(ExpertManager expertManager) {
        this.expertManager = expertManager;
    }

    @Tool(name = "agent_list", description = "列出全部子智能体：内置专家与用户在设置中创建的自定义智能体。"
            + "若要新增一种可复用能力，请改用技能（skill_create），技能能携带可执行脚本并复用编排器的工具。")
    public String agentList() {
        StringBuilder sb = new StringBuilder("内置专家：\n");
        for (ExpertManager.ExpertDef d : expertManager.getExpertDefs()) {
            sb.append("· ").append(d.agentName()).append("（").append(d.toolName()).append("）\n");
        }
        List<CustomAgentDef> customs = CustomAgentConfig.getInstance().getAll();
        sb.append("自定义智能体：");
        if (customs.isEmpty()) {
            sb.append("无（如需可复用能力，建议用 skill_create 沉淀为技能）");
        } else {
            sb.append("\n");
            for (CustomAgentDef c : customs) {
                sb.append("· [").append(c.id).append("] ").append(c.name)
                        .append(c.enabled ? "" : "（停用）").append("\n");
            }
        }
        log.debug("agent_list 调用");
        return ToolResponse.success("agent_list", sb.toString().trim());
    }
}
