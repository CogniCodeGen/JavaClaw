package com.javaclaw.agent.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.javaclaw.agent.tool.RegisteredTool;
import com.javaclaw.agent.tool.ToolHandler;
import com.javaclaw.agent.tool.ToolOrigin;
import com.javaclaw.agent.tool.ToolRisk;
import com.javaclaw.agent.tool.UserInputGateway;
import com.javaclaw.core.api.ThreadItem;
import com.javaclaw.core.api.ToolDescriptor;
import com.javaclaw.sandbox.api.SandboxPolicy;

/** Built-in model tool that pauses a Turn until a local client responds. */
public final class UserInputTool {
    private static final String SCHEMA = """
            {
              "type":"object",
              "additionalProperties":false,
              "required":["prompt"],
              "properties":{
                "prompt":{"type":"string","minLength":1,"maxLength":10000},
                "choices":{"type":"array","maxItems":100,
                  "items":{"type":"string","minLength":1,"maxLength":10000}}
              }
            }
            """;

    private UserInputTool() {}

    /** 注册用户输入工具，使用 gateway 先登记等待再发布问题 Item，防止响应竞态。 */
    public static RegisteredTool create(UserInputGateway gateway, SandboxPolicy ceiling) {
        ToolDescriptor descriptor =
                new ToolDescriptor("ask_user", "Pause this turn and request structured input from the user.", SCHEMA);
        return new RegisteredTool(descriptor, ToolOrigin.BUILTIN, ToolRisk.LOW, false, ceiling, context -> {
                    String id = "input_" + UUID.randomUUID().toString().replace("-", "");
                    String prompt = context.arguments().path("prompt").asText();
                    List<String> choices = new ArrayList<>();
                    context.arguments().path("choices").forEach(value -> choices.add(value.asText()));
                    List<String> immutableChoices = List.copyOf(choices);
                    UserInputGateway.Response response = gateway.ask(
                            new UserInputGateway.Request(id, context.call(), prompt, immutableChoices),
                            () -> context.events()
                                    .append(new ThreadItem.UserInputRequest(id, prompt, immutableChoices)));
                    ThreadItem.UserInputResponse item =
                            new ThreadItem.UserInputResponse(id, response.value(), response.cancelled());
                    String modelContent =
                            response.cancelled() ? "User input was cancelled or timed out." : response.value();
                    return new ToolHandler.Result(item, modelContent);
                })
                .readOnly();
    }
}
