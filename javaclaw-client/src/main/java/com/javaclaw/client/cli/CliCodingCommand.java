package com.javaclaw.client.cli;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.builtin.contracts.CodingEnvironmentContracts;
import com.javaclaw.client.CommandOptions;
import com.javaclaw.client.sdk.JavaClawClient;
import com.javaclaw.protocol.CanonicalJson;

/**
 * Coding 管理命令；只管理环境、可信工具链与安装 Job，不执行项目脚本。
 *
 * @param action 已校验的管理动作
 * @param target Workspace UUID 或服务端 Job 标识
 * @param payload 可选的版本化环境或工具链 JSON
 * @param options 写动作的明确 revision 与幂等键，只读为空
 */
record CliCodingCommand(
        String action, String target, Optional<CanonicalPayload> payload, Optional<CommandOptions> options) {
    private static final Set<String> READS = Set.of("catalog", "toolchains", "environment", "job");
    private static final CanonicalJson JSON = new CanonicalJson();

    static CliCodingCommand parse(List<String> arguments) {
        if (arguments.size() < 3) {
            throw usage();
        }
        String action = arguments.get(1);
        String target = arguments.get(2);
        if (!action.equals("job") && !action.equals("cancel-job")) {
            WorkspaceId.parse(target);
        }
        if (READS.contains(action) && arguments.size() == 3) {
            return new CliCodingCommand(action, target, Optional.empty(), Optional.empty());
        }
        if (action.equals("cancel-job") && arguments.size() == 5) {
            return new CliCodingCommand(action, target, Optional.empty(), Optional.of(options(arguments, 3)));
        }
        if (!Set.of("save", "install").contains(action) || arguments.size() != 6) {
            throw usage();
        }
        CanonicalPayload value = new CanonicalPayload(arguments.get(3));
        try {
            if (action.equals("save")) {
                JSON.decode(value, CodingEnvironmentContracts.EnvironmentSpec.class);
            } else {
                JSON.decode(value, CodingEnvironmentContracts.ToolchainRef.class);
            }
        } catch (com.javaclaw.protocol.ProtocolException failure) {
            throw new IllegalArgumentException("Coding JSON 不符合公开契约", failure);
        }
        return new CliCodingCommand(action, target, Optional.of(value), Optional.of(options(arguments, 4)));
    }

    Object execute(JavaClawClient client) {
        if (action.equals("job")) {
            return client.extensionJobs().read(target);
        }
        if (action.equals("cancel-job")) {
            return client.extensionJobs().cancel(target, options.orElseThrow());
        }
        WorkspaceId workspace = WorkspaceId.parse(target);
        var coding = client.builtins().coding();
        return switch (action) {
            case "catalog" -> coding.catalog(workspace);
            case "toolchains" -> coding.toolchains(workspace);
            case "environment" -> coding.environment(workspace);
            case "save" ->
                coding.updateEnvironment(
                        workspace,
                        JSON.decode(payload.orElseThrow(), CodingEnvironmentContracts.EnvironmentSpec.class),
                        options.orElseThrow());
            case "install" ->
                coding.installToolchain(
                        workspace,
                        JSON.decode(payload.orElseThrow(), CodingEnvironmentContracts.ToolchainRef.class),
                        options.orElseThrow());
            default -> throw usage();
        };
    }

    private static CommandOptions options(List<String> arguments, int index) {
        return new CommandOptions(arguments.get(index + 1), Long.parseLong(arguments.get(index)));
    }

    private static IllegalArgumentException usage() {
        return new IllegalArgumentException("usage: coding <catalog|toolchains|environment> <workspace>; "
                + "coding <save|install> <workspace> <JSON> <revision> <idempotency-key>; "
                + "coding job <job-id>; coding cancel-job <job-id> <revision> <idempotency-key>");
    }
}
