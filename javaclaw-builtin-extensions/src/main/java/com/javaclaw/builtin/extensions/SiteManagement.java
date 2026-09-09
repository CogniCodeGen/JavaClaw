package com.javaclaw.builtin.extensions;

import java.net.URI;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.CredentialMetadata;
import com.javaclaw.api.CredentialRef;
import com.javaclaw.api.PrivateNetworkGrant;
import com.javaclaw.api.PrivateNetworkGrantRef;
import com.javaclaw.api.PrivateNetworkPurpose;
import com.javaclaw.builtin.contracts.SiteContracts;
import com.javaclaw.builtin.contracts.SiteManagementContracts;
import com.javaclaw.extension.spi.ExtensionContribution;
import com.javaclaw.extension.spi.ExtensionContributions;
import com.javaclaw.extension.spi.ExtensionExecutionContext;
import com.javaclaw.extension.spi.ExtensionRequest;
import com.javaclaw.extension.spi.ExtensionResponse;
import com.javaclaw.extension.spi.ExtensionTransaction;
import com.javaclaw.extension.spi.VersionedDocument;
import com.javaclaw.extension.spi.ViewQueryRequest;
import com.javaclaw.extension.spi.ViewQueryResult;

/** Site 的强类型新建、权限表面编辑和服务端 authority revision 管理纵切。 */
final class SiteManagement {
    static final String CREATE = "site/create";
    static final String UPDATE = "site/update";
    static final String CREDENTIAL_BIND = "site/credential/bind";
    static final String CREDENTIAL_CLEAR = "site/credential/clear";
    static final String PRIVATE_NETWORK_BIND = "site/private-network/bind";
    static final String PRIVATE_NETWORK_CLEAR = "site/private-network/clear";
    static final String VIEW_NEW = "site/view.new";
    static final String VIEW_SELECTED = "site/view.selected";
    static final String VIEW_CREDENTIALS = "site/view.credentials";
    static final String VIEW_PRIVATE_NETWORK = "site/view.private-network-grants";
    // ViewSchema 只发布协议允许的小写名称；已有 SDK operation 保留为别名，不改变其幂等键语义。
    private static final String LEGACY_PRIVATE_NETWORK_BIND = "site/privateNetwork/bind";
    private static final String LEGACY_PRIVATE_NETWORK_CLEAR = "site/privateNetwork/clear";
    private static final String LEGACY_VIEW_PRIVATE_NETWORK = "site/view.privateNetworkGrants";
    static final String NEW_SOURCE = "newSite";
    static final String EDIT_SOURCE = "siteEditor";
    static final String CREDENTIAL_SOURCE = "siteCredentials";
    static final String PRIVATE_NETWORK_SOURCE = "sitePrivateNetworkGrants";

    private final ManagedDocumentResource<SiteContracts.Site> documents;
    private final SiteDefinitionLifecycle lifecycle;
    private final DefinitionManagementSupport<SiteContracts.Site> support;

    SiteManagement(ManagedDocumentResource<SiteContracts.Site> documents, SiteDefinitionLifecycle lifecycle) {
        this.documents = java.util.Objects.requireNonNull(documents, "documents");
        this.lifecycle = java.util.Objects.requireNonNull(lifecycle, "lifecycle");
        support = new DefinitionManagementSupport<>(documents);
    }

    List<ExtensionContribution> contributions() {
        return List.of(
                new ExtensionContributions.Query(
                        "site.management.query",
                        Set.of(
                                VIEW_NEW,
                                VIEW_SELECTED,
                                VIEW_CREDENTIALS,
                                VIEW_PRIVATE_NETWORK,
                                LEGACY_VIEW_PRIVATE_NETWORK),
                        this::query),
                new ExtensionContributions.Command(
                        "site.management.command",
                        Set.of(
                                CREATE,
                                UPDATE,
                                CREDENTIAL_BIND,
                                CREDENTIAL_CLEAR,
                                PRIVATE_NETWORK_BIND,
                                PRIVATE_NETWORK_CLEAR,
                                LEGACY_PRIVATE_NETWORK_BIND,
                                LEGACY_PRIVATE_NETWORK_CLEAR),
                        this::command),
                new ExtensionContributions.View(
                        "site.management.view",
                        SiteManagementView.create(documents.extensionId().value())));
    }

    private ExtensionResponse query(ExtensionRequest request, ExtensionExecutionContext context) throws Exception {
        return switch (request.operation()) {
            case VIEW_NEW -> support.newEditor(request, NEW_SOURCE);
            case VIEW_SELECTED -> support.selected(request, context, EDIT_SOURCE, SiteManagementView::editor);
            case VIEW_CREDENTIALS -> credentialOptions(request, context);
            case VIEW_PRIVATE_NETWORK, LEGACY_VIEW_PRIVATE_NETWORK -> privateNetworkOptions(request, context);
            default -> throw new IllegalArgumentException("unknown Site management query");
        };
    }

    private ExtensionResponse command(ExtensionRequest request, ExtensionExecutionContext context) throws Exception {
        return switch (request.operation()) {
            case CREATE, UPDATE -> save(request, context);
            case CREDENTIAL_BIND -> bindCredential(request, context);
            case CREDENTIAL_CLEAR -> clearCredential(request, context);
            case PRIVATE_NETWORK_BIND, LEGACY_PRIVATE_NETWORK_BIND -> bindPrivateNetwork(request, context);
            case PRIVATE_NETWORK_CLEAR, LEGACY_PRIVATE_NETWORK_CLEAR -> clearPrivateNetwork(request, context);
            default -> throw new IllegalArgumentException("unknown Site management command");
        };
    }

    private ExtensionResponse save(ExtensionRequest request, ExtensionExecutionContext context) throws Exception {
        SaveMode mode =
                switch (request.operation()) {
                    case CREATE -> SaveMode.CREATE;
                    case UPDATE -> SaveMode.UPDATE;
                    default -> throw new IllegalArgumentException("unknown Site management command");
                };
        String key = request.idempotencyKey()
                .orElseThrow(() -> new IllegalArgumentException("Site save requires idempotency key"));
        CanonicalPayload digest = documents
                .payloads()
                .encode(new CommandDigest(request.operation(), request.expectedRevision(), request.payload()));
        ExtensionResponse response = context.managedStore()
                .inCommand(
                        documents.extensionId(),
                        request.operation(),
                        key,
                        digest.sha256(),
                        transaction -> persist(request, context, transaction, mode));
        lifecycle.afterManagedCommit(documents, response, context);
        return response;
    }

    private ExtensionResponse persist(
            ExtensionRequest request,
            ExtensionExecutionContext context,
            ExtensionTransaction transaction,
            SaveMode mode) {
        SiteManagementContracts.SaveRequest input =
                documents.payloads().decode(request.payload(), SiteManagementContracts.SaveRequest.class);
        Optional<SiteContracts.Site> current = requireCurrent(transaction, request, input.id(), mode);
        SiteContracts.Site site =
                site(input, current, request.expectedRevision(), context.clock().instant());
        transaction.put(
                documents.documentCollection(request.workspaceId()),
                site.id(),
                request.expectedRevision(),
                documents.payloads().encode(site));
        return new ExtensionResponse(documents.payloads().encode(SiteContracts.Projection.from(site)), site.revision());
    }

    private Optional<SiteContracts.Site> requireCurrent(
            ExtensionTransaction transaction, ExtensionRequest request, String id, SaveMode mode) {
        Optional<VersionedDocument> stored = transaction.get(documents.documentCollection(request.workspaceId()), id);
        if (mode == SaveMode.CREATE) {
            if (request.expectedRevision() != 0 || stored.isPresent()) {
                throw new IllegalArgumentException("Site revision changed");
            }
            return Optional.empty();
        }
        VersionedDocument value = stored.orElseThrow(() -> new IllegalArgumentException("Site does not exist"));
        SiteContracts.Site current = documents.payloads().decode(value.payload(), SiteContracts.Site.class);
        if (request.expectedRevision() < 1
                || value.revision() != request.expectedRevision()
                || current.revision() != request.expectedRevision()) {
            throw new IllegalArgumentException("Site revision changed");
        }
        return Optional.of(current);
    }

    private ExtensionResponse bindCredential(ExtensionRequest request, ExtensionExecutionContext context)
            throws Exception {
        SiteManagementContracts.CredentialBindRequest input =
                documents.payloads().decode(request.payload(), SiteManagementContracts.CredentialBindRequest.class);
        CredentialRef reference = new CredentialRef(SiteContracts.SITE_CREDENTIAL_NAMESPACE, input.credentialId());
        context.credentials()
                .metadata(reference)
                .orElseThrow(() -> new IllegalArgumentException("Site credential does not exist"));
        SiteContracts.SiteCredential credential =
                new SiteContracts.SiteCredential(input.kind(), Optional.of(reference), input.apiKeyHeader());
        return mutateAuthority(
                request,
                context,
                input.siteId(),
                input.expectedAuthorityRevision(),
                current -> replaceAuthority(
                        current,
                        credential,
                        current.privateNetworkGrant(),
                        context.clock().instant()));
    }

    private ExtensionResponse clearCredential(ExtensionRequest request, ExtensionExecutionContext context)
            throws Exception {
        SiteManagementContracts.AuthorityClearRequest input =
                documents.payloads().decode(request.payload(), SiteManagementContracts.AuthorityClearRequest.class);
        return mutateAuthority(request, context, input.siteId(), input.expectedAuthorityRevision(), current -> {
            if (current.credential().kind() == SiteContracts.CredentialKind.NONE) {
                throw new IllegalArgumentException("Site does not have a credential binding");
            }
            return replaceAuthority(
                    current,
                    SiteContracts.SiteCredential.none(),
                    current.privateNetworkGrant(),
                    context.clock().instant());
        });
    }

    private ExtensionResponse bindPrivateNetwork(ExtensionRequest request, ExtensionExecutionContext context)
            throws Exception {
        SiteManagementContracts.PrivateNetworkBindRequest input =
                documents.payloads().decode(request.payload(), SiteManagementContracts.PrivateNetworkBindRequest.class);
        return mutateAuthority(request, context, input.siteId(), input.expectedAuthorityRevision(), current -> {
            PrivateNetworkGrant candidate =
                    context.privateNetworkGrants().available(request.workspaceId(), PrivateNetworkPurpose.SITE).stream()
                            .filter(grant -> grant.id().equals(input.grantId()))
                            .findFirst()
                            .orElseThrow(
                                    () -> new IllegalArgumentException("Site private-network grant is unavailable"));
            PrivateNetworkGrantRef reference = new PrivateNetworkGrantRef(candidate.id(), candidate.revision());
            context.privateNetworkGrants()
                    .requireBindable(reference, request.workspaceId(), PrivateNetworkPurpose.SITE, current.origin());
            return replaceAuthority(
                    current,
                    current.credential(),
                    Optional.of(reference),
                    context.clock().instant());
        });
    }

    private ExtensionResponse clearPrivateNetwork(ExtensionRequest request, ExtensionExecutionContext context)
            throws Exception {
        SiteManagementContracts.AuthorityClearRequest input =
                documents.payloads().decode(request.payload(), SiteManagementContracts.AuthorityClearRequest.class);
        return mutateAuthority(request, context, input.siteId(), input.expectedAuthorityRevision(), current -> {
            if (current.privateNetworkGrant().isEmpty()) {
                throw new IllegalArgumentException("Site does not have a private-network grant binding");
            }
            return replaceAuthority(
                    current,
                    current.credential(),
                    Optional.empty(),
                    context.clock().instant());
        });
    }

    private ExtensionResponse mutateAuthority(
            ExtensionRequest request,
            ExtensionExecutionContext context,
            String siteId,
            long expectedAuthorityRevision,
            AuthorityMutation mutation)
            throws Exception {
        String key = request.idempotencyKey()
                .orElseThrow(() -> new IllegalArgumentException("Site authority mutation requires idempotency key"));
        CanonicalPayload digest = documents
                .payloads()
                .encode(new CommandDigest(request.operation(), request.expectedRevision(), request.payload()));
        ExtensionResponse response = context.managedStore()
                .inCommand(
                        documents.extensionId(),
                        request.operation(),
                        key,
                        digest.sha256(),
                        transaction -> mutateAuthority(
                                request, context, transaction, siteId, expectedAuthorityRevision, mutation));
        lifecycle.afterManagedCommit(documents, response, context);
        return response;
    }

    private ExtensionResponse mutateAuthority(
            ExtensionRequest request,
            ExtensionExecutionContext context,
            ExtensionTransaction transaction,
            String siteId,
            long expectedAuthorityRevision,
            AuthorityMutation mutation)
            throws Exception {
        SiteContracts.Site current =
                requireCurrent(transaction, request, siteId, SaveMode.UPDATE).orElseThrow();
        if (current.authorityRevision() != expectedAuthorityRevision) {
            throw new IllegalArgumentException("Site authority revision changed");
        }
        SiteContracts.Site updated = mutation.apply(current);
        transaction.put(
                documents.documentCollection(request.workspaceId()),
                current.id(),
                request.expectedRevision(),
                documents.payloads().encode(updated));
        return new ExtensionResponse(
                documents.payloads().encode(SiteContracts.Projection.from(updated)), updated.revision());
    }

    private static SiteContracts.Site replaceAuthority(
            SiteContracts.Site current,
            SiteContracts.SiteCredential credential,
            Optional<PrivateNetworkGrantRef> privateNetworkGrant,
            Instant updatedAt) {
        return new SiteContracts.Site(
                current.id(),
                Math.addExact(current.revision(), 1),
                Math.addExact(current.authorityRevision(), 1),
                current.name(),
                current.origin(),
                current.allowedOrigins(),
                credential,
                privateNetworkGrant,
                current.enabled(),
                updatedAt);
    }

    private ExtensionResponse credentialOptions(ExtensionRequest request, ExtensionExecutionContext context) {
        ViewQueryRequest query = requireOptionQuery(request, CREDENTIAL_SOURCE);
        List<CredentialRow> available =
                context.credentials().listMetadata(SiteContracts.SITE_CREDENTIAL_NAMESPACE).stream()
                        .sorted(Comparator.comparing(
                                metadata -> metadata.reference().id()))
                        .map(CredentialRow::from)
                        .toList();
        return optionPage(query, available, CredentialRow::id, CredentialRow::revision);
    }

    private ExtensionResponse privateNetworkOptions(ExtensionRequest request, ExtensionExecutionContext context) {
        ViewQueryRequest query = requireOptionQuery(request, PRIVATE_NETWORK_SOURCE);
        List<PrivateNetworkGrantRow> available =
                context.privateNetworkGrants().available(request.workspaceId(), PrivateNetworkPurpose.SITE).stream()
                        .sorted(Comparator.comparing(PrivateNetworkGrant::id))
                        .map(PrivateNetworkGrantRow::from)
                        .toList();
        return optionPage(query, available, PrivateNetworkGrantRow::id, PrivateNetworkGrantRow::revision);
    }

    private ViewQueryRequest requireOptionQuery(ExtensionRequest request, String sourceId) {
        ViewQueryRequest query = documents.payloads().decode(request.payload(), ViewQueryRequest.class);
        if (!sourceId.equals(query.dataSourceId()) || !query.arguments().isEmpty()) {
            throw new IllegalArgumentException("Site option view does not accept arguments");
        }
        return query;
    }

    private <T> ExtensionResponse optionPage(
            ViewQueryRequest query,
            List<T> available,
            java.util.function.Function<T, String> key,
            java.util.function.ToLongFunction<T> revision) {
        int start = optionPageStart(available, query.cursor(), key);
        int end = Math.min(available.size(), Math.addExact(start, query.limit()));
        List<T> page = available.subList(start, end);
        boolean hasMore = end < available.size();
        ViewQueryResult result = new ViewQueryResult(
                query.dataSourceId(),
                page.stream().map(documents.payloads()::encode).toList(),
                documents.payloads().encode(Map.of()),
                hasMore && !page.isEmpty() ? key.apply(page.getLast()) : "",
                hasMore,
                page.stream().mapToLong(revision).max().orElse(0));
        return new ExtensionResponse(documents.payloads().encode(result), result.revision());
    }

    private static <T> int optionPageStart(
            List<T> available, String cursor, java.util.function.Function<T, String> key) {
        if (cursor.isEmpty()) {
            return 0;
        }
        for (int index = 0; index < available.size(); index++) {
            if (key.apply(available.get(index)).equals(cursor)) {
                return index + 1;
            }
        }
        throw new IllegalArgumentException("Site option view cursor is stale");
    }

    private SiteContracts.Site site(
            SiteManagementContracts.SaveRequest input,
            Optional<SiteContracts.Site> current,
            long expectedRevision,
            Instant updatedAt) {
        long revision = Math.addExact(expectedRevision, 1);
        SiteContracts.Site previous = current.orElse(null);
        SiteContracts.SiteCredential credential =
                current.map(SiteContracts.Site::credential).orElseGet(SiteContracts.SiteCredential::none);
        Optional<com.javaclaw.api.PrivateNetworkGrantRef> grant =
                current.flatMap(SiteContracts.Site::privateNetworkGrant);
        long authority = current.map(SiteContracts.Site::authorityRevision).orElse(1L);
        SiteContracts.Site candidate = new SiteContracts.Site(
                input.id(),
                revision,
                authority,
                input.name(),
                input.origin(),
                input.allowedOriginSet(),
                credential,
                grant,
                input.enabled(),
                updatedAt);
        requireNoNormalizedDuplicates(input, candidate);
        if (previous == null) {
            return candidate;
        }
        candidate = clearReferencesWhenOriginsChange(candidate, previous);
        long nextAuthority = lifecycle.nextAuthority(candidate, previous);
        if (nextAuthority == authority) {
            return candidate;
        }
        return new SiteContracts.Site(
                candidate.id(),
                candidate.revision(),
                nextAuthority,
                candidate.name(),
                candidate.origin(),
                candidate.allowedOrigins(),
                candidate.credential(),
                candidate.privateNetworkGrant(),
                candidate.enabled(),
                candidate.updatedAt());
    }

    private static SiteContracts.Site clearReferencesWhenOriginsChange(
            SiteContracts.Site candidate, SiteContracts.Site previous) {
        if (candidate.origin().equals(previous.origin())
                && candidate.allowedOrigins().equals(previous.allowedOrigins())) {
            return candidate;
        }
        return new SiteContracts.Site(
                candidate.id(),
                candidate.revision(),
                candidate.authorityRevision(),
                candidate.name(),
                candidate.origin(),
                candidate.allowedOrigins(),
                SiteContracts.SiteCredential.none(),
                Optional.empty(),
                candidate.enabled(),
                candidate.updatedAt());
    }

    private static void requireNoNormalizedDuplicates(
            SiteManagementContracts.SaveRequest input, SiteContracts.Site site) {
        if (site.allowedOrigins().size() != input.allowedOrigins().size()) {
            throw new IllegalArgumentException("allowedOrigins contains equivalent Origins");
        }
    }

    private enum SaveMode {
        CREATE,
        UPDATE
    }

    @FunctionalInterface
    private interface AuthorityMutation {
        SiteContracts.Site apply(SiteContracts.Site current) throws Exception;
    }

    private record CredentialRow(String id, String label, long revision, Instant updatedAt) {
        private static CredentialRow from(CredentialMetadata metadata) {
            return new CredentialRow(
                    metadata.reference().id(),
                    metadata.reference().id() + " · r" + metadata.revision(),
                    metadata.revision(),
                    metadata.updatedAt());
        }
    }

    private record PrivateNetworkGrantRow(String id, String label, long revision, URI origin, Instant expiresAt) {
        private static PrivateNetworkGrantRow from(PrivateNetworkGrant grant) {
            return new PrivateNetworkGrantRow(
                    grant.id(),
                    grant.origin().toASCIIString() + " · r" + grant.revision(),
                    grant.revision(),
                    grant.origin(),
                    grant.expiresAt());
        }
    }

    private record CommandDigest(String operation, long expectedRevision, CanonicalPayload payload) {}
}
