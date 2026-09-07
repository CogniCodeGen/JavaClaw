package com.javaclaw.builtin.extensions;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import com.javaclaw.api.ToolDescriptor;
import com.javaclaw.api.ToolIdentity;
import com.javaclaw.builtin.contracts.BuiltinExtensionIds;
import com.javaclaw.builtin.contracts.CodingContracts;
import com.javaclaw.builtin.contracts.CodingSchemas;
import com.javaclaw.extension.spi.ContributionKind;
import com.javaclaw.extension.spi.ExtensionAvailability;
import com.javaclaw.extension.spi.ExtensionBundle;
import com.javaclaw.extension.spi.ExtensionContext;
import com.javaclaw.extension.spi.ExtensionContribution;
import com.javaclaw.extension.spi.ExtensionContributions;
import com.javaclaw.extension.spi.ExtensionDescriptor;
import com.javaclaw.extension.spi.ExtensionExecutionContext;
import com.javaclaw.extension.spi.ExtensionId;
import com.javaclaw.extension.spi.ExtensionRequest;
import com.javaclaw.extension.spi.ExtensionRequirements;
import com.javaclaw.extension.spi.ExtensionResponse;
import com.javaclaw.extension.spi.ExtensionSchema;
import com.javaclaw.extension.spi.ExtensionTrust;

/** Coding 的静态贡献；实际 IO 只通过 Host 为当前调用绑定的单次平台端口执行。 */
final class CodingExtension implements ExtensionBundle {
    private static final Set<String> QUERIES = Set.of(
            "toolchain/catalog",
            "toolchain/list",
            "environment/read",
            "execution/list",
            "command/output",
            "preparation/read",
            "preparation/output",
            "preparation/evidence",
            "change/list",
            "diff/read",
            "terminal/read",
            "terminal/output");
    private static final Set<String> COMMANDS = Set.of("environment/update", "toolchain/install");
    private final ExtensionDescriptor descriptor = new ExtensionDescriptor(
            new ExtensionId(BuiltinExtensionIds.CODING),
            "Coding",
            "6.0.0",
            CodingContracts.REVISION,
            Set.of(ContributionKind.QUERY, ContributionKind.COMMAND, ContributionKind.TOOL),
            new ExtensionRequirements(
                    ExtensionTrust.BUILT_IN,
                    ExtensionAvailability.OPTIONAL,
                    2,
                    BuiltinStoragePermission.create(BuiltinExtensionIds.CODING)));

    @Override
    public ExtensionDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public List<ExtensionContribution> start(ExtensionContext context) {
        Objects.requireNonNull(context, "context");
        List<ExtensionContribution> result = new ArrayList<>();
        result.add(new ExtensionContributions.Query("coding.query", QUERIES, this::invoke));
        result.add(new ExtensionContributions.Command("coding.command", COMMANDS, this::invoke));
        for (CodingToolDefinitions.Definition definition : CodingToolDefinitions.all()) {
            ToolDescriptor tool = new ToolDescriptor(
                    new ToolIdentity(BuiltinExtensionIds.CODING, definition.name(), CodingContracts.REVISION),
                    definition.description(),
                    CodingSchemas.read(definition.inputSchema()),
                    CodingSchemas.toolOutput(definition.outputSchema()),
                    definition.risk(),
                    Set.of("coding", definition.name(), "编程", "workspace"));
            result.add(new ExtensionContributions.Tool(definition.name(), tool, this::invoke));
        }
        return List.copyOf(result);
    }

    @Override
    public List<ExtensionSchema> schemas() {
        return CodingSchemas.names().stream()
                .map(name -> new ExtensionSchema(CodingSchemas.id(name), CodingSchemas.read(name)))
                .toList();
    }

    @Override
    public void close() {
        // 平台端口与进程由当前 Turn 的 Host 作用域拥有；Bundle 本身不持有 IO 资源。
    }

    private ExtensionResponse invoke(ExtensionRequest request, ExtensionExecutionContext context) throws Exception {
        Objects.requireNonNull(request, "request");
        context.cancellation().throwIfCancelled();
        // 不转传请求参数；Host 绑定的端口已捕获权威操作、参数、调用类型和授权，防止替换审批内容。
        return context.workspaceExecution().invoke();
    }
}
