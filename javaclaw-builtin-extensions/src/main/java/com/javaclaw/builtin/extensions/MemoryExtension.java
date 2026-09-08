package com.javaclaw.builtin.extensions;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import com.javaclaw.builtin.contracts.BuiltinExtensionIds;
import com.javaclaw.extension.spi.ContributionKind;
import com.javaclaw.extension.spi.ExtensionAvailability;
import com.javaclaw.extension.spi.ExtensionBundle;
import com.javaclaw.extension.spi.ExtensionContext;
import com.javaclaw.extension.spi.ExtensionContribution;
import com.javaclaw.extension.spi.ExtensionContributions;
import com.javaclaw.extension.spi.ExtensionDescriptor;
import com.javaclaw.extension.spi.ExtensionPayloadCodec;
import com.javaclaw.extension.spi.ExtensionRequirements;
import com.javaclaw.extension.spi.ExtensionSchema;
import com.javaclaw.extension.spi.ExtensionTrust;

/** Memory Bundle 的薄组合入口；查询、命令和托管存取由独立协作者承担。 */
final class MemoryExtension implements ExtensionBundle {
    private static final Set<String> QUERIES = Set.of(
            "read",
            "list",
            "history",
            "search",
            "search/v2",
            "graph/read",
            "proposal/read",
            "proposal/list",
            "settings/read",
            "stats",
            "view.new-memory",
            "view.memories",
            "view.memory",
            "view.history",
            "view.stats",
            "view.tombstones",
            "view.proposals",
            "view.settings");
    private static final Set<String> COMMANDS = Set.of(
            "effectivity/update",
            "conflict/resolve",
            "create",
            "management/create",
            "update",
            "update/content",
            "pin",
            "pin/set",
            "pin/clear",
            "tombstone",
            "restore",
            "settings/update",
            "proposal/submit",
            "proposal/accept",
            "proposal/reject");

    private final ExtensionDescriptor descriptor = new ExtensionDescriptor(
            MemoryStoreAccess.ID,
            "记忆",
            "6.0.0",
            2,
            Set.of(
                    ContributionKind.QUERY,
                    ContributionKind.COMMAND,
                    ContributionKind.TOOL,
                    ContributionKind.VIEW,
                    ContributionKind.ORCHESTRATOR,
                    ContributionKind.SCHEDULABLE_ACTION),
            new ExtensionRequirements(
                    ExtensionTrust.BUILT_IN,
                    ExtensionAvailability.OPTIONAL,
                    2,
                    BuiltinStoragePermission.create(BuiltinExtensionIds.MEMORY)));

    private ExtensionPayloadCodec payloads;
    private MemoryQueryHandler queries;
    private MemoryCommandHandler commands;
    private MemoryLearningResource learning;

    @Override
    public ExtensionDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public synchronized List<ExtensionContribution> start(ExtensionContext context) {
        if (payloads != null) {
            throw new IllegalStateException("extension is already started");
        }
        payloads = Objects.requireNonNull(context, "context").payloads();
        MemoryStoreAccess store = new MemoryStoreAccess(payloads);
        queries = new MemoryQueryHandler(payloads, store);
        commands = new MemoryCommandHandler(payloads, store);
        learning = new MemoryLearningResource(payloads, store);
        List<ExtensionContribution> contributions = new ArrayList<>(List.of(
                new ExtensionContributions.Query("memory.query", QUERIES, queries::query),
                new ExtensionContributions.Command("memory.command", COMMANDS, commands::command),
                new ExtensionContributions.Tool(
                        "memory.search.tool",
                        MemoryExtensionPresentation.searchTool(payloads, descriptor.revision()),
                        queries::searchV2),
                new ExtensionContributions.Tool(
                        "memory.propose.tool",
                        MemoryExtensionPresentation.proposeTool(payloads, descriptor.revision()),
                        commands::submitProposal),
                new ExtensionContributions.View("memory.management", MemoryExtensionPresentation.managementView()),
                new ExtensionContributions.View("memory.learning", MemoryExtensionPresentation.learningView())));
        contributions.addAll(learning.contributions());
        contributions.addAll(new MemoryV3Management(payloads, store, commands, learning).contributions());
        contributions.addAll(new MemoryGraphResource(payloads, store).contributions());
        contributions.addAll(new MemoryGraphProjection(payloads, store).contributions());
        return List.copyOf(contributions);
    }

    @Override
    public List<com.javaclaw.extension.spi.ExtensionJobRegistration> jobExecutors(
            com.javaclaw.extension.spi.ExtensionJobRuntimeContext context) {
        return List.of(
                new com.javaclaw.extension.spi.ExtensionJobRegistration(
                        MemoryLearningState.JOB_TYPE, new MemoryLearningJobExecutor(context)),
                new com.javaclaw.extension.spi.ExtensionJobRegistration(
                        MemoryLearningState.BINDING_JOB_TYPE, new MemoryBindingJobExecutor(context)));
    }

    @Override
    public void restore(com.javaclaw.extension.spi.ExtensionExecutionContext context) throws Exception {
        new MemoryGraphProjection(payloads, new MemoryStoreAccess(payloads)).restore(context);
        learning.restore(context);
    }

    @Override
    public synchronized List<ExtensionSchema> schemas() {
        return MemoryExtensionPresentation.schemas(codec());
    }

    private ExtensionPayloadCodec codec() {
        if (payloads == null) {
            throw new IllegalStateException("extension is not started");
        }
        return payloads;
    }

    @Override
    public synchronized void close() {
        learning = null;
        commands = null;
        queries = null;
        payloads = null;
    }
}
