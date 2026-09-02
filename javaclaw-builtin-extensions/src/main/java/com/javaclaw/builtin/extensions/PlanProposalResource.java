package com.javaclaw.builtin.extensions;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.ToolRisk;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.builtin.contracts.DocumentContracts;
import com.javaclaw.builtin.contracts.PlanContracts;
import com.javaclaw.extension.spi.ExpectedRevisionBinding;
import com.javaclaw.extension.spi.ExtensionContribution;
import com.javaclaw.extension.spi.ExtensionContributions;
import com.javaclaw.extension.spi.ExtensionExecutionContext;
import com.javaclaw.extension.spi.ExtensionRequest;
import com.javaclaw.extension.spi.ExtensionResponse;
import com.javaclaw.extension.spi.ExtensionSchema;
import com.javaclaw.extension.spi.ExtensionTransaction;
import com.javaclaw.extension.spi.VersionedDocument;
import com.javaclaw.extension.spi.ViewAction;
import com.javaclaw.extension.spi.ViewDataSource;
import com.javaclaw.extension.spi.ViewQueryRequest;
import com.javaclaw.extension.spi.ViewQueryResult;
import com.javaclaw.extension.spi.ViewSchema;
import com.javaclaw.extension.spi.ViewSelectionMode;

/** Plan 模型 Proposal 与人工采纳之间的独立、可审计事务边界。 */
final class PlanProposalResource {
    private static final String READ = "proposal/read";
    private static final String LIST = "proposal/list";
    private static final String VIEW_LIST = "proposal/view.list";
    private static final String SUBMIT = "proposal/submit";
    private static final String ADOPT = "proposal/adopt";
    private static final String REJECT = "proposal/reject";
    private static final String VIEW_SOURCE = "proposals";
    private static final String COLLECTION_PREFIX = "proposals.";

    private final ManagedDocumentResource<PlanContracts.Definition> documents;

    PlanProposalResource(ManagedDocumentResource<PlanContracts.Definition> documents) {
        this.documents = Objects.requireNonNull(documents, "documents");
    }

    List<ExtensionContribution> contributions() {
        return List.of(
                new ExtensionContributions.Query("plan.proposal.query", Set.of(READ, LIST, VIEW_LIST), this::query),
                new ExtensionContributions.Command(
                        "plan.proposal.command", Set.of(SUBMIT, ADOPT, REJECT), this::command),
                new ExtensionContributions.Tool(
                        "plan.proposal.submit.tool",
                        documents.governedTool(
                                "proposal_submit",
                                "提交等待人工审阅的 Plan Proposal；不会直接创建或修改 Definition",
                                proposalInputSchema(),
                                ToolRisk.WORKSPACE_WRITE,
                                Set.of("proposal", "人工审阅")),
                        this::submitTool),
                new ExtensionContributions.View("plan.proposal.view", view()));
    }

    List<ExtensionSchema> schemas() {
        String id = documents.extensionId().value() + "/proposal/v5";
        CanonicalPayload schema = ContractSchemaFactory.document(
                documents.payloads(), id, "Plan Proposal", proposalProperties(), proposalRequired());
        return List.of(new ExtensionSchema(id, schema));
    }

    private ExtensionResponse query(ExtensionRequest request, ExtensionExecutionContext context) throws Exception {
        return switch (request.operation()) {
            case READ -> read(request, context);
            case LIST -> list(request, context);
            case VIEW_LIST -> viewList(request, context);
            default -> throw new IllegalArgumentException("unknown Plan Proposal query");
        };
    }

    private ExtensionResponse command(ExtensionRequest request, ExtensionExecutionContext context) throws Exception {
        String key = request.idempotencyKey()
                .orElseThrow(() -> new IllegalArgumentException("Plan Proposal command requires idempotency key"));
        CanonicalPayload digest = documents
                .payloads()
                .encode(new CommandDigest(
                        request.workspaceId(), request.operation(), request.expectedRevision(), request.payload()));
        return context.managedStore()
                .inCommand(
                        documents.extensionId(),
                        request.operation(),
                        key,
                        digest.sha256(),
                        transaction -> mutate(request, context, transaction));
    }

    private ExtensionResponse submitTool(ExtensionRequest request, ExtensionExecutionContext context) throws Exception {
        ExtensionRequest command = new ExtensionRequest(
                request.workspaceId(),
                request.threadId(),
                request.turnId(),
                SUBMIT,
                request.payload(),
                request.idempotencyKey(),
                request.expectedRevision(),
                request.unattendedExecutionScope());
        return command(command, context);
    }

    private ExtensionResponse mutate(
            ExtensionRequest request, ExtensionExecutionContext context, ExtensionTransaction transaction) {
        return switch (request.operation()) {
            case SUBMIT -> submit(request, context, transaction);
            case ADOPT -> decide(request, context, transaction, true);
            case REJECT -> decide(request, context, transaction, false);
            default -> throw new IllegalArgumentException("unknown Plan Proposal command");
        };
    }

    private ExtensionResponse submit(
            ExtensionRequest request, ExtensionExecutionContext context, ExtensionTransaction transaction) {
        requireExpected(request, 0);
        PlanContracts.ProposeRequest input =
                documents.payloads().decode(request.payload(), PlanContracts.ProposeRequest.class);
        PlanContracts.Candidate candidate = PlanManagement.candidate(input.candidate());
        requireTargetRevision(transaction, request.workspaceId(), candidate.id(), input.baseDefinitionRevision());
        Instant now = context.clock().instant();
        String contentHash = documents.payloads().encode(candidate).sha256();
        PlanContracts.Proposal proposal = new PlanContracts.Proposal(
                input.id(),
                1,
                candidate,
                input.baseDefinitionRevision(),
                input.sourceItemId(),
                contentHash,
                PlanContracts.ProposalState.PENDING,
                Optional.empty(),
                now,
                now);
        transaction.put(
                collection(request.workspaceId()),
                proposal.id(),
                0,
                documents.payloads().encode(proposal));
        appendEvent(transaction, proposal);
        return response(proposal);
    }

    private ExtensionResponse decide(
            ExtensionRequest request,
            ExtensionExecutionContext context,
            ExtensionTransaction transaction,
            boolean adopt) {
        PlanContracts.ProposalDecision input =
                documents.payloads().decode(request.payload(), PlanContracts.ProposalDecision.class);
        PlanContracts.Proposal current = requireProposal(transaction, request.workspaceId(), input.id());
        requireExpected(request, current.revision());
        if (current.state() != PlanContracts.ProposalState.PENDING) {
            throw new IllegalArgumentException("only pending Plan Proposal can be decided");
        }
        Optional<Long> adoptedRevision = adopt
                ? Optional.of(adoptDefinition(
                        transaction,
                        request.workspaceId(),
                        current,
                        context.clock().instant()))
                : Optional.empty();
        PlanContracts.Proposal updated = new PlanContracts.Proposal(
                current.id(),
                Math.addExact(current.revision(), 1),
                current.candidate(),
                current.baseDefinitionRevision(),
                current.sourceItemId(),
                current.contentHash(),
                adopt ? PlanContracts.ProposalState.ADOPTED : PlanContracts.ProposalState.REJECTED,
                adoptedRevision,
                current.createdAt(),
                context.clock().instant());
        transaction.put(
                collection(request.workspaceId()),
                updated.id(),
                request.expectedRevision(),
                documents.payloads().encode(updated));
        appendEvent(transaction, updated);
        return response(updated);
    }

    private long adoptDefinition(
            ExtensionTransaction transaction,
            WorkspaceId workspaceId,
            PlanContracts.Proposal proposal,
            Instant updatedAt) {
        requireTargetRevision(transaction, workspaceId, proposal.candidate().id(), proposal.baseDefinitionRevision());
        long expected = proposal.baseDefinitionRevision().orElse(0L);
        long revision = Math.addExact(expected, 1);
        PlanContracts.Definition definition = PlanManagement.definition(proposal.candidate(), revision, updatedAt);
        transaction.put(
                documents.documentCollection(workspaceId),
                definition.id(),
                expected,
                documents.payloads().encode(definition));
        return revision;
    }

    private ExtensionResponse read(ExtensionRequest request, ExtensionExecutionContext context) throws Exception {
        DocumentContracts.Key key = documents.payloads().decode(request.payload(), DocumentContracts.Key.class);
        PlanContracts.Proposal proposal = context.managedStore()
                .inTransaction(
                        documents.extensionId(),
                        transaction -> requireProposal(transaction, request.workspaceId(), key.id()));
        return response(proposal);
    }

    private ExtensionResponse list(ExtensionRequest request, ExtensionExecutionContext context) throws Exception {
        DocumentContracts.PageRequest page =
                documents.payloads().decode(request.payload(), DocumentContracts.PageRequest.class);
        List<VersionedDocument> proposals = context.managedStore()
                .inTransaction(
                        documents.extensionId(),
                        transaction ->
                                transaction.list(collection(request.workspaceId()), page.afterKey(), page.limit()));
        proposals.forEach(this::decodeProposal);
        DocumentContracts.Page result = new DocumentContracts.Page(
                proposals.stream().map(VersionedDocument::payload).toList(),
                proposals.isEmpty() ? page.afterKey() : proposals.getLast().key());
        return new ExtensionResponse(documents.payloads().encode(result), 0);
    }

    private ExtensionResponse viewList(ExtensionRequest request, ExtensionExecutionContext context) throws Exception {
        ViewQueryRequest query = documents.payloads().decode(request.payload(), ViewQueryRequest.class);
        if (!VIEW_SOURCE.equals(query.dataSourceId()) || !query.arguments().isEmpty()) {
            throw new IllegalArgumentException("Plan Proposal view arguments must be empty");
        }
        List<VersionedDocument> fetched = context.managedStore()
                .inTransaction(
                        documents.extensionId(),
                        transaction -> transaction.list(
                                collection(request.workspaceId()), query.cursor(), Math.addExact(query.limit(), 1)));
        boolean more = fetched.size() > query.limit();
        List<VersionedDocument> page = more ? fetched.subList(0, query.limit()) : fetched;
        ViewQueryResult result = new ViewQueryResult(
                query.dataSourceId(),
                page.stream().map(this::viewRow).toList(),
                documents.payloads().encode(Map.of()),
                more && !page.isEmpty() ? page.getLast().key() : "",
                more,
                page.stream().mapToLong(VersionedDocument::revision).max().orElse(0));
        return new ExtensionResponse(documents.payloads().encode(result), result.revision());
    }

    private PlanContracts.Proposal requireProposal(
            ExtensionTransaction transaction, WorkspaceId workspaceId, String id) {
        return transaction
                .get(collection(workspaceId), id)
                .map(this::decodeProposal)
                .orElseThrow(() -> new IllegalArgumentException("Plan Proposal does not exist"));
    }

    private PlanContracts.Proposal decodeProposal(VersionedDocument document) {
        PlanContracts.Proposal proposal = documents.payloads().decode(document.payload(), PlanContracts.Proposal.class);
        if (proposal.revision() != document.revision()
                || !proposal.contentHash()
                        .equals(documents
                                .payloads()
                                .encode(proposal.candidate())
                                .sha256())) {
            throw new IllegalStateException("Plan Proposal stored identity or content digest differs");
        }
        return proposal;
    }

    private CanonicalPayload viewRow(VersionedDocument document) {
        PlanContracts.Proposal proposal = decodeProposal(document);
        return documents
                .payloads()
                .encode(new ProposalRow(
                        proposal.id(),
                        proposal.revision(),
                        proposal.candidate().id(),
                        proposal.candidate().title(),
                        proposal.state(),
                        proposal.contentHash(),
                        proposal.sourceItemId().orElse(""),
                        proposal.baseDefinitionRevision().orElse(0L),
                        proposal.updatedAt()));
    }

    private ExtensionResponse response(PlanContracts.Proposal proposal) {
        return new ExtensionResponse(documents.payloads().encode(proposal), proposal.revision());
    }

    private void requireTargetRevision(
            ExtensionTransaction transaction,
            WorkspaceId workspaceId,
            String definitionId,
            Optional<Long> expectedRevision) {
        Optional<VersionedDocument> current = transaction.get(documents.documentCollection(workspaceId), definitionId);
        boolean valid = expectedRevision
                .map(expected ->
                        current.filter(value -> value.revision() == expected).isPresent())
                .orElseGet(current::isEmpty);
        if (!valid) {
            throw new IllegalArgumentException("Plan Proposal target Definition revision changed");
        }
    }

    private void appendEvent(ExtensionTransaction transaction, PlanContracts.Proposal proposal) {
        transaction.appendEvent(
                "plan.proposal.changed",
                documents.payloads().encode(new ProposalEvent(proposal.id(), proposal.revision())));
    }

    private ViewSchema view() {
        ViewAction adopt = new ViewAction(
                "采纳为 Definition",
                ADOPT,
                Map.of(),
                Map.of("id", "id"),
                new ExpectedRevisionBinding.RowField("revision"),
                true);
        ViewAction reject = new ViewAction(
                "拒绝", REJECT, Map.of(), Map.of("id", "id"), new ExpectedRevisionBinding.RowField("revision"), true);
        ViewSchema.Table table = new ViewSchema.Table(
                "plan-proposals",
                "模型 Plan Proposal",
                VIEW_SOURCE,
                "id",
                List.of(
                        new ViewSchema.Column("title", "标题", Optional.of(220)),
                        new ViewSchema.Column("definitionId", "Definition", Optional.of(180)),
                        new ViewSchema.Column("state", "状态", Optional.of(120)),
                        new ViewSchema.Column("revision", "版本", Optional.of(80)),
                        new ViewSchema.Column("updatedAt", "更新时间", Optional.of(180))),
                ViewSelectionMode.SINGLE,
                List.of(adopt, reject));
        return new ViewSchema(
                ViewSchema.CURRENT_VERSION,
                documents.extensionId().value() + ".proposals",
                "Plan 提案",
                List.of(new ViewDataSource(VIEW_SOURCE, VIEW_LIST, Map.of(), List.of(), 100)),
                List.of(table));
    }

    private static Map<String, Object> proposalProperties() {
        return Map.of(
                "id", ContractSchemaFactory.string(),
                "revision", ContractSchemaFactory.integer(1),
                "candidate", Map.of("type", "object"),
                "baseDefinitionRevision", nullable(ContractSchemaFactory.integer(1)),
                "sourceItemId", nullable(ContractSchemaFactory.string()),
                "contentHash", ContractSchemaFactory.digest(),
                "state", ContractSchemaFactory.enumStrings("PENDING", "ADOPTED", "REJECTED"),
                "adoptedDefinitionRevision", nullable(ContractSchemaFactory.integer(1)),
                "createdAt", ContractSchemaFactory.instant(),
                "updatedAt", ContractSchemaFactory.instant());
    }

    private CanonicalPayload proposalInputSchema() {
        return documents
                .payloads()
                .encode(ContractSchemaFactory.object(
                        Map.of(
                                "id", ContractSchemaFactory.string(),
                                "baseDefinitionRevision", nullable(ContractSchemaFactory.integer(1)),
                                "sourceItemId", nullable(ContractSchemaFactory.string()),
                                "candidate", candidateInputSchema()),
                        List.of("id", "baseDefinitionRevision", "sourceItemId", "candidate")));
    }

    private static Map<String, Object> candidateInputSchema() {
        return ContractSchemaFactory.object(
                Map.of(
                        "id", ContractSchemaFactory.string(),
                        "title", ContractSchemaFactory.string(),
                        "objective", ContractSchemaFactory.string(),
                        "scope", ContractSchemaFactory.string(),
                        "risks", ContractSchemaFactory.array(managementRiskSchema()),
                        "openQuestions", ContractSchemaFactory.array(managementQuestionSchema()),
                        "steps", ContractSchemaFactory.array(managementStepSchema())),
                List.of("id", "title", "objective", "scope", "risks", "openQuestions", "steps"));
    }

    private static Map<String, Object> managementRiskSchema() {
        return ContractSchemaFactory.object(
                Map.of("itemKey", ContractSchemaFactory.string(), "description", ContractSchemaFactory.string()),
                List.of("itemKey", "description"));
    }

    private static Map<String, Object> managementQuestionSchema() {
        return ContractSchemaFactory.object(
                Map.of(
                        "itemKey", ContractSchemaFactory.string(),
                        "id", ContractSchemaFactory.string(),
                        "prompt", ContractSchemaFactory.string(),
                        "decision", nullable(ContractSchemaFactory.string())),
                List.of("itemKey", "id", "prompt", "decision"));
    }

    private static Map<String, Object> managementStepSchema() {
        return ContractSchemaFactory.object(
                Map.of(
                        "itemKey", ContractSchemaFactory.string(),
                        "id", ContractSchemaFactory.string(),
                        "title", ContractSchemaFactory.string(),
                        "instruction", ContractSchemaFactory.string(),
                        "acceptanceCriteria", ContractSchemaFactory.string(),
                        "dependencies", ContractSchemaFactory.uniqueStrings()),
                List.of("itemKey", "id", "title", "instruction", "acceptanceCriteria", "dependencies"));
    }

    private static List<String> proposalRequired() {
        return List.of(
                "id",
                "revision",
                "candidate",
                "baseDefinitionRevision",
                "sourceItemId",
                "contentHash",
                "state",
                "adoptedDefinitionRevision",
                "createdAt",
                "updatedAt");
    }

    private static Map<String, Object> nullable(Map<String, Object> schema) {
        return Map.of("anyOf", List.of(schema, Map.of("type", "null")));
    }

    private static void requireExpected(ExtensionRequest request, long expected) {
        if (request.expectedRevision() != expected) {
            throw new IllegalArgumentException("Plan Proposal expected revision changed");
        }
    }

    private static String collection(WorkspaceId workspaceId) {
        return COLLECTION_PREFIX + workspaceId;
    }

    private record CommandDigest(
            WorkspaceId workspaceId, String operation, long expectedRevision, CanonicalPayload payload) {}

    private record ProposalEvent(String id, long revision) {}

    private record ProposalRow(
            String id,
            long revision,
            String definitionId,
            String title,
            PlanContracts.ProposalState state,
            String contentHash,
            String sourceItemId,
            long baseDefinitionRevision,
            Instant updatedAt) {}
}
