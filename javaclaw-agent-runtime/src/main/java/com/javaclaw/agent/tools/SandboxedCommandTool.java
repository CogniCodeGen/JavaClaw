package com.javaclaw.agent.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.javaclaw.agent.tool.RegisteredTool;
import com.javaclaw.agent.tool.ToolHandler;
import com.javaclaw.agent.tool.ToolOrigin;
import com.javaclaw.agent.tool.ToolRisk;
import com.javaclaw.core.api.ThreadItem;
import com.javaclaw.core.api.ToolDescriptor;
import com.javaclaw.sandbox.api.SandboxCommand;
import com.javaclaw.sandbox.api.SandboxPolicy;
import com.javaclaw.sandbox.api.SandboxResult;

/** Built-in command feature. All execution is delegated to the sandbox SPI. */
public final class SandboxedCommandTool {
    private static final String SCHEMA = """
            {
              "type": "object",
              "additionalProperties": false,
              "required": ["argv"],
              "properties": {
                "argv": {
                  "type": "array",
                  "minItems": 1,
                  "maxItems": 256,
                  "items": {"type": "string", "minLength": 1, "maxLength": 32768}
                }
              }
            }
            """;

    private SandboxedCommandTool() {}

    /** 注册命令工具描述及处理器；执行只能使用上下文提供的 SandboxExecutor，不能直接创建进程。 */
    public static RegisteredTool create(SandboxPolicy ceiling) {
        ToolDescriptor descriptor = new ToolDescriptor(
                "command",
                "Execute an argv vector in the platform sandbox. Shell expansion is never implicit.",
                SCHEMA);
        return new RegisteredTool(descriptor, ToolOrigin.BUILTIN, ToolRisk.HIGH, false, ceiling, context -> {
            List<String> argv = new ArrayList<>();
            context.arguments().path("argv").forEach(value -> argv.add(value.textValue()));
            SandboxCommand command = new SandboxCommand(
                    "cmd_" + UUID.randomUUID().toString().replace("-", ""),
                    argv,
                    context.call().config().workingDirectory(),
                    System.getenv(),
                    context.sandboxPolicy());
            SandboxResult result = context.sandbox().execute(command);
            ThreadItem.CommandExecution item = new ThreadItem.CommandExecution(
                    argv, result.exitCode(), result.stdout(), result.stderr(), result.timedOut(), result.truncated());
            String modelContent =
                    "exitCode=" + result.exitCode() + "\nstdout:\n" + result.stdout() + "\nstderr:\n" + result.stderr();
            return new ToolHandler.Result(item, modelContent);
        });
    }
}
