package com.javaclaw.agent.model;

import io.agentscope.core.formatter.openai.OpenAIMultiAgentFormatter;
import io.agentscope.core.formatter.openai.dto.OpenAIRequest;
import io.agentscope.core.model.ToolSchema;

import java.util.List;

/**
 * 多智能体兼容性格式化器 — 修复工具参数中的裸 object 类型
 *
 * <p>继承 {@link OpenAIMultiAgentFormatter} 以支持 MsgHub 多智能体协作场景，
 * 通过 {@link ToolSchemaFixer} 复用裸 object 修复逻辑。</p>
 *
 * @author JavaClaw
 */
public class ToolSchemaFixMultiAgentFormatter extends OpenAIMultiAgentFormatter {

    @Override
    public void applyTools(OpenAIRequest request, List<ToolSchema> tools) {
        super.applyTools(request, tools);
        ToolSchemaFixer.fixTools(request);
    }
}
