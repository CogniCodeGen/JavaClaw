package com.javaclaw.builtin.extensions;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.builtin.contracts.VersionedExtensionDocument;
import com.javaclaw.extension.spi.ExtensionExecutionContext;
import com.javaclaw.extension.spi.ExtensionRequest;
import com.javaclaw.extension.spi.ExtensionResponse;
import com.javaclaw.extension.spi.ExtensionTransaction;
import com.javaclaw.extension.spi.VersionedDocument;
import com.javaclaw.extension.spi.ViewQueryRequest;
import com.javaclaw.extension.spi.ViewQueryResult;

/** Plan 与 SDD 管理命令共享的事务、幂等和权威详情读取边界。 */
final class DefinitionManagementSupport<T extends VersionedExtensionDocument> {
    private final ManagedDocumentResource<T> documents;

    DefinitionManagementSupport(ManagedDocumentResource<T> documents) {
        this.documents = java.util.Objects.requireNonNull(documents, "documents");
    }

    <I> ExtensionResponse save(
            ExtensionRequest request,
            ExtensionExecutionContext context,
            Class<I> inputType,
            Function<I, String> id,
            DefinitionFactory<I, T> factory,
            SaveMode mode)
            throws Exception {
        String key = request.idempotencyKey()
                .orElseThrow(() -> new IllegalArgumentException("Definition save requires idempotency key"));
        CanonicalPayload digest = documents
                .payloads()
                .encode(new CommandDigest(request.operation(), request.expectedRevision(), request.payload()));
        return context.managedStore()
                .inCommand(
                        documents.extensionId(),
                        request.operation(),
                        key,
                        digest.sha256(),
                        transaction -> save(request, context, transaction, inputType, id, factory, mode));
    }

    <E> ExtensionResponse selected(
            ExtensionRequest request, ExtensionExecutionContext context, String sourceId, Function<T, E> editor)
            throws Exception {
        ViewQueryRequest query = documents.payloads().decode(request.payload(), ViewQueryRequest.class);
        Selection selection = selection(query, sourceId);
        T definition = context.managedStore()
                .inTransaction(documents.extensionId(), transaction -> requireExact(transaction, request, selection));
        ViewQueryResult result = new ViewQueryResult(
                sourceId,
                java.util.List.of(),
                documents.payloads().encode(editor.apply(definition)),
                "",
                false,
                definition.revision());
        return new ExtensionResponse(documents.payloads().encode(result), definition.revision());
    }

    ExtensionResponse newEditor(ExtensionRequest request, String sourceId) {
        ViewQueryRequest query = documents.payloads().decode(request.payload(), ViewQueryRequest.class);
        if (!sourceId.equals(query.dataSourceId())
                || !query.arguments().isEmpty()
                || !query.cursor().isEmpty()) {
            throw new IllegalArgumentException("new Definition view does not accept arguments");
        }
        ViewQueryResult result = new ViewQueryResult(
                sourceId, java.util.List.of(), documents.payloads().encode(Map.of()), "", false, 0);
        return new ExtensionResponse(documents.payloads().encode(result), 0);
    }

    private <I> ExtensionResponse save(
            ExtensionRequest request,
            ExtensionExecutionContext context,
            ExtensionTransaction transaction,
            Class<I> inputType,
            Function<I, String> id,
            DefinitionFactory<I, T> factory,
            SaveMode mode) {
        I input = documents.payloads().decode(request.payload(), inputType);
        String definitionId = id.apply(input);
        requireRevision(transaction, request, definitionId, mode);
        long revision = Math.addExact(request.expectedRevision(), 1);
        T definition = factory.create(input, revision, context.clock().instant());
        if (!definition.id().equals(definitionId) || definition.revision() != revision) {
            throw new IllegalStateException("Definition factory changed identity or revision");
        }
        CanonicalPayload payload = documents.payloads().encode(definition);
        transaction.put(
                documents.documentCollection(request.workspaceId()),
                definition.id(),
                request.expectedRevision(),
                payload);
        return new ExtensionResponse(payload, definition.revision());
    }

    private T requireExact(ExtensionTransaction transaction, ExtensionRequest request, Selection selection) {
        VersionedDocument stored = transaction
                .get(documents.documentCollection(request.workspaceId()), selection.id())
                .orElseThrow(() -> new IllegalArgumentException("Definition does not exist"));
        T definition = documents.payloads().decode(stored.payload(), documents.documentType());
        if (stored.revision() != selection.revision()
                || definition.revision() != selection.revision()
                || !definition.id().equals(selection.id())) {
            throw new IllegalArgumentException("Definition selection revision changed");
        }
        return definition;
    }

    private void requireRevision(ExtensionTransaction transaction, ExtensionRequest request, String id, SaveMode mode) {
        Optional<VersionedDocument> current = transaction.get(documents.documentCollection(request.workspaceId()), id);
        boolean valid =
                switch (mode) {
                    case CREATE -> request.expectedRevision() == 0 && current.isEmpty();
                    case UPDATE ->
                        request.expectedRevision() > 0
                                && current.map(VersionedDocument::revision)
                                        .filter(revision -> revision == request.expectedRevision())
                                        .isPresent();
                };
        if (!valid) {
            throw new IllegalArgumentException("Definition revision changed");
        }
    }

    private static Selection selection(ViewQueryRequest query, String sourceId) {
        if (!sourceId.equals(query.dataSourceId())
                || !query.arguments().keySet().equals(Set.of("id", "revision"))
                || !query.cursor().isEmpty()) {
            throw new IllegalArgumentException("Definition editor requires one exact selection");
        }
        String id = query.arguments().get("id");
        try {
            long revision = Long.parseLong(query.arguments().get("revision"));
            if (id == null || id.isBlank() || revision < 1) {
                throw new IllegalArgumentException("Definition selection is invalid");
            }
            return new Selection(id, revision);
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("Definition selection revision is invalid", failure);
        }
    }

    enum SaveMode {
        CREATE,
        UPDATE
    }

    @FunctionalInterface
    interface DefinitionFactory<I, T> {
        T create(I input, long revision, Instant updatedAt);
    }

    private record Selection(String id, long revision) {}

    private record CommandDigest(String operation, long expectedRevision, CanonicalPayload payload) {}
}
