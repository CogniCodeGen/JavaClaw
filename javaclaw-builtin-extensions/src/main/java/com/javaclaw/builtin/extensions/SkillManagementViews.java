package com.javaclaw.builtin.extensions;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.builtin.contracts.SkillContracts;
import com.javaclaw.extension.spi.DocumentRevision;
import com.javaclaw.extension.spi.ExtensionExecutionContext;
import com.javaclaw.extension.spi.ExtensionId;
import com.javaclaw.extension.spi.ExtensionPayloadCodec;
import com.javaclaw.extension.spi.ExtensionRequest;
import com.javaclaw.extension.spi.ExtensionResponse;
import com.javaclaw.extension.spi.ExtensionTransaction;
import com.javaclaw.extension.spi.VersionedDocument;
import com.javaclaw.extension.spi.ViewQueryRequest;
import com.javaclaw.extension.spi.ViewQueryResult;

/** Skill 管理中心使用的 Draft 详情、发布版本连接与 tombstone 只读投影。 */
final class SkillManagementViews {
    private final ExtensionId extensionId;
    private final ExtensionPayloadCodec payloads;

    SkillManagementViews(ExtensionId extensionId, ExtensionPayloadCodec payloads) {
        this.extensionId = Objects.requireNonNull(extensionId, "extensionId");
        this.payloads = Objects.requireNonNull(payloads, "payloads");
    }

    ExtensionResponse newDraft(ExtensionRequest request) {
        ViewQueryRequest query = payloads.decode(request.payload(), ViewQueryRequest.class);
        if (!"newDraft".equals(query.dataSourceId())
                || !query.arguments().isEmpty()
                || !query.cursor().isEmpty()) {
            throw new IllegalArgumentException("Skill new Draft view does not accept arguments");
        }
        ViewQueryResult result =
                new ViewQueryResult(query.dataSourceId(), List.of(), payloads.encode(Map.of()), "", false, 0);
        return new ExtensionResponse(payloads.encode(result), 0);
    }

    ExtensionResponse drafts(ExtensionRequest request, ExtensionExecutionContext context) throws Exception {
        ViewQueryRequest query = payloads.decode(request.payload(), ViewQueryRequest.class);
        if (!query.arguments().isEmpty()) {
            throw new IllegalArgumentException("Skill Draft view arguments must be empty");
        }
        List<DraftViewRow> fetched = context.managedStore()
                .inTransaction(
                        extensionId,
                        transaction ->
                                transaction
                                        .list(drafts(request), query.cursor(), Math.addExact(query.limit(), 1))
                                        .stream()
                                        .map(document -> draftViewRow(transaction, request, document))
                                        .toList());
        boolean more = fetched.size() > query.limit();
        List<DraftViewRow> page = more ? fetched.subList(0, query.limit()) : fetched;
        ViewQueryResult result = new ViewQueryResult(
                query.dataSourceId(),
                page.stream().map(payloads::encode).toList(),
                payloads.encode(Map.of()),
                more && !page.isEmpty() ? page.getLast().id() : "",
                more,
                0);
        return new ExtensionResponse(payloads.encode(result), 0);
    }

    ExtensionResponse draft(ExtensionRequest request, ExtensionExecutionContext context) throws Exception {
        ViewQueryRequest query = payloads.decode(request.payload(), ViewQueryRequest.class);
        if (!"draftEditor".equals(query.dataSourceId())
                || !query.cursor().isEmpty()
                || !query.arguments().keySet().equals(Set.of("id", "revision"))) {
            throw new IllegalArgumentException("Skill Draft editor requires one exact selected revision");
        }
        long selectedRevision = selectedRevision(query.arguments().get("revision"));
        SkillContracts.Draft current = context.managedStore()
                .inTransaction(
                        extensionId,
                        transaction -> requireDraft(
                                transaction, drafts(request), query.arguments().get("id")));
        requireSelectedRevision(current, selectedRevision);
        ViewQueryResult result = new ViewQueryResult(
                query.dataSourceId(), List.of(), payloads.encode(current), "", false, current.revision());
        return new ExtensionResponse(payloads.encode(result), current.revision());
    }

    ExtensionResponse resources(ExtensionRequest request, ExtensionExecutionContext context) throws Exception {
        ViewQueryRequest query = payloads.decode(request.payload(), ViewQueryRequest.class);
        if (!"draftResources".equals(query.dataSourceId())
                || !query.cursor().isEmpty()
                || !query.arguments().keySet().equals(Set.of("id", "revision"))) {
            throw new IllegalArgumentException("Skill resource view requires one exact selected Draft revision");
        }
        long selectedRevision = selectedRevision(query.arguments().get("revision"));
        SkillContracts.Draft current = context.managedStore()
                .inTransaction(
                        extensionId,
                        transaction -> requireDraft(
                                transaction, drafts(request), query.arguments().get("id")));
        requireSelectedRevision(current, selectedRevision);
        ViewQueryResult result = new ViewQueryResult(
                query.dataSourceId(),
                current.resources().stream()
                        .map(resource -> payloads.encode(new ResourceViewRow(
                                resource.id(),
                                current.id(),
                                current.revision(),
                                resource.mediaType(),
                                resource.digest(),
                                resource.executable())))
                        .toList(),
                payloads.encode(Map.of()),
                "",
                false,
                current.revision());
        return new ExtensionResponse(payloads.encode(result), current.revision());
    }

    ExtensionResponse tombstones(ExtensionRequest request, ExtensionExecutionContext context) throws Exception {
        ViewQueryRequest query = payloads.decode(request.payload(), ViewQueryRequest.class);
        if (!query.arguments().isEmpty()) {
            throw new IllegalArgumentException("Skill tombstone view arguments must be empty");
        }
        List<DocumentRevision> fetched = context.managedStore()
                .inTransaction(
                        extensionId,
                        transaction -> transaction.listTombstones(
                                drafts(request), query.cursor(), Math.addExact(query.limit(), 1)));
        boolean more = fetched.size() > query.limit();
        List<DocumentRevision> page = more ? fetched.subList(0, query.limit()) : fetched;
        ViewQueryResult result = new ViewQueryResult(
                query.dataSourceId(),
                page.stream().map(this::tombstoneRow).toList(),
                payloads.encode(Map.of()),
                more && !page.isEmpty() ? page.getLast().key() : "",
                more,
                0);
        return new ExtensionResponse(payloads.encode(result), 0);
    }

    ExtensionResponse history(ExtensionRequest request, ExtensionExecutionContext context) throws Exception {
        ViewQueryRequest query = payloads.decode(request.payload(), ViewQueryRequest.class);
        if (!query.arguments().keySet().equals(Set.of("id"))) {
            throw new IllegalArgumentException("Skill Draft history requires one selected id");
        }
        long afterRevision = historyCursor(query.cursor());
        String id = query.arguments().get("id");
        HistoryPage page = context.managedStore().inTransaction(extensionId, transaction -> {
            SkillContracts.Draft current = requireDraft(transaction, drafts(request), id);
            List<DocumentRevision> fetched =
                    transaction.history(drafts(request), id, afterRevision, Math.addExact(query.limit(), 1));
            boolean more = fetched.size() > query.limit();
            List<DocumentRevision> values = more ? fetched.subList(0, query.limit()) : fetched;
            return new HistoryPage(current.revision(), values, more);
        });
        ViewQueryResult result = new ViewQueryResult(
                query.dataSourceId(),
                page.values().stream()
                        .map(value -> historyRow(value, page.currentRevision()))
                        .toList(),
                payloads.encode(Map.of()),
                page.more() && !page.values().isEmpty()
                        ? Long.toString(page.values().getLast().revision())
                        : "",
                page.more(),
                page.currentRevision());
        return new ExtensionResponse(payloads.encode(result), result.revision());
    }

    ExtensionResponse proposals(ExtensionRequest request, ExtensionExecutionContext context) throws Exception {
        ViewQueryRequest query = payloads.decode(request.payload(), ViewQueryRequest.class);
        if (!query.arguments().isEmpty()) {
            throw new IllegalArgumentException("Skill Proposal view arguments must be empty");
        }
        List<VersionedDocument> fetched = context.managedStore()
                .inTransaction(
                        extensionId,
                        transaction ->
                                transaction.list(proposals(request), query.cursor(), Math.addExact(query.limit(), 1)));
        boolean more = fetched.size() > query.limit();
        List<VersionedDocument> page = more ? fetched.subList(0, query.limit()) : fetched;
        ViewQueryResult result = new ViewQueryResult(
                query.dataSourceId(),
                page.stream().map(this::proposalRow).toList(),
                payloads.encode(Map.of()),
                more && !page.isEmpty() ? page.getLast().key() : "",
                more,
                page.stream().mapToLong(VersionedDocument::revision).max().orElse(0));
        return new ExtensionResponse(payloads.encode(result), result.revision());
    }

    ExtensionResponse published(ExtensionRequest request, ExtensionExecutionContext context) throws Exception {
        ViewQueryRequest query = payloads.decode(request.payload(), ViewQueryRequest.class);
        if (!query.arguments().isEmpty()) {
            throw new IllegalArgumentException("Published Skill view arguments must be empty");
        }
        List<VersionedDocument> fetched = context.managedStore()
                .inTransaction(
                        extensionId,
                        transaction ->
                                transaction.list(published(request), query.cursor(), Math.addExact(query.limit(), 1)));
        boolean more = fetched.size() > query.limit();
        List<VersionedDocument> page = more ? fetched.subList(0, query.limit()) : fetched;
        ViewQueryResult result = new ViewQueryResult(
                query.dataSourceId(),
                page.stream().map(VersionedDocument::payload).toList(),
                payloads.encode(Map.of()),
                more && !page.isEmpty() ? page.getLast().key() : "",
                more,
                0);
        return new ExtensionResponse(payloads.encode(result), 0);
    }

    private DraftViewRow draftViewRow(
            ExtensionTransaction transaction, ExtensionRequest request, VersionedDocument document) {
        SkillContracts.Draft draft = decodeDraft(document, "Skill Draft");
        long publishedRevision = transaction
                .get(published(request), draft.id())
                .map(value -> decodePublished(value, "Published Skill").revision())
                .orElse(0L);
        return new DraftViewRow(
                draft.id(),
                draft.revision(),
                draft.name(),
                draft.description(),
                draft.resources().size(),
                publishedRevision);
    }

    private CanonicalPayload tombstoneRow(DocumentRevision tombstone) {
        SkillContracts.Draft previous = payloads.decode(tombstone.payload(), SkillContracts.Draft.class);
        return payloads.encode(new DraftTombstoneRow(
                tombstone.key(), tombstone.revision(), Math.subtractExact(tombstone.revision(), 1), previous.name()));
    }

    private CanonicalPayload historyRow(DocumentRevision revision, long currentRevision) {
        SkillContracts.Draft draft = payloads.decode(revision.payload(), SkillContracts.Draft.class);
        long sourceRevision = revision.tombstone() ? Math.subtractExact(revision.revision(), 1) : revision.revision();
        return payloads.encode(new HistoryViewRow(
                draft.id(),
                revision.revision(),
                sourceRevision,
                currentRevision,
                draft.name(),
                draft.description(),
                revision.tombstone(),
                revision.updatedAt()));
    }

    private CanonicalPayload proposalRow(VersionedDocument document) {
        SkillContracts.Proposal proposal = payloads.decode(document.payload(), SkillContracts.Proposal.class);
        requireRevision(proposal.revision(), document.revision(), "Skill Proposal");
        return payloads.encode(new ProposalViewRow(
                proposal.id(),
                proposal.revision(),
                proposal.candidate().draftId(),
                proposal.candidate().name(),
                proposal.candidate().description(),
                proposal.state(),
                proposal.draftId().orElse(""),
                proposal.updatedAt()));
    }

    private static long historyCursor(String cursor) {
        if (cursor.isEmpty()) {
            return 0;
        }
        try {
            long revision = Long.parseLong(cursor);
            if (revision < 0) {
                throw new IllegalArgumentException("Skill history cursor must not be negative");
            }
            return revision;
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("Skill history cursor is invalid", failure);
        }
    }

    private static long selectedRevision(String value) {
        try {
            long revision = Long.parseLong(value);
            if (revision < 1) {
                throw new IllegalArgumentException("Skill Draft revision must be positive");
            }
            return revision;
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("Skill Draft revision is invalid", failure);
        }
    }

    private static void requireSelectedRevision(SkillContracts.Draft draft, long selectedRevision) {
        if (draft.revision() != selectedRevision) {
            throw new IllegalArgumentException("Skill Draft selection is stale");
        }
    }

    private SkillContracts.Draft requireDraft(ExtensionTransaction transaction, String collection, String id) {
        return transaction
                .get(collection, id)
                .map(document -> decodeDraft(document, "Skill Draft"))
                .orElseThrow(() -> new IllegalArgumentException("Skill Draft does not exist"));
    }

    private SkillContracts.Draft decodeDraft(VersionedDocument document, String label) {
        SkillContracts.Draft draft = payloads.decode(document.payload(), SkillContracts.Draft.class);
        requireRevision(draft.revision(), document.revision(), label);
        return draft;
    }

    private SkillContracts.PublishedSkill decodePublished(VersionedDocument document, String label) {
        SkillContracts.PublishedSkill published =
                payloads.decode(document.payload(), SkillContracts.PublishedSkill.class);
        requireRevision(published.revision(), document.revision(), label);
        return published;
    }

    private static void requireRevision(long payload, long stored, String label) {
        if (payload != stored) {
            throw new IllegalStateException(label + " payload revision differs from managed store");
        }
    }

    private static String drafts(ExtensionRequest request) {
        return "drafts." + request.workspaceId();
    }

    private static String published(ExtensionRequest request) {
        return "published." + request.workspaceId();
    }

    private static String proposals(ExtensionRequest request) {
        return "proposals." + request.workspaceId();
    }

    private record DraftViewRow(
            String id, long revision, String name, String description, int resourceCount, long publishedRevision) {}

    private record DraftTombstoneRow(String id, long revision, long sourceRevision, String name) {}

    private record ResourceViewRow(
            String id, String draftId, long draftRevision, String mediaType, String digest, boolean executable) {}

    private record HistoryPage(long currentRevision, List<DocumentRevision> values, boolean more) {}

    private record HistoryViewRow(
            String id,
            long revision,
            long sourceRevision,
            long currentRevision,
            String name,
            String description,
            boolean tombstone,
            java.time.Instant updatedAt) {}

    private record ProposalViewRow(
            String id,
            long revision,
            String candidateDraftId,
            String name,
            String description,
            SkillContracts.ProposalState state,
            String adoptedDraftId,
            java.time.Instant updatedAt) {}
}
