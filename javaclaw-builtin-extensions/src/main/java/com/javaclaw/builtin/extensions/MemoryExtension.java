package com.javaclaw.builtin.extensions;

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
            "5.0.0",
            1,
            Set.of(ContributionKind.QUERY, ContributionKind.COMMAND, ContributionKind.TOOL, ContributionKind.VIEW),
            new ExtensionRequirements(
                    ExtensionTrust.BUILT_IN,
                    ExtensionAvailability.OPTIONAL,
                    2,
                    BuiltinStoragePermission.create(BuiltinExtensionIds.MEMORY)));

    private ExtensionPayloadCodec payloads;
    private MemoryQueryHandler queries;
    private MemoryCommandHandler commands;

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
        return List.of(
                new ExtensionContributions.Query("memory.query", QUERIES, queries::query),
                new ExtensionContributions.Command("memory.command", COMMANDS, commands::command),
                new ExtensionContributions.Tool(
                        "memory.search.tool",
                        MemoryExtensionPresentation.searchTool(payloads, descriptor.revision()),
                        queries::search),
                new ExtensionContributions.Tool(
                        "memory.propose.tool",
                        MemoryExtensionPresentation.proposeTool(payloads, descriptor.revision()),
                        commands::submitProposal),
                new ExtensionContributions.View("memory.management", MemoryExtensionPresentation.managementView()),
                new ExtensionContributions.View("memory.learning", MemoryExtensionPresentation.learningView()));
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
        commands = null;
        queries = null;
        payloads = null;
    }
}
