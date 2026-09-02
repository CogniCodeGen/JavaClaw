package com.javaclaw.builtin.extensions;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.javaclaw.builtin.contracts.DocumentContracts;
import com.javaclaw.builtin.contracts.SiteContracts;
import com.javaclaw.extension.spi.ExtensionContribution;
import com.javaclaw.extension.spi.ExtensionContributions;
import com.javaclaw.extension.spi.ExtensionExecutionContext;
import com.javaclaw.extension.spi.ExtensionRequest;
import com.javaclaw.extension.spi.ExtensionResponse;
import com.javaclaw.extension.spi.VersionedDocument;
import com.javaclaw.extension.spi.ViewQueryRequest;
import com.javaclaw.extension.spi.ViewQueryResult;

/** Site 完整文档之外的脱敏 Query 与 Tool 边界。 */
final class SitePublicDocuments {
    private final ManagedDocumentResource<SiteContracts.Site> documents;

    SitePublicDocuments(ManagedDocumentResource<SiteContracts.Site> documents) {
        this.documents = java.util.Objects.requireNonNull(documents, "documents");
    }

    List<ExtensionContribution> contributions() {
        return List.of(
                new ExtensionContributions.Query(
                        "site.documents.query", Set.of("read", "list", "view.list"), this::query),
                new ExtensionContributions.Tool(
                        "site.read.tool",
                        documents.readOnlyTool("read", "读取一个不含凭据或私网授权引用的 Site 投影", keySchema(), Set.of("site")),
                        this::query),
                new ExtensionContributions.Tool(
                        "site.list.tool",
                        documents.readOnlyTool("list", "分页列出不含凭据或私网授权引用的 Site 投影", pageSchema(), Set.of("site")),
                        this::query));
    }

    private ExtensionResponse query(ExtensionRequest request, ExtensionExecutionContext context) throws Exception {
        return switch (request.operation()) {
            case "read" -> read(request, context);
            case "list" -> list(request, context);
            case "view.list" -> viewList(request, context);
            default -> throw new IllegalArgumentException("unknown Site document query");
        };
    }

    private ExtensionResponse read(ExtensionRequest request, ExtensionExecutionContext context) throws Exception {
        DocumentContracts.Key key = documents.payloads().decode(request.payload(), DocumentContracts.Key.class);
        VersionedDocument stored = context.managedStore()
                .inTransaction(
                        documents.extensionId(),
                        transaction -> transaction
                                .get(documents.documentCollection(request.workspaceId()), key.id())
                                .orElseThrow(() -> new IllegalArgumentException("Site does not exist")));
        SiteContracts.Projection projection = projection(stored);
        return new ExtensionResponse(documents.payloads().encode(projection), projection.revision());
    }

    private ExtensionResponse list(ExtensionRequest request, ExtensionExecutionContext context) throws Exception {
        DocumentContracts.PageRequest input =
                documents.payloads().decode(request.payload(), DocumentContracts.PageRequest.class);
        List<VersionedDocument> stored = context.managedStore()
                .inTransaction(
                        documents.extensionId(),
                        transaction -> transaction.list(
                                documents.documentCollection(request.workspaceId()), input.afterKey(), input.limit()));
        List<SiteContracts.Projection> projections =
                stored.stream().map(this::projection).toList();
        String nextKey = stored.isEmpty() ? input.afterKey() : stored.getLast().key();
        DocumentContracts.Page page = new DocumentContracts.Page(
                projections.stream().map(documents.payloads()::encode).toList(), nextKey);
        return new ExtensionResponse(documents.payloads().encode(page), 0);
    }

    private ExtensionResponse viewList(ExtensionRequest request, ExtensionExecutionContext context) throws Exception {
        ViewQueryRequest input = documents.payloads().decode(request.payload(), ViewQueryRequest.class);
        if (!"documents".equals(input.dataSourceId()) || !input.arguments().isEmpty()) {
            throw new IllegalArgumentException("unknown Site view data source");
        }
        List<VersionedDocument> fetched = context.managedStore()
                .inTransaction(
                        documents.extensionId(),
                        transaction -> transaction.list(
                                documents.documentCollection(request.workspaceId()),
                                input.cursor(),
                                Math.addExact(input.limit(), 1)));
        boolean hasMore = fetched.size() > input.limit();
        List<VersionedDocument> page = hasMore ? fetched.subList(0, input.limit()) : fetched;
        String nextCursor = hasMore && !page.isEmpty() ? page.getLast().key() : "";
        ViewQueryResult result = new ViewQueryResult(
                input.dataSourceId(),
                page.stream()
                        .map(this::projection)
                        .map(documents.payloads()::encode)
                        .toList(),
                documents.payloads().encode(Map.of()),
                nextCursor,
                hasMore,
                0);
        return new ExtensionResponse(documents.payloads().encode(result), 0);
    }

    private SiteContracts.Projection projection(VersionedDocument stored) {
        SiteContracts.Site site = documents.payloads().decode(stored.payload(), SiteContracts.Site.class);
        if (stored.revision() != site.revision()) {
            throw new IllegalStateException("stored Site revision differs from payload");
        }
        return SiteContracts.Projection.from(site);
    }

    private com.javaclaw.api.CanonicalPayload keySchema() {
        return ContractSchemaFactory.document(
                documents.payloads(),
                documents.extensionId().value() + "/site-projection-key/v1",
                "Site projection key",
                Map.of("id", ContractSchemaFactory.string()),
                List.of("id"));
    }

    private com.javaclaw.api.CanonicalPayload pageSchema() {
        return ContractSchemaFactory.document(
                documents.payloads(),
                documents.extensionId().value() + "/site-projection-page/v1",
                "Site projection page",
                Map.of(
                        "afterKey", Map.of("type", "string"),
                        "limit", ContractSchemaFactory.boundedInteger(1, 500)),
                List.of("afterKey", "limit"));
    }
}
