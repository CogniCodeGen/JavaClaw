package com.javaclaw.builtin.extensions;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.builtin.contracts.BuiltinExtensionIds;
import com.javaclaw.builtin.contracts.DocumentContracts;
import com.javaclaw.builtin.contracts.SkillContracts;
import com.javaclaw.extension.spi.ContributionKind;
import com.javaclaw.extension.spi.DocumentRevision;
import com.javaclaw.extension.spi.ExtensionAvailability;
import com.javaclaw.extension.spi.ExtensionBundle;
import com.javaclaw.extension.spi.ExtensionContext;
import com.javaclaw.extension.spi.ExtensionContribution;
import com.javaclaw.extension.spi.ExtensionContributions;
import com.javaclaw.extension.spi.ExtensionDescriptor;
import com.javaclaw.extension.spi.ExtensionExecutionContext;
import com.javaclaw.extension.spi.ExtensionId;
import com.javaclaw.extension.spi.ExtensionPayloadCodec;
import com.javaclaw.extension.spi.ExtensionRequest;
import com.javaclaw.extension.spi.ExtensionRequirements;
import com.javaclaw.extension.spi.ExtensionResponse;
import com.javaclaw.extension.spi.ExtensionSchema;
import com.javaclaw.extension.spi.ExtensionTransaction;
import com.javaclaw.extension.spi.ExtensionTrust;
import com.javaclaw.extension.spi.VersionedDocument;

/** Skill 领域实现；Proposal、Draft、Published 和 Turn 冻结目录保持严格分离。 */
final class SkillExtension implements ExtensionBundle {
    private static final ExtensionId ID = new ExtensionId(BuiltinExtensionIds.SKILL);
    private static final String DRAFTS = "drafts.";
    private static final String PUBLISHED = "published.";
    private static final String PROPOSALS = "proposals.";
    private static final String CATALOGS = "catalogs.";
    private static final Set<String> QUERIES = Set.of(
            "draft/read",
            "draft/list",
            "draft/history",
            "published/read",
            "published/list",
            "search",
            "proposal/read",
            "proposal/list",
            "resource/execution/availability",
            "view.new-draft",
            "view.drafts",
            "view.draft",
            "view.resources",
            "view.history",
            "view.tombstones",
            "view.published",
            "view.proposals");
    private static final Set<String> COMMANDS = Set.of(
            "draft/save-content",
            "draft/resource/add",
            "draft/resource/remove",
            "draft/tombstone",
            "draft/restore",
            "publish",
            "enable",
            "enable/set",
            "enable/clear",
            "proposal/submit",
            "proposal/adopt",
            "proposal/reject",
            "skill/import",
            "skill/export");

    private final ExtensionDescriptor descriptor = new ExtensionDescriptor(
            ID,
            "技能",
            "5.0.0",
            1,
            Set.of(
                    ContributionKind.QUERY,
                    ContributionKind.COMMAND,
                    ContributionKind.TOOL,
                    ContributionKind.VIEW,
                    ContributionKind.SKILL),
            new ExtensionRequirements(
                    ExtensionTrust.BUILT_IN,
                    ExtensionAvailability.OPTIONAL,
                    2,
                    BuiltinStoragePermission.create(ID.value())));
    private ExtensionPayloadCodec payloads;
    private SkillCatalogCoordinator catalogs;
    private SkillManagementCommands managementCommands;
    private SkillManagementViews managementViews;
    private SkillResourceExecution resourceExecution;
    private SkillTransferService transfers;

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
        catalogs = new SkillCatalogCoordinator(payloads);
        managementCommands = new SkillManagementCommands(payloads);
        managementViews = new SkillManagementViews(ID, payloads);
        resourceExecution = new SkillResourceExecution(payloads, catalogs);
        transfers = new SkillTransferService(payloads);
        return List.of(
                new ExtensionContributions.Query("skill.query", QUERIES, this::query),
                new ExtensionContributions.Command("skill.command", COMMANDS, this::command),
                new ExtensionContributions.Tool(
                        "skill.search.tool",
                        SkillExtensionPresentation.searchTool(codec(), descriptor.revision()),
                        this::search),
                new ExtensionContributions.Tool(
                        "skill.read.tool",
                        SkillExtensionPresentation.readTool(codec(), descriptor.revision()),
                        this::readPublished),
                new ExtensionContributions.Tool(
                        "skill.resource.execute.tool",
                        SkillExtensionPresentation.executeResourceTool(codec(), descriptor.revision()),
                        this::executeResource),
                new ExtensionContributions.Resource(
                        "skill.boundary",
                        ContributionKind.SKILL,
                        codec().encode(Map.of(
                                "adoption", "explicit",
                                "publication", "draft-published",
                                "resourceExecution", "java-jshell-native-sandbox"))),
                new ExtensionContributions.View("skill.management", SkillExtensionPresentation.managementView()));
    }

    @Override
    public List<ExtensionSchema> schemas() {
        return SkillExtensionPresentation.schemas(codec());
    }

    private ExtensionResponse query(ExtensionRequest request, ExtensionExecutionContext context) throws Exception {
        return switch (request.operation()) {
            case "draft/read" -> readDraft(request, context);
            case "draft/list" -> list(request, context, drafts(request));
            case "draft/history" -> draftHistory(request, context);
            case "published/read" -> readPublished(request, context);
            case "published/list" -> list(request, context, published(request));
            case "search" -> search(request, context);
            case "proposal/read" -> readProposal(request, context);
            case "proposal/list" -> list(request, context, proposals(request));
            case "resource/execution/availability" -> resourceExecutor().availability(request, context);
            default -> viewQuery(request, context);
        };
    }

    private ExtensionResponse viewQuery(ExtensionRequest request, ExtensionExecutionContext context) throws Exception {
        return switch (request.operation()) {
            case "view.new-draft" -> managementViews().newDraft(request);
            case "view.drafts" -> managementViews().drafts(request, context);
            case "view.draft" -> managementViews().draft(request, context);
            case "view.resources" -> managementViews().resources(request, context);
            case "view.history" -> managementViews().history(request, context);
            case "view.tombstones" -> managementViews().tombstones(request, context);
            case "view.published" -> managementViews().published(request, context);
            case "view.proposals" -> managementViews().proposals(request, context);
            default -> throw new IllegalArgumentException("unknown Skill query: " + request.operation());
        };
    }

    private ExtensionResponse command(ExtensionRequest request, ExtensionExecutionContext context) throws Exception {
        return idempotent(request, context, transaction -> {
            if (managementCommands().supports(request.operation())) {
                return managementCommands().execute(request, context, transaction, drafts(request), published(request));
            }
            return domainCommand(request, context, transaction);
        });
    }

    private ExtensionResponse domainCommand(
            ExtensionRequest request, ExtensionExecutionContext context, ExtensionTransaction transaction) {
        return switch (request.operation()) {
            case "draft/tombstone" -> tombstoneDraft(request, transaction);
            case "draft/restore" -> restoreDraft(request, context, transaction);
            case "publish" -> publish(request, context, transaction);
            case "enable" -> enable(request, context, transaction);
            case "proposal/submit" -> submitProposal(request, context, transaction);
            case "proposal/adopt" -> decideProposal(request, context, transaction, true);
            case "proposal/reject" -> decideProposal(request, context, transaction, false);
            case "skill/import" -> transfers().importDraft(request, context, transaction, drafts(request));
            case "skill/export" -> transfers().exportPublished(request, context, transaction, published(request));
            default -> throw new IllegalArgumentException("unknown Skill command: " + request.operation());
        };
    }

    private ExtensionResponse tombstoneDraft(ExtensionRequest request, ExtensionTransaction transaction) {
        SkillContracts.Key key = codec().decode(request.payload(), SkillContracts.Key.class);
        SkillContracts.Draft current = requireDraft(transaction, drafts(request), key.id());
        requireExpected(request, current.revision());
        transaction.delete(drafts(request), key.id(), request.expectedRevision());
        return new ExtensionResponse(
                codec().encode(new DocumentContracts.Deleted(key.id())), Math.addExact(request.expectedRevision(), 1));
    }

    private ExtensionResponse restoreDraft(
            ExtensionRequest request, ExtensionExecutionContext context, ExtensionTransaction transaction) {
        SkillContracts.DraftRestoreRequest input =
                codec().decode(request.payload(), SkillContracts.DraftRestoreRequest.class);
        DocumentRevision source =
                transaction.history(drafts(request), input.id(), input.sourceRevision() - 1, 1).stream()
                        .filter(value -> value.revision() == input.sourceRevision() && !value.tombstone())
                        .findFirst()
                        .orElseThrow(
                                () -> new IllegalArgumentException("restorable Skill Draft revision does not exist"));
        SkillContracts.Draft historical = codec().decode(source.payload(), SkillContracts.Draft.class);
        SkillContracts.Draft restored =
                copyDraft(historical, next(request), context.clock().instant());
        transaction.put(drafts(request), restored.id(), request.expectedRevision(), codec().encode(restored));
        return response(restored);
    }

    private ExtensionResponse publish(
            ExtensionRequest request, ExtensionExecutionContext context, ExtensionTransaction transaction) {
        SkillContracts.PublishRequest input = codec().decode(request.payload(), SkillContracts.PublishRequest.class);
        SkillContracts.Draft draft = requireDraft(transaction, drafts(request), input.id());
        if (draft.revision() != input.draftRevision()) {
            throw new IllegalArgumentException("Skill Draft revision changed before publish");
        }
        Optional<SkillContracts.PublishedSkill> current = transaction
                .get(published(request), input.id())
                .map(document -> decodePublished(document, "Published Skill"));
        requireExpected(
                request, current.map(SkillContracts.PublishedSkill::revision).orElse(0L));
        Instant now = context.clock().instant();
        String digest = publishedDigest(draft);
        SkillContracts.PublishedSkill published = new SkillContracts.PublishedSkill(
                draft.id(),
                next(request),
                draft.revision(),
                digest,
                draft.name(),
                draft.description(),
                draft.instructions(),
                draft.resources(),
                false,
                current.map(SkillContracts.PublishedSkill::publishedAt).orElse(now),
                now);
        transaction.put(published(request), published.id(), request.expectedRevision(), codec().encode(published));
        return response(published);
    }

    private ExtensionResponse enable(
            ExtensionRequest request, ExtensionExecutionContext context, ExtensionTransaction transaction) {
        SkillContracts.EnableRequest input = codec().decode(request.payload(), SkillContracts.EnableRequest.class);
        return managementCommands()
                .setEnabled(request, context, transaction, published(request), input.id(), input.enabled());
    }

    private ExtensionResponse submitProposal(
            ExtensionRequest request, ExtensionExecutionContext context, ExtensionTransaction transaction) {
        requireExpected(request, 0);
        SkillContracts.ProposeRequest input = codec().decode(request.payload(), SkillContracts.ProposeRequest.class);
        Instant now = context.clock().instant();
        SkillContracts.Proposal proposal = new SkillContracts.Proposal(
                input.id(), 1, input, SkillContracts.ProposalState.PENDING, Optional.empty(), now, now);
        transaction.put(proposals(request), proposal.id(), 0, codec().encode(proposal));
        return response(proposal);
    }

    private ExtensionResponse decideProposal(
            ExtensionRequest request,
            ExtensionExecutionContext context,
            ExtensionTransaction transaction,
            boolean adopt) {
        SkillContracts.ProposalDecision decision =
                codec().decode(request.payload(), SkillContracts.ProposalDecision.class);
        SkillContracts.Proposal current = requireProposal(transaction, proposals(request), decision.id());
        requireExpected(request, current.revision());
        if (current.state() != SkillContracts.ProposalState.PENDING) {
            throw new IllegalArgumentException("only pending Skill Proposal can be decided");
        }
        Instant now = context.clock().instant();
        Optional<SkillContracts.Draft> draft = adopt
                ? Optional.of(adoptDraft(current.candidate(), now, transaction, drafts(request)))
                : Optional.empty();
        SkillContracts.Proposal updated = new SkillContracts.Proposal(
                current.id(),
                next(request),
                current.candidate(),
                adopt ? SkillContracts.ProposalState.ADOPTED_AS_DRAFT : SkillContracts.ProposalState.REJECTED,
                draft.map(SkillContracts.Draft::id),
                current.createdAt(),
                now);
        transaction.put(proposals(request), updated.id(), request.expectedRevision(), codec().encode(updated));
        return response(updated);
    }

    private ExtensionResponse readDraft(ExtensionRequest request, ExtensionExecutionContext context) throws Exception {
        SkillContracts.Key key = codec().decode(request.payload(), SkillContracts.Key.class);
        SkillContracts.Draft draft = context.managedStore()
                .inTransaction(ID, transaction -> requireDraft(transaction, drafts(request), key.id()));
        return response(draft);
    }

    private ExtensionResponse readPublished(ExtensionRequest request, ExtensionExecutionContext context)
            throws Exception {
        if (request.turnId().isEmpty()) {
            SkillContracts.Key key = codec().decode(request.payload(), SkillContracts.Key.class);
            SkillContracts.PublishedSkill published = context.managedStore()
                    .inTransaction(ID, transaction -> requirePublished(transaction, published(request), key.id()));
            return response(published);
        }
        SkillContracts.PublishedReadRequest input =
                codec().decode(request.payload(), SkillContracts.PublishedReadRequest.class);
        SkillContracts.PublishedSkill published = context.managedStore()
                .inTransaction(
                        ID,
                        transaction -> catalogCoordinator()
                                .requireFrozenPublished(
                                        request, transaction, input, published(request), catalogs(request)));
        return response(published);
    }

    private ExtensionResponse search(ExtensionRequest request, ExtensionExecutionContext context) throws Exception {
        SkillContracts.SearchRequest input = codec().decode(request.payload(), SkillContracts.SearchRequest.class);
        SkillContracts.CatalogSnapshot catalog = context.managedStore()
                .inTransaction(
                        ID,
                        transaction -> catalogCoordinator()
                                .catalog(request, context, transaction, published(request), catalogs(request)));
        List<SkillContracts.Summary> matches = catalog.skills().stream()
                .filter(skill -> ExtensionSearch.contains(input.query(), skill.name(), skill.description()))
                .limit(input.limit())
                .toList();
        String digest = request.turnId().isPresent() ? catalog.digest() : "";
        return new ExtensionResponse(codec().encode(new SkillContracts.SearchResult(matches, digest)), 0);
    }

    private ExtensionResponse readProposal(ExtensionRequest request, ExtensionExecutionContext context)
            throws Exception {
        SkillContracts.Key key = codec().decode(request.payload(), SkillContracts.Key.class);
        SkillContracts.Proposal proposal = context.managedStore()
                .inTransaction(ID, transaction -> requireProposal(transaction, proposals(request), key.id()));
        return response(proposal);
    }

    private ExtensionResponse draftHistory(ExtensionRequest request, ExtensionExecutionContext context)
            throws Exception {
        SkillContracts.HistoryRequest input = codec().decode(request.payload(), SkillContracts.HistoryRequest.class);
        List<DocumentRevision> fetched = context.managedStore()
                .inTransaction(
                        ID,
                        transaction -> transaction.history(
                                drafts(request), input.id(), input.afterRevision(), input.limit() + 1));
        boolean more = fetched.size() > input.limit();
        List<DocumentRevision> page = more ? fetched.subList(0, input.limit()) : fetched;
        List<SkillContracts.DraftHistoryEntry> entries =
                page.stream().map(this::draftHistoryEntry).toList();
        return new ExtensionResponse(codec().encode(new SkillContracts.DraftHistoryPage(entries, more)), 0);
    }

    private ExtensionResponse list(ExtensionRequest request, ExtensionExecutionContext context, String collection)
            throws Exception {
        DocumentContracts.PageRequest page = codec().decode(request.payload(), DocumentContracts.PageRequest.class);
        List<VersionedDocument> values = context.managedStore()
                .inTransaction(ID, transaction -> transaction.list(collection, page.afterKey(), page.limit()));
        DocumentContracts.Page result = new DocumentContracts.Page(
                values.stream().map(VersionedDocument::payload).toList(),
                values.isEmpty() ? page.afterKey() : values.getLast().key());
        return new ExtensionResponse(codec().encode(result), 0);
    }

    private ExtensionResponse executeResource(ExtensionRequest request, ExtensionExecutionContext context)
            throws Exception {
        return resourceExecutor().execute(request, context, published(request), catalogs(request));
    }

    private SkillContracts.Draft adoptDraft(
            SkillContracts.ProposeRequest input, Instant now, ExtensionTransaction transaction, String collection) {
        SkillContracts.Draft draft = new SkillContracts.Draft(
                input.draftId(), 1, input.name(), input.description(), input.instructions(), List.of(), now, now);
        transaction.put(collection, draft.id(), 0, codec().encode(draft));
        return draft;
    }

    private String publishedDigest(SkillContracts.Draft draft) {
        return codec().encode(new PublishedContent(
                        draft.name(), draft.description(), draft.instructions(), draft.resources()))
                .sha256();
    }

    private ExtensionResponse idempotent(
            ExtensionRequest request,
            ExtensionExecutionContext context,
            com.javaclaw.extension.spi.ManagedExtensionStore.TransactionWork<ExtensionResponse> work)
            throws Exception {
        String key = request.idempotencyKey()
                .orElseThrow(() -> new IllegalArgumentException("Skill command requires idempotency key"));
        CanonicalPayload digest = codec().encode(new CommandDigest(
                request.workspaceId().toString(), request.operation(), request.expectedRevision(), request.payload()));
        return context.managedStore().inCommand(ID, request.operation(), key, digest.sha256(), work);
    }

    private SkillContracts.Draft requireDraft(ExtensionTransaction transaction, String collection, String id) {
        return transaction
                .get(collection, id)
                .map(document -> decodeDraft(document, "Skill Draft"))
                .orElseThrow(() -> new IllegalArgumentException("Skill Draft does not exist"));
    }

    private SkillContracts.PublishedSkill requirePublished(
            ExtensionTransaction transaction, String collection, String id) {
        return transaction
                .get(collection, id)
                .map(document -> decodePublished(document, "Published Skill"))
                .orElseThrow(() -> new IllegalArgumentException("Published Skill does not exist"));
    }

    private SkillContracts.Proposal requireProposal(ExtensionTransaction transaction, String collection, String id) {
        VersionedDocument document = transaction
                .get(collection, id)
                .orElseThrow(() -> new IllegalArgumentException("Skill Proposal does not exist"));
        SkillContracts.Proposal proposal = codec().decode(document.payload(), SkillContracts.Proposal.class);
        requirePayloadRevision(proposal.revision(), document.revision(), "Skill Proposal");
        return proposal;
    }

    private SkillContracts.Draft decodeDraft(VersionedDocument document, String label) {
        SkillContracts.Draft draft = codec().decode(document.payload(), SkillContracts.Draft.class);
        requirePayloadRevision(draft.revision(), document.revision(), label);
        return draft;
    }

    private SkillContracts.PublishedSkill decodePublished(VersionedDocument document, String label) {
        SkillContracts.PublishedSkill published =
                codec().decode(document.payload(), SkillContracts.PublishedSkill.class);
        requirePayloadRevision(published.revision(), document.revision(), label);
        return published;
    }

    private SkillContracts.DraftHistoryEntry draftHistoryEntry(DocumentRevision revision) {
        SkillContracts.Draft decoded = codec().decode(revision.payload(), SkillContracts.Draft.class);
        SkillContracts.Draft normalized = copyDraft(decoded, revision.revision(), revision.updatedAt());
        return new SkillContracts.DraftHistoryEntry(
                revision.revision(), normalized, revision.tombstone(), revision.updatedAt());
    }

    private static SkillContracts.Draft copyDraft(SkillContracts.Draft source, long revision, Instant updatedAt) {
        return new SkillContracts.Draft(
                source.id(),
                revision,
                source.name(),
                source.description(),
                source.instructions(),
                source.resources(),
                source.createdAt(),
                updatedAt);
    }

    private static void requireExpected(ExtensionRequest request, long actual) {
        if (request.expectedRevision() != actual) {
            throw new IllegalArgumentException("Skill expected revision differs from current revision");
        }
    }

    private static void requirePayloadRevision(long payload, long stored, String label) {
        if (payload != stored) {
            throw new IllegalStateException(label + " payload revision differs from managed store");
        }
    }

    private static long next(ExtensionRequest request) {
        return Math.addExact(request.expectedRevision(), 1);
    }

    private ExtensionResponse response(Object value) {
        long revision = value instanceof com.javaclaw.builtin.contracts.VersionedExtensionDocument document
                ? document.revision()
                : 0;
        return new ExtensionResponse(codec().encode(value), revision);
    }

    private synchronized ExtensionPayloadCodec codec() {
        if (payloads == null) {
            throw new IllegalStateException("extension is not started");
        }
        return payloads;
    }

    private synchronized SkillCatalogCoordinator catalogCoordinator() {
        if (catalogs == null) {
            throw new IllegalStateException("extension is not started");
        }
        return catalogs;
    }

    private synchronized SkillManagementCommands managementCommands() {
        if (managementCommands == null) {
            throw new IllegalStateException("extension is not started");
        }
        return managementCommands;
    }

    private synchronized SkillManagementViews managementViews() {
        if (managementViews == null) {
            throw new IllegalStateException("extension is not started");
        }
        return managementViews;
    }

    private synchronized SkillResourceExecution resourceExecutor() {
        if (resourceExecution == null) {
            throw new IllegalStateException("extension is not started");
        }
        return resourceExecution;
    }

    private synchronized SkillTransferService transfers() {
        if (transfers == null) {
            throw new IllegalStateException("extension is not started");
        }
        return transfers;
    }

    @Override
    public synchronized void close() {
        transfers = null;
        resourceExecution = null;
        managementViews = null;
        managementCommands = null;
        catalogs = null;
        payloads = null;
    }

    private static String drafts(ExtensionRequest request) {
        return DRAFTS + request.workspaceId();
    }

    private static String published(ExtensionRequest request) {
        return PUBLISHED + request.workspaceId();
    }

    private static String proposals(ExtensionRequest request) {
        return PROPOSALS + request.workspaceId();
    }

    private static String catalogs(ExtensionRequest request) {
        return CATALOGS + request.workspaceId();
    }

    private record PublishedContent(
            String name, String description, String instructions, List<SkillContracts.Resource> resources) {}

    private record CommandDigest(
            String workspaceId, String operation, long expectedRevision, CanonicalPayload payload) {}
}
