package com.javaclaw.server.extension;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.ToolDescriptor;
import com.javaclaw.extension.spi.ContributionKind;
import com.javaclaw.extension.spi.ExtensionContribution;
import com.javaclaw.extension.spi.ExtensionContributions;
import com.javaclaw.extension.spi.ExtensionDescriptor;
import com.javaclaw.extension.spi.ExtensionHandler;
import com.javaclaw.extension.spi.ExtensionPayloadCodec;
import com.javaclaw.extension.spi.ViewSchema;

/** 把扩展贡献编译成不可变调用索引，并验证名称、所有权与工具 Schema。 */
record ExtensionContributionIndex(
        Map<String, ExtensionContributions.Tool> tools,
        Map<String, ExtensionHandler> queries,
        Map<String, ExtensionHandler> commands,
        Set<String> schedulableActions,
        List<ExtensionContributions.SchedulableAction> actionCatalog,
        List<ExtensionContributions.SchedulableDefinition> definitionCatalog,
        List<ViewSchema> views,
        Set<ContributionKind> kinds) {
    static ExtensionContributionIndex create(
            ExtensionDescriptor descriptor, List<ExtensionContribution> contributions, ExtensionPayloadCodec payloads) {
        Accumulator index = Accumulator.empty();
        Set<String> contributionIds = new HashSet<>();
        Set<ContributionKind> kinds = new HashSet<>();
        for (ExtensionContribution contribution : contributions) {
            if (!contributionIds.add(contribution.contributionId())) {
                throw new IllegalArgumentException("duplicate contribution ID");
            }
            kinds.add(contribution.kind());
            addContribution(contribution, descriptor, payloads, index);
        }
        if (!index.commands().keySet().containsAll(index.schedulableActions())) {
            throw new IllegalArgumentException("schedulable action must reference an existing command operation");
        }
        return new ExtensionContributionIndex(
                Map.copyOf(index.tools()),
                Map.copyOf(index.queries()),
                Map.copyOf(index.commands()),
                Set.copyOf(index.schedulableActions()),
                List.copyOf(index.actionCatalog()),
                List.copyOf(index.definitionCatalog()),
                List.copyOf(index.views()),
                Set.copyOf(kinds));
    }

    private static void addContribution(
            ExtensionContribution contribution,
            ExtensionDescriptor descriptor,
            ExtensionPayloadCodec payloads,
            Accumulator index) {
        switch (contribution) {
            case ExtensionContributions.Tool tool -> addTool(index.tools(), descriptor, tool, payloads);
            case ExtensionContributions.Query query -> add(index.queries(), query.operations(), query.handler());
            case ExtensionContributions.Command command ->
                add(index.commands(), command.operations(), command.handler());
            case ExtensionContributions.Orchestrator orchestrator ->
                add(
                        index.commands(),
                        orchestrator.operations(),
                        (request, context) -> orchestrator.orchestrator().orchestrate(request, context));
            case ExtensionContributions.View view -> index.views().add(view.view());
            case ExtensionContributions.SchedulableAction action -> {
                if (!index.schedulableActions().add(action.commandOperation())) {
                    throw new IllegalArgumentException("duplicate schedulable action operation");
                }
                if (action.catalogVisible()) {
                    index.actionCatalog().add(action);
                }
            }
            case ExtensionContributions.SchedulableDefinition definition ->
                index.definitionCatalog().add(definition);
            default -> {
                // 专用 Host 消费其余贡献点；类型仍参与描述一致性校验。
            }
        }
    }

    private static void addTool(
            Map<String, ExtensionContributions.Tool> tools,
            ExtensionDescriptor extension,
            ExtensionContributions.Tool tool,
            ExtensionPayloadCodec payloads) {
        ToolDescriptor descriptor = tool.descriptor();
        boolean identityMatches =
                descriptor.identity().producerId().equals(extension.id().value())
                        && descriptor.identity().revision() == extension.revision();
        if (!identityMatches) {
            throw new IllegalArgumentException("tool producer or revision differs from extension descriptor");
        }
        requireObjectSchema(descriptor.inputSchema(), "inputSchema", payloads);
        requireObjectSchema(descriptor.outputSchema(), "outputSchema", payloads);
        if (tools.put(descriptor.identity().name(), tool) != null) {
            throw new IllegalArgumentException(
                    "duplicate extension tool name: " + descriptor.identity().name());
        }
    }

    private static void requireObjectSchema(CanonicalPayload schema, String name, ExtensionPayloadCodec payloads) {
        Object decoded = payloads.decode(schema, Object.class);
        if (!(decoded instanceof Map<?, ?> object) || !"object".equals(object.get("type"))) {
            throw new IllegalArgumentException(name + " must be a valid object JSON Schema");
        }
    }

    private static void add(Map<String, ExtensionHandler> target, Set<String> operations, ExtensionHandler handler) {
        for (String operation : operations) {
            if (target.put(operation, handler) != null) {
                throw new IllegalArgumentException("duplicate extension operation: " + operation);
            }
        }
    }

    private record Accumulator(
            Map<String, ExtensionContributions.Tool> tools,
            Map<String, ExtensionHandler> queries,
            Map<String, ExtensionHandler> commands,
            Set<String> schedulableActions,
            List<ExtensionContributions.SchedulableAction> actionCatalog,
            List<ExtensionContributions.SchedulableDefinition> definitionCatalog,
            List<ViewSchema> views) {
        private static Accumulator empty() {
            return new Accumulator(
                    new LinkedHashMap<>(),
                    new LinkedHashMap<>(),
                    new LinkedHashMap<>(),
                    new HashSet<>(),
                    new ArrayList<>(),
                    new ArrayList<>(),
                    new ArrayList<>());
        }
    }
}
