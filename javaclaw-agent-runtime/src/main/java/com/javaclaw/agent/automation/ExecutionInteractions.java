package com.javaclaw.agent.automation;

import java.util.List;
import java.util.UUID;

import com.javaclaw.agent.runtime.ItemSink;
import com.javaclaw.agent.runtime.ToolExecutionContext;
import com.javaclaw.agent.runtime.TurnExecutionContext;
import com.javaclaw.agent.tool.UserInputGateway;
import com.javaclaw.core.api.ModelToolCall;
import com.javaclaw.core.api.StoredItem;
import com.javaclaw.core.api.ThreadItem;

/** 领域确认复用 Runtime 的用户输入状态，不用模型文本或特殊 Markdown 标记推进状态机。 */
public final class ExecutionInteractions {
    private final UserInputGateway inputs;

    /** 注入已装配的输入等待端口；null 表示此入口不能交互，遇到确认时必须暂停。 */
    public ExecutionInteractions(UserInputGateway inputs) {
        this.inputs = inputs;
    }

    /** 问题与回答均持久化；无人值守入口不能自行确认，取消时保留检查点。 */
    public StoredItem ask(
            TurnExecutionContext context, ItemSink events, String step, String prompt, List<String> choices)
            throws Exception {
        if (inputs == null
                || "SCHEDULE".equals(context.turn().config().attributes().get("profileKind"))
                || "true".equals(context.turn().config().attributes().get("unattended"))) {
            throw new ExecutionPausedException("需要用户确认后才能继续：" + prompt);
        }
        context.throwIfInterrupted();
        String id = "input_" + UUID.randomUUID().toString().replace("-", "");
        var call = new ModelToolCall("input-" + step, "user_input", "{}");
        var request = new UserInputGateway.Request(
                id,
                new ToolExecutionContext(
                        context.thread(), context.turn(), call, context.turn().config(), context.scope()),
                prompt,
                choices);
        var response = inputs.ask(request, () -> events.append(new ThreadItem.UserInputRequest(id, prompt, choices)));
        StoredItem evidence =
                events.append(new ThreadItem.UserInputResponse(id, response.value(), response.cancelled()));
        if (response.cancelled()) {
            throw new ExecutionPausedException("用户取消确认；已完成步骤保持不变。");
        }
        return evidence;
    }
}
