package com.javaclaw.client.cli;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import com.javaclaw.client.sdk.JavaClawClient;
import com.javaclaw.client.transport.StdioProcessTransport;
import com.javaclaw.protocol.ClientInfo;

/** 最小 Protocol v2 CLI；复杂交互通过 Java SDK facade 复用同一契约。 */
public final class JavaClawCli {
    private JavaClawCli() {}

    /**
     * 运行 CLI。
     *
     * <p>格式为 {@code workspace-list -- <server executable> [server args...]}。服务端命令作为参数数组直接执行，不经过 shell。
     *
     * @param arguments CLI 参数
     * @throws Exception 建立连接或 RPC 失败
     */
    public static void main(String[] arguments) throws Exception {
        ParsedCommand command = ParsedCommand.parse(arguments);
        try (JavaClawClient client = JavaClawClient.connect(
                new StdioProcessTransport(command.serverCommand()),
                new ClientInfo("javaclaw-cli", "5.0.0-SNAPSHOT"),
                Set.of(),
                notification -> {})) {
            client.workspaces().list().forEach(System.out::println);
        }
    }

    /** 已校验的 CLI 命令。 */
    private record ParsedCommand(List<String> serverCommand) {
        private static ParsedCommand parse(String[] arguments) {
            int separator = Arrays.asList(arguments).indexOf("--");
            if (separator != 1 || !"workspace-list".equals(arguments[0]) || separator == arguments.length - 1) {
                throw new IllegalArgumentException(
                        "usage: javaclaw workspace-list -- <server executable> [server args...]");
            }
            return new ParsedCommand(List.of(Arrays.copyOfRange(arguments, separator + 1, arguments.length)));
        }
    }
}
