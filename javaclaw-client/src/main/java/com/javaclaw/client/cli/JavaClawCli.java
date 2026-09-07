package com.javaclaw.client.cli;

import java.io.PrintStream;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import com.javaclaw.client.sdk.JavaClawClient;
import com.javaclaw.client.transport.StdioProcessTransport;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.ClientInfo;

/** Protocol v3 前台 CLI；执行、审批和结构化输入均通过 Java SDK 的既有契约完成。 */
public final class JavaClawCli {
    private JavaClawCli() {}

    /**
     * 运行 CLI。
     *
     * <p>支持 workspace-list、role-list 和 turn-start；以 {@code --} 分隔客户端参数和服务端启动命令。 turn-start 接受 Thread 标识、消息及独立
     * --role、--provider、--permission、--approval、--reasoning 选择。 --budget 依次接受输入 token、输出 token、工具调用数、直接子 Thread 数和墙钟秒数；
     * --capabilities 接受逗号分隔能力名，空字符串明确禁用全部能力。--non-interactive 禁止终端交互；非 TTY 也使用同一规则。 turn-start 等待终态，将进度和模型消息写入
     * stderr，最终 TurnStartResult JSON 写入 stdout。 退出码为成功 0、执行或连接失败 1、参数错误或缺少必需输入 2、用户取消 130。 服务端命令作为参数数组直接执行，不经过 shell。
     *
     * <p>coding 提供 catalog、toolchains、environment、save、install、job 和 cancel-job；写入要求明确 revision 与幂等键。 环境与工具链引用使用版本化
     * JSON，项目依赖准备仍在 turn-start 的受控工具中进行。
     *
     * @param arguments CLI 参数
     */
    public static void main(String[] arguments) {
        System.exit(run(arguments, CliTerminal.system(), System.out, System.err));
    }

    static int run(String[] arguments, CliTerminal terminal, PrintStream output, PrintStream errors) {
        ParsedCommand command;
        CliTurnRequest turn;
        CliCodingCommand coding;
        try {
            command = ParsedCommand.parse(arguments);
            turn = command.arguments().getFirst().equals("turn-start")
                    ? CliTurnRequest.parse(command.arguments())
                    : null;
            coding = command.arguments().getFirst().equals("coding")
                    ? CliCodingCommand.parse(command.arguments())
                    : null;
        } catch (IllegalArgumentException failure) {
            errors.println("CLI 参数错误：" + failure.getMessage());
            return 2;
        }
        try (JavaClawClient client = JavaClawClient.connect(
                new StdioProcessTransport(command.serverCommand()),
                new ClientInfo("javaclaw-cli", "6.0.0-SNAPSHOT"),
                Set.of(),
                notification -> {})) {
            switch (command.arguments().getFirst()) {
                case "coding" ->
                    output.println(
                            new CanonicalJson().encode(coding.execute(client)).json());
                case "workspace-list" -> client.workspaces().list().forEach(output::println);
                case "role-list" ->
                    client.roles()
                            .list()
                            .forEach(role -> output.println(role.id() + "@" + role.revision() + " "
                                    + role.spec().name()));
                case "turn-start" -> {
                    CliTurnRunner.Result result = new CliTurnRunner(client, errors).run(turn, terminal);
                    result.turn()
                            .ifPresent(value -> output.println(new CanonicalJson()
                                    .encode(new com.javaclaw.protocol.CoreRpcContracts.TurnStartResult(
                                            value, value.resolvedConfig()))
                                    .json()));
                    return result.exitCode();
                }
                default -> throw new IllegalArgumentException("unsupported CLI command");
            }
            return 0;
        } catch (Exception failure) {
            errors.println("CLI 执行失败：" + failure.getMessage());
            return 1;
        }
    }

    /**
     * 已校验并按分隔符拆分的 CLI 命令。
     *
     * @param arguments 客户端命令及参数，不可空、非空列表，元素不可空
     * @param serverCommand 直接执行的服务端程序及参数，不可空、非空列表，元素不可空；不经过 shell
     */
    private record ParsedCommand(List<String> arguments, List<String> serverCommand) {
        private static ParsedCommand parse(String[] arguments) {
            int separator = Arrays.asList(arguments).indexOf("--");
            if (separator < 1
                    || !Set.of("workspace-list", "role-list", "turn-start", "coding")
                            .contains(arguments[0])
                    || separator == arguments.length - 1) {
                throw new IllegalArgumentException(
                        "usage: javaclaw <workspace-list|role-list|turn-start|coding> [arguments] -- <server executable> [server args...]");
            }
            return new ParsedCommand(
                    List.of(Arrays.copyOfRange(arguments, 0, separator)),
                    List.of(Arrays.copyOfRange(arguments, separator + 1, arguments.length)));
        }
    }
}
