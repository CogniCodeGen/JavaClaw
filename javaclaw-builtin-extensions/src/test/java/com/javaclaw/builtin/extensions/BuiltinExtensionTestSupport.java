package com.javaclaw.builtin.extensions;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.TreeMap;

import com.javaclaw.api.AgentRoleRef;
import com.javaclaw.api.AttachmentContent;
import com.javaclaw.api.AttachmentMetadata;
import com.javaclaw.api.AttachmentRef;
import com.javaclaw.api.AutomationExecutionSnapshot;
import com.javaclaw.api.CancellationSource;
import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.CredentialMetadata;
import com.javaclaw.api.CredentialRef;
import com.javaclaw.api.ExecutionOverrides;
import com.javaclaw.api.InputRequest;
import com.javaclaw.api.InputRequestRecord;
import com.javaclaw.api.ItemStatus;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.PermissionProfileRef;
import com.javaclaw.api.PrivateNetworkGrant;
import com.javaclaw.api.PrivateNetworkGrantRef;
import com.javaclaw.api.PrivateNetworkPurpose;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.api.SecurityGrantState;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.ToolCatalogSnapshot;
import com.javaclaw.api.TurnBudget;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.TurnStatus;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.extension.spi.AttachmentEvidencePort;
import com.javaclaw.extension.spi.AutomationExecutionPolicyPort;
import com.javaclaw.extension.spi.AutomationRoleOption;
import com.javaclaw.extension.spi.CredentialVaultPort;
import com.javaclaw.extension.spi.DocumentRevision;
import com.javaclaw.extension.spi.EmbeddingBatch;
import com.javaclaw.extension.spi.EmbeddingPort;
import com.javaclaw.extension.spi.EmbeddingVector;
import com.javaclaw.extension.spi.ExtensionBundle;
import com.javaclaw.extension.spi.ExtensionContext;
import com.javaclaw.extension.spi.ExtensionContribution;
import com.javaclaw.extension.spi.ExtensionContributions;
import com.javaclaw.extension.spi.ExtensionExecutionContext;
import com.javaclaw.extension.spi.ExtensionId;
import com.javaclaw.extension.spi.ExtensionPayloadCodec;
import com.javaclaw.extension.spi.ExtensionRequest;
import com.javaclaw.extension.spi.ExtensionResponse;
import com.javaclaw.extension.spi.ExtensionTransaction;
import com.javaclaw.extension.spi.InputRequestPort;
import com.javaclaw.extension.spi.IsolatedServicePort;
import com.javaclaw.extension.spi.ItemEvidencePort;
import com.javaclaw.extension.spi.ManagedExtensionStore;
import com.javaclaw.extension.spi.OrchestratedTurnCommand;
import com.javaclaw.extension.spi.OrchestratedTurnResult;
import com.javaclaw.extension.spi.OrchestratedTurnSummary;
import com.javaclaw.extension.spi.PrivateNetworkGrantPort;
import com.javaclaw.extension.spi.ScheduleTargetCatalogPort;
import com.javaclaw.extension.spi.VersionedDocument;
import com.javaclaw.protocol.CanonicalJson;

final class BuiltinExtensionTestSupport {
    static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");

    final TestPayloadCodec payloads = new TestPayloadCodec();
    final InMemoryManagedStore store = new InMemoryManagedStore();
    final RecordingTurns turns = new RecordingTurns(payloads);
    final List<ExecutionOverrides> frozenSelections = new java.util.ArrayList<>();
    final AutomationExecutionPolicyPort executionPolicies = new AutomationExecutionPolicyPort() {
        @Override
        public List<AutomationRoleOption> roles(WorkspaceId workspace) {
            if (!workspace.equals(workspaceId)) {
                throw new IllegalArgumentException("unknown test Workspace");
            }
            return List.of(new AutomationRoleOption(new AgentRoleRef("profile", 1), "默认 Role"));
        }

        @Override
        public List<com.javaclaw.api.ProviderEndpoint> providers(WorkspaceId workspace) {
            return List.of(AutomationV6Fixtures.provider());
        }

        @Override
        public List<PermissionProfile> permissions(WorkspaceId workspace) {
            return List.of(SitePermission.create());
        }

        @Override
        public AutomationExecutionSnapshot freeze(
                WorkspaceId workspace, ExecutionOverrides execution, com.javaclaw.api.CancellationToken ignored) {
            if (!workspace.equals(workspaceId)) {
                throw new IllegalArgumentException("unknown test Workspace");
            }
            frozenSelections.add(execution);
            return executionSnapshot(execution.role().orElse(new AgentRoleRef("default", 1)));
        }
    };
    ScheduleTargetCatalogPort scheduleTargets = ScheduleTargetCatalogPort.unavailable();
    final InputRequestPort inputs = new UnavailableInputPort();
    final RecordingExtensionJobPort jobs = new RecordingExtensionJobPort();
    final WorkspaceId workspaceId = WorkspaceId.random();
    final Map<AttachmentKey, AttachmentMetadata> attachmentClaims = new HashMap<>();
    final Map<String, byte[]> attachmentContents = new HashMap<>();
    final Map<CredentialRef, CredentialMetadata> credentialMetadata = new HashMap<>();
    final Map<String, PrivateNetworkGrant> privateNetworkGrants = new HashMap<>();
    final AttachmentEvidencePort attachments = new AttachmentEvidencePort() {
        @Override
        public AttachmentMetadata requireOwned(AttachmentRef reference) {
            AttachmentMetadata metadata = attachmentClaims.get(new AttachmentKey(workspaceId, reference.digest()));
            if (metadata == null
                    || !metadata.mediaType().equals(reference.mediaType())
                    || metadata.sizeBytes() != reference.sizeBytes()) {
                throw new IllegalArgumentException("Attachment is not owned by the test Workspace");
            }
            return metadata;
        }

        @Override
        public AttachmentContent readOwned(String digest, long maximumBytes) {
            AttachmentMetadata metadata = attachmentClaims.get(new AttachmentKey(workspaceId, digest));
            byte[] content = attachmentContents.get(digest);
            if (metadata == null || content == null || content.length > maximumBytes) {
                throw new IllegalArgumentException("Attachment content is unavailable");
            }
            return new AttachmentContent(metadata, content);
        }

        @Override
        public AttachmentRef storeGenerated(
                String operation, String idempotencyKey, String mediaType, String fileName, byte[] content) {
            String digest = sha256(content);
            AttachmentMetadata metadata = new AttachmentMetadata(digest, mediaType, content.length, NOW);
            attachmentClaims.put(new AttachmentKey(workspaceId, digest), metadata);
            attachmentContents.put(digest, content.clone());
            return new AttachmentRef(digest, mediaType, fileName, content.length);
        }
    };
    final CredentialVaultPort credentials = new CredentialVaultPort() {
        @Override
        public Optional<CredentialMetadata> metadata(CredentialRef reference) {
            return Optional.ofNullable(credentialMetadata.get(reference));
        }

        @Override
        public List<CredentialMetadata> listMetadata(String namespace) {
            return credentialMetadata.values().stream()
                    .filter(metadata -> metadata.reference().namespace().equals(namespace))
                    .sorted(java.util.Comparator.comparing(
                            metadata -> metadata.reference().id()))
                    .toList();
        }
    };
    final PrivateNetworkGrantPort networkGrants = new PrivateNetworkGrantPort() {
        @Override
        public List<PrivateNetworkGrant> available(WorkspaceId workspace, PrivateNetworkPurpose purpose) {
            return privateNetworkGrants.values().stream()
                    .filter(grant -> grant.workspaceId().equals(workspace))
                    .filter(grant -> grant.purpose() == purpose)
                    .filter(grant -> grant.state() == SecurityGrantState.ACTIVE)
                    .filter(grant -> grant.expiresAt().isAfter(NOW))
                    .sorted(java.util.Comparator.comparing(PrivateNetworkGrant::id))
                    .toList();
        }

        @Override
        public PrivateNetworkGrant requireBindable(
                PrivateNetworkGrantRef reference,
                WorkspaceId workspace,
                PrivateNetworkPurpose purpose,
                java.net.URI origin) {
            return available(workspace, purpose).stream()
                    .filter(grant -> grant.id().equals(reference.id()))
                    .filter(grant -> grant.revision() == reference.revision())
                    .filter(grant -> grant.origin().equals(PrivateNetworkGrant.normalizeOrigin(origin)))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("private-network grant is unavailable"));
        }
    };
    EmbeddingPort embeddings = (texts, purpose, cancellation) -> new EmbeddingBatch(
            "e".repeat(64),
            2,
            texts.stream()
                    .map(ignored -> new EmbeddingVector(List.of(1.0, 0.0)))
                    .toList());
    com.javaclaw.extension.spi.WorkspaceExecutionPort workspaceExecution =
            com.javaclaw.extension.spi.WorkspaceExecutionPort.denied();
    final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    final CancellationSource cancellation = new CancellationSource();
    boolean evidenceAvailable = true;
    boolean uncertainEvidence;
    ServiceHandler service = (caller, serviceId, request) -> {
        if (com.javaclaw.builtin.contracts.SiteContracts.BROWSER_INVALIDATE_SERVICE.equals(serviceId)) {
            return payloads.encode(java.util.Map.of());
        }
        throw new IllegalStateException("isolated service is not configured");
    };

    Started start(ExtensionBundle bundle) throws Exception {
        List<ExtensionContribution> contributions = bundle.start(new ExtensionContext(clock, payloads));
        return new Started(bundle, contributions, context(bundle));
    }

    ExtensionExecutionContext context(ExtensionBundle bundle) {
        IsolatedServicePort services =
                invocation -> service.invoke(invocation.caller(), invocation.serviceId(), invocation.request());
        return new ExtensionExecutionContext(
                bundle.descriptor(),
                workspaceId,
                SitePermission.create(),
                cancellation,
                clock,
                store,
                turns,
                executionPolicies,
                scheduleTargets,
                inputs,
                jobs,
                evidence(),
                attachments,
                credentials,
                networkGrants,
                services,
                embeddings,
                workspaceExecution);
    }

    void claimAttachment(WorkspaceId workspace, AttachmentRef reference) {
        attachmentClaims.put(
                new AttachmentKey(workspace, reference.digest()),
                new AttachmentMetadata(reference.digest(), reference.mediaType(), reference.sizeBytes(), NOW));
    }

    void claimAttachment(WorkspaceId workspace, AttachmentRef reference, byte[] content) {
        if (!sha256(content).equals(reference.digest()) || content.length != reference.sizeBytes()) {
            throw new IllegalArgumentException("test Attachment content differs from its reference");
        }
        claimAttachment(workspace, reference);
        attachmentContents.put(reference.digest(), content.clone());
    }

    void claimCredential(CredentialMetadata metadata) {
        credentialMetadata.put(metadata.reference(), metadata);
    }

    void claimPrivateNetworkGrant(PrivateNetworkGrant grant) {
        privateNetworkGrants.put(grant.id(), grant);
    }

    private ItemEvidencePort evidence() {
        return new ItemEvidencePort() {
            @Override
            public boolean containsVerbatim(
                    WorkspaceId workspace, ThreadId thread, com.javaclaw.api.ItemId item, String verbatim) {
                return evidenceAvailable && workspace.equals(workspaceId);
            }

            @Override
            public boolean isUncertainOutcome(WorkspaceId workspace, ThreadId thread, com.javaclaw.api.ItemId item) {
                return uncertainEvidence;
            }
        };
    }

    private static String sha256(byte[] content) {
        try {
            return java.util.HexFormat.of()
                    .formatHex(
                            java.security.MessageDigest.getInstance("SHA-256").digest(content));
        } catch (java.security.NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    ExtensionRequest request(String operation, Object value, Optional<String> key, long expectedRevision) {
        return request(operation, value, key, expectedRevision, Optional.empty());
    }

    ExtensionRequest request(
            String operation, Object value, Optional<String> key, long expectedRevision, Optional<TurnId> turnId) {
        return new ExtensionRequest(
                workspaceId,
                Optional.of(ThreadId.random()),
                turnId,
                operation,
                payloads.encode(value),
                key,
                expectedRevision,
                Optional.empty());
    }

    <T> T decode(ExtensionResponse response, Class<T> type) {
        return payloads.decode(response.payload(), type);
    }

    AutomationExecutionSnapshot executionSnapshot(AgentRoleRef profile) {
        PermissionProfile permission = SitePermission.create();
        return AutomationV6Fixtures.snapshot(
                profile,
                new ProviderRef("provider", 1, "model"),
                new PermissionProfileRef(permission.id(), permission.version()),
                new TurnBudget(1_000, 1_000, 10, 1, Duration.ofMinutes(5)),
                new ToolCatalogSnapshot(TurnId.random(), 1, List.of(), permission, NOW),
                Optional.empty());
    }

    record Started(
            ExtensionBundle bundle, List<ExtensionContribution> contributions, ExtensionExecutionContext context) {
        ExtensionResponse command(ExtensionRequest request) throws Exception {
            ExtensionContributions.Command command = contributions.stream()
                    .filter(ExtensionContributions.Command.class::isInstance)
                    .map(ExtensionContributions.Command.class::cast)
                    .filter(value -> value.operations().contains(request.operation()))
                    .findFirst()
                    .orElseThrow();
            return command.handler().handle(request, context);
        }

        ExtensionResponse query(ExtensionRequest request) throws Exception {
            ExtensionContributions.Query query = contributions.stream()
                    .filter(ExtensionContributions.Query.class::isInstance)
                    .map(ExtensionContributions.Query.class::cast)
                    .filter(value -> value.operations().contains(request.operation()))
                    .findFirst()
                    .orElseThrow();
            return query.handler().handle(request, context);
        }

        ExtensionResponse orchestrate(ExtensionRequest request) throws Exception {
            ExtensionContributions.Orchestrator orchestrator = contributions.stream()
                    .filter(ExtensionContributions.Orchestrator.class::isInstance)
                    .map(ExtensionContributions.Orchestrator.class::cast)
                    .filter(value -> value.operations().contains(request.operation()))
                    .findFirst()
                    .orElseThrow();
            return orchestrator.orchestrator().orchestrate(request, context);
        }

        ExtensionResponse tool(String contributionId, ExtensionRequest request) throws Exception {
            ExtensionContributions.Tool tool = contributions.stream()
                    .filter(ExtensionContributions.Tool.class::isInstance)
                    .map(ExtensionContributions.Tool.class::cast)
                    .filter(value -> value.contributionId().equals(contributionId))
                    .findFirst()
                    .orElseThrow();
            return tool.handler().handle(request, context);
        }
    }

    private record AttachmentKey(WorkspaceId workspaceId, String digest) {}

    @FunctionalInterface
    interface ServiceHandler {
        CanonicalPayload invoke(ExtensionId caller, String serviceId, CanonicalPayload request) throws Exception;
    }

    static final class TestPayloadCodec implements ExtensionPayloadCodec {
        private final CanonicalJson json = new CanonicalJson();

        @Override
        public CanonicalPayload encode(Object value) {
            return json.encode(value);
        }

        @Override
        public <T> T decode(CanonicalPayload payload, Class<T> type) {
            return json.decode(payload, type);
        }
    }

    static final class RecordingTurns implements com.javaclaw.extension.spi.TurnOrchestrationPort {
        private final ExtensionPayloadCodec payloads;
        private final ArrayDeque<TurnStatus> statuses = new ArrayDeque<>();
        private final List<OrchestratedTurnCommand> commands = new ArrayList<>();

        private RecordingTurns(ExtensionPayloadCodec payloads) {
            this.payloads = payloads;
        }

        void returnStatuses(TurnStatus... values) {
            statuses.clear();
            java.util.Collections.addAll(statuses, values);
        }

        List<OrchestratedTurnCommand> commands() {
            return List.copyOf(commands);
        }

        @Override
        public OrchestratedTurnResult execute(
                OrchestratedTurnCommand command, com.javaclaw.api.CancellationToken cancellation) {
            commands.add(command);
            TurnStatus status = statuses.isEmpty() ? TurnStatus.COMPLETED : statuses.removeFirst();
            OrchestratedTurnSummary summary = new OrchestratedTurnSummary(
                    "result:" + command.title(),
                    5,
                    2,
                    0,
                    status == TurnStatus.COMPLETED ? Optional.empty() : Optional.of("TURN_FAILED"),
                    List.of());
            return new OrchestratedTurnResult(ThreadId.random(), TurnId.random(), status, payloads.encode(summary));
        }
    }

    static final class InMemoryManagedStore implements ManagedExtensionStore, ExtensionTransaction {
        private final Map<String, NavigableMap<String, VersionedDocument>> collections = new HashMap<>();
        private final Map<String, VersionedDocument> heads = new HashMap<>();
        private final Map<String, List<DocumentRevision>> histories = new HashMap<>();
        private final Map<String, CommandRecord> commands = new HashMap<>();
        private Instant now = NOW;

        @Override
        public synchronized <T> T inTransaction(ExtensionId extensionId, TransactionWork<T> work) throws Exception {
            return work.execute(this);
        }

        @Override
        public ExtensionResponse inCommand(
                ExtensionId extensionId,
                String operation,
                String idempotencyKey,
                String requestDigest,
                TransactionWork<ExtensionResponse> work)
                throws Exception {
            String key = extensionId.value() + ":" + idempotencyKey;
            CommandRecord existing = commands.get(key);
            if (existing != null) {
                existing.require(operation, requestDigest);
                return existing.response();
            }
            ExtensionResponse response = work.execute(this);
            commands.put(key, new CommandRecord(operation, requestDigest, response));
            return response;
        }

        @Override
        public Optional<ExtensionResponse> recoverCommand(
                ExtensionId extensionId, String operation, String idempotencyKey, String requestDigest) {
            CommandRecord existing = commands.get(extensionId.value() + ":" + idempotencyKey);
            if (existing == null) {
                return Optional.empty();
            }
            existing.require(operation, requestDigest);
            return Optional.of(existing.response());
        }

        @Override
        public Optional<VersionedDocument> get(String collection, String key) {
            return Optional.ofNullable(collection(collection).get(key));
        }

        @Override
        public List<VersionedDocument> list(String collection, String afterKey, int limit) {
            return collection(collection).tailMap(afterKey, false).values().stream()
                    .limit(limit)
                    .toList();
        }

        @Override
        public List<DocumentRevision> history(String collection, String key, long afterRevision, int limit) {
            return histories.getOrDefault(documentKey(collection, key), List.of()).stream()
                    .filter(revision -> revision.revision() > afterRevision)
                    .limit(limit)
                    .toList();
        }

        @Override
        public List<DocumentRevision> listTombstones(String collection, String afterKey, int limit) {
            return heads.entrySet().stream()
                    .filter(entry -> entry.getKey().startsWith(collection + "\u0000"))
                    .filter(entry -> entry.getValue().key().compareTo(afterKey) > 0)
                    .filter(entry ->
                            !collection(collection).containsKey(entry.getValue().key()))
                    .sorted(java.util.Map.Entry.comparingByKey())
                    .limit(limit)
                    .map(entry -> new DocumentRevision(
                            entry.getValue().key(),
                            entry.getValue().revision(),
                            entry.getValue().payload(),
                            true,
                            entry.getValue().updatedAt()))
                    .toList();
        }

        @Override
        public long put(String collection, String key, long expectedRevision, CanonicalPayload payload) {
            NavigableMap<String, VersionedDocument> documents = collection(collection);
            long actual = Optional.ofNullable(heads.get(documentKey(collection, key)))
                    .map(VersionedDocument::revision)
                    .orElse(0L);
            if (actual != expectedRevision) {
                throw new IllegalArgumentException("document revision changed");
            }
            long revision = Math.addExact(actual, 1);
            VersionedDocument document = new VersionedDocument(key, revision, payload, now);
            documents.put(key, document);
            heads.put(documentKey(collection, key), document);
            appendHistory(collection, key, revision, payload, false);
            now = now.plusMillis(1);
            return revision;
        }

        @Override
        public void delete(String collection, String key, long expectedRevision) {
            NavigableMap<String, VersionedDocument> documents = collection(collection);
            VersionedDocument document = heads.get(documentKey(collection, key));
            if (document == null || document.revision() != expectedRevision) {
                throw new IllegalArgumentException("document revision changed");
            }
            documents.remove(key);
            long revision = Math.addExact(expectedRevision, 1);
            heads.put(documentKey(collection, key), new VersionedDocument(key, revision, document.payload(), now));
            appendHistory(collection, key, revision, document.payload(), true);
            now = now.plusMillis(1);
        }

        @Override
        public void appendItem(
                TurnId turnId, String kind, String schemaId, CanonicalPayload payload, ItemStatus status) {}

        @Override
        public void appendEvent(String topic, CanonicalPayload payload) {}

        @Override
        public void enqueueOutbox(String destination, String idempotencyKey, CanonicalPayload payload) {}

        private NavigableMap<String, VersionedDocument> collection(String name) {
            return collections.computeIfAbsent(name, ignored -> new TreeMap<>());
        }

        private void appendHistory(
                String collection, String key, long revision, CanonicalPayload payload, boolean tombstone) {
            histories
                    .computeIfAbsent(documentKey(collection, key), ignored -> new ArrayList<>())
                    .add(new DocumentRevision(key, revision, payload, tombstone, now));
        }

        private static String documentKey(String collection, String key) {
            return collection + "\u0000" + key;
        }

        private record CommandRecord(String operation, String digest, ExtensionResponse response) {
            private void require(String actualOperation, String actualDigest) {
                if (!operation.equals(actualOperation) || !digest.equals(actualDigest)) {
                    throw new IllegalArgumentException("idempotency identity changed");
                }
            }
        }
    }

    private static final class UnavailableInputPort implements InputRequestPort {
        @Override
        public InputRequestRecord open(InputRequest request) {
            throw new UnsupportedOperationException("test input port is unavailable");
        }

        @Override
        public Optional<InputRequestRecord> find(String requestId) {
            return Optional.empty();
        }

        @Override
        public InputRequestRecord completeResolved(String requestId, String producerId) {
            throw new UnsupportedOperationException("test input port is unavailable");
        }
    }
}
