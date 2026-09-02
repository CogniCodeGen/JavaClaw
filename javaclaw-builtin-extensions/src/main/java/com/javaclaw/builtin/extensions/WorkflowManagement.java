package com.javaclaw.builtin.extensions;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.builtin.contracts.WorkflowContracts;
import com.javaclaw.builtin.contracts.WorkflowManagementContracts;
import com.javaclaw.extension.spi.ExtensionContribution;
import com.javaclaw.extension.spi.ExtensionContributions;
import com.javaclaw.extension.spi.ExtensionExecutionContext;
import com.javaclaw.extension.spi.ExtensionRequest;
import com.javaclaw.extension.spi.ExtensionResponse;
import com.javaclaw.extension.spi.ExtensionTransaction;
import com.javaclaw.extension.spi.VersionedDocument;
import com.javaclaw.extension.spi.ViewQueryRequest;
import com.javaclaw.extension.spi.ViewQueryResult;

/** Workflow Definition 的幂等管理写入与权威详情查询。 */
final class WorkflowManagement {
    static final String SAVE = "definition/save";
    static final String VIEW_NEW = "definition/view.new";
    static final String VIEW_SELECTED = "definition/view.selected";

    private final ManagedDocumentResource<WorkflowContracts.Definition> documents;

    WorkflowManagement(ManagedDocumentResource<WorkflowContracts.Definition> documents) {
        this.documents = java.util.Objects.requireNonNull(documents, "documents");
    }

    List<ExtensionContribution> contributions() {
        return List.of(
                new ExtensionContributions.Query(
                        "definition.management.query", Set.of(VIEW_NEW, VIEW_SELECTED), this::query),
                new ExtensionContributions.Command("definition.management.command", Set.of(SAVE), this::save),
                new ExtensionContributions.View(
                        "definition.management.view",
                        new WorkflowManagementView(documents.extensionId().value()).schema()));
    }

    private ExtensionResponse query(ExtensionRequest request, ExtensionExecutionContext context) throws Exception {
        return switch (request.operation()) {
            case VIEW_NEW -> newEditor(request);
            case VIEW_SELECTED -> selectedEditor(request, context);
            default -> throw new IllegalArgumentException("unknown Workflow management query");
        };
    }

    private ExtensionResponse newEditor(ExtensionRequest request) {
        ViewQueryRequest query = documents.payloads().decode(request.payload(), ViewQueryRequest.class);
        if (!"newDefinition".equals(query.dataSourceId())
                || !query.arguments().isEmpty()
                || !query.cursor().isEmpty()) {
            throw new IllegalArgumentException("Workflow new Definition view does not accept arguments");
        }
        ViewQueryResult result = new ViewQueryResult(
                query.dataSourceId(), List.of(), documents.payloads().encode(Map.of()), "", false, 0);
        return new ExtensionResponse(documents.payloads().encode(result), 0);
    }

    private ExtensionResponse selectedEditor(ExtensionRequest request, ExtensionExecutionContext context)
            throws Exception {
        ViewQueryRequest query = documents.payloads().decode(request.payload(), ViewQueryRequest.class);
        if (!"definitionEditor".equals(query.dataSourceId())
                || !query.arguments().keySet().equals(Set.of("id", "revision"))
                || !query.cursor().isEmpty()) {
            throw new IllegalArgumentException("Workflow editor requires one exact selected Definition");
        }
        WorkflowContracts.Definition definition = documents.requireDocument(
                query.arguments().get("id"), parseRevision(query.arguments().get("revision")), context);
        WorkflowManagementContracts.SaveRequest values = mapper().management(definition);
        ViewQueryResult result = new ViewQueryResult(
                query.dataSourceId(), List.of(), documents.payloads().encode(values), "", false, definition.revision());
        return new ExtensionResponse(documents.payloads().encode(result), definition.revision());
    }

    private ExtensionResponse save(ExtensionRequest request, ExtensionExecutionContext context) throws Exception {
        String key = request.idempotencyKey()
                .orElseThrow(() -> new IllegalArgumentException("Workflow Definition save requires idempotency key"));
        CanonicalPayload digest = documents
                .payloads()
                .encode(new CommandDigest(request.operation(), request.expectedRevision(), request.payload()));
        return context.managedStore()
                .inCommand(
                        documents.extensionId(),
                        request.operation(),
                        key,
                        digest.sha256(),
                        transaction -> save(request, context, transaction));
    }

    private ExtensionResponse save(
            ExtensionRequest request, ExtensionExecutionContext context, ExtensionTransaction transaction) {
        WorkflowManagementContracts.SaveRequest input =
                documents.payloads().decode(request.payload(), WorkflowManagementContracts.SaveRequest.class);
        requireExpectedRevision(transaction, request, input.id());
        WorkflowContracts.Definition definition = mapper().definition(
                        input,
                        Math.addExact(request.expectedRevision(), 1),
                        context.clock().instant());
        long revision = transaction.put(
                documents.documentCollection(request.workspaceId()),
                definition.id(),
                request.expectedRevision(),
                documents.payloads().encode(definition));
        if (revision != definition.revision()) {
            throw new IllegalStateException("Workflow managed revision differs from Definition revision");
        }
        return new ExtensionResponse(documents.payloads().encode(definition), revision);
    }

    private void requireExpectedRevision(
            ExtensionTransaction transaction, ExtensionRequest request, String definitionId) {
        Optional<VersionedDocument> current =
                transaction.get(documents.documentCollection(request.workspaceId()), definitionId);
        long actual = current.map(VersionedDocument::revision).orElse(0L);
        if (actual != request.expectedRevision()) {
            throw new IllegalArgumentException("Workflow Definition revision changed");
        }
    }

    private WorkflowDefinitionMapper mapper() {
        return new WorkflowDefinitionMapper(documents.payloads());
    }

    private static long parseRevision(String value) {
        try {
            long revision = Long.parseLong(value);
            if (revision < 1) {
                throw new IllegalArgumentException("Workflow Definition revision must be positive");
            }
            return revision;
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("Workflow Definition revision is invalid", failure);
        }
    }

    private record CommandDigest(String operation, long expectedRevision, CanonicalPayload payload) {}
}
