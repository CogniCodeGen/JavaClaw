package com.javaclaw.agent.model;

import io.agentscope.core.formatter.openai.OpenAIChatFormatter;
import io.agentscope.core.formatter.openai.dto.OpenAIRequest;
import io.agentscope.core.model.ToolSchema;

import java.util.List;

/**
 * 兼容性格式化器 — 修复工具参数中的裸 object 类型
 *
 * <p>继承 {@link OpenAIChatFormatter}，在发送前通过 {@link ToolSchemaFixer}
 * 自动修复部分 OpenAI 兼容 API（如智谱 GLM）的兼容性问题。</p>
 *
 * @author JavaClaw
 */
public class ToolSchemaFixFormatter extends OpenAIChatFormatter {

    @Override
    public void applyTools(OpenAIRequest request, List<ToolSchema> tools) {
        super.applyTools(request, tools);
        ToolSchemaFixer.fixTools(request);
    }
}
