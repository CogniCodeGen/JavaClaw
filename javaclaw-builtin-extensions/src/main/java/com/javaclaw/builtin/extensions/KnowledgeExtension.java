package com.javaclaw.builtin.extensions;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.builtin.contracts.BuiltinExtensionIds;
import com.javaclaw.builtin.contracts.DocumentContracts;
import com.javaclaw.builtin.contracts.KnowledgeContracts;
import com.javaclaw.extension.spi.ContributionKind;
import com.javaclaw.extension.spi.ExtensionAvailability;
import com.javaclaw.extension.spi.ExtensionBundle;
import com.javaclaw.extension.spi.ExtensionContext;
import com.javaclaw.extension.spi.ExtensionContribution;
import com.javaclaw.extension.spi.ExtensionContributions;
import com.javaclaw.extension.spi.ExtensionDescriptor;
import com.javaclaw.extension.spi.ExtensionExecutionContext;
import com.javaclaw.extension.spi.ExtensionId;
import com.javaclaw.extension.spi.ExtensionJob;
import com.javaclaw.extension.spi.ExtensionJobMutation;
import com.javaclaw.extension.spi.ExtensionJobRegistration;
import com.javaclaw.extension.spi.ExtensionJobRuntimeContext;
import com.javaclaw.extension.spi.ExtensionJobSubmission;
import com.javaclaw.extension.spi.ExtensionPayloadCodec;
import com.javaclaw.extension.spi.ExtensionRequest;
import com.javaclaw.extension.spi.ExtensionRequirements;
import com.javaclaw.extension.spi.ExtensionResponse;
import com.javaclaw.extension.spi.ExtensionSchema;
import com.javaclaw.extension.spi.ExtensionTransaction;
import com.javaclaw.extension.spi.ExtensionTrust;
import com.javaclaw.extension.spi.VersionedDocument;
import com.javaclaw.extension.spi.ViewQueryRequest;
import com.javaclaw.extension.spi.ViewQueryResult;

/** Knowledge 领域实现；Attachment 解析与索引构建由可恢复后台 Job 完成。 */
final class KnowledgeExtension implements ExtensionBundle {
    private static final ExtensionId ID = new ExtensionId(BuiltinExtensionIds.KNOWLEDGE);
    private static final Set<String> QUERIES = Set.of(
            "source/read",
            "source/list",
            "generation/read",
            "generation/list",
            "search",
            "view.new-source",
            "view.sources",
            "view.source",
            "view.generations",
            "view.jobs");
    private static final Set<String> COMMANDS = Set.of("source/import", "source/delete");

    private final ExtensionDescriptor descriptor = new ExtensionDescriptor(
            ID,
            "Knowledge",
            "5.0.0",
            1,
            Set.of(ContributionKind.QUERY, ContributionKind.COMMAND, ContributionKind.TOOL, ContributionKind.VIEW),
            new ExtensionRequirements(
                    ExtensionTrust.BUILT_IN,
                    ExtensionAvailability.OPTIONAL,
                    2,
                    BuiltinStoragePermission.create(ID.value())));
    private ExtensionPayloadCodec payloads;

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
        return List.of(
                new ExtensionContributions.Query("knowledge.query", QUERIES, this::query),
                new ExtensionContributions.Command("knowledge.command", COMMANDS, this::command),
                new ExtensionContributions.Tool(
                        "knowledge.search.tool",
                        KnowledgeExtensionPresentation.searchTool(codec(), descriptor.revision()),
                        this::search),
                new ExtensionContributions.View(
                        "knowledge.management", KnowledgeExtensionPresentation.managementView()));
    }

    @Override
    public List<ExtensionSchema> schemas() {
        return KnowledgeExtensionPresentation.schemas(codec());
    }

    @Override
    public List<ExtensionJobRegistration> jobExecutors(ExtensionJobRuntimeContext context) {
        return List.of(new ExtensionJobRegistration(
                KnowledgeContracts.GENERATION_JOB_TYPE, new KnowledgeJobExecutor(context)));
    }

    private ExtensionResponse query(ExtensionRequest request, ExtensionExecutionContext context) throws Exception {
        return switch (request.operation()) {
            case "source/read" -> readSource(request, context);
            case "source/list" -> listSources(request, context);
            case "generation/read" -> readGeneration(request, context);
            case "generation/list" -> listGenerations(request, context);
            case "search" -> search(request, context);
            case "view.new-source" -> viewNewSource(request);
            case "view.sources" -> viewSources(request, context);
            case "view.source" -> viewSource(request, context);
            case "view.generations" -> viewGenerations(request, context);
            case "view.jobs" -> viewJobs(request, context);
            default -> throw new IllegalArgumentException("unknown Knowledge query: " + request.operation());
        };
    }

    private ExtensionResponse command(ExtensionRequest request, ExtensionExecutionContext context) throws Exception {
        return switch (request.operation()) {
            case "source/import" -> importSource(request, context);
            case "source/delete" -> deleteSource(request, context);
            default -> throw new IllegalArgumentException("unknown Knowledge command: " + request.operation());
        };
    }

    private ExtensionResponse importSource(ExtensionRequest request, ExtensionExecutionContext context)
            throws Exception {
        KnowledgeContracts.ImportRequest input =
                codec().decode(request.payload(), KnowledgeContracts.ImportRequest.class);
        long targetRevision = Math.addExact(request.expectedRevision(), 1);
        String idempotencyKey = request.idempotencyKey()
                .orElseThrow(() -> new IllegalArgumentException("source/import requires idempotency key"));
        CanonicalPayload identity = codec().encode(JobSubmissionIdentity.from(ID, request));
        ExtensionJob job = context.jobs()
                .submit(
                        identity,
                        new ExtensionJobMutation(idempotencyKey, 0),
                        () -> submission(request, context, input, targetRevision));
        requireSubmissionRevision(input.id(), request.expectedRevision(), targetRevision, job.id(), context);
        KnowledgeContracts.ImportAccepted accepted =
                new KnowledgeContracts.ImportAccepted(job.id(), input.id(), targetRevision);
        return new ExtensionResponse(codec().encode(accepted), targetRevision);
    }

    private ExtensionJobSubmission submission(
            ExtensionRequest request,
            ExtensionExecutionContext context,
            KnowledgeContracts.ImportRequest input,
            long targetRevision)
            throws Exception {
        requireCurrentRevision(input.id(), request.expectedRevision(), context);
        return new ExtensionJobSubmission(
                ID,
                request.workspaceId(),
                KnowledgeContracts.GENERATION_JOB_TYPE,
                input.id(),
                targetRevision,
                codec().encode(new KnowledgeJobContracts.FrozenImport(
                        input, request.expectedRevision(), context.effectivePermissions())),
                codec().encode(KnowledgeJobContracts.Checkpoint.pending()));
    }

    private ExtensionResponse deleteSource(ExtensionRequest request, ExtensionExecutionContext context)
            throws Exception {
        KnowledgeContracts.Key key = codec().decode(request.payload(), KnowledgeContracts.Key.class);
        String idempotencyKey = request.idempotencyKey()
                .orElseThrow(() -> new IllegalArgumentException("source/delete requires idempotency key"));
        CanonicalPayload digest =
                codec().encode(new CommandDigest(request.operation(), request.expectedRevision(), request.payload()));
        return context.managedStore()
                .inCommand(ID, request.operation(), idempotencyKey, digest.sha256(), transaction -> {
                    VersionedDocument current = transaction
                            .get(KnowledgeCollections.sources(request.workspaceId()), key.id())
                            .orElseThrow(() -> new IllegalArgumentException("Knowledge source does not exist"));
                    if (current.revision() != request.expectedRevision()) {
                        throw new IllegalArgumentException("Knowledge source revision changed");
                    }
                    transaction.delete(
                            KnowledgeCollections.sources(request.workspaceId()), key.id(), request.expectedRevision());
                    return new ExtensionResponse(
                            codec().encode(new DocumentContracts.Deleted(key.id())),
                            Math.addExact(request.expectedRevision(), 1));
                });
    }

    private ExtensionResponse readSource(ExtensionRequest request, ExtensionExecutionContext context) throws Exception {
        KnowledgeContracts.Key key = codec().decode(request.payload(), KnowledgeContracts.Key.class);
        KnowledgeContracts.Source source = context.managedStore()
                .inTransaction(
                        ID,
                        transaction -> decodeRequired(
                                transaction,
                                KnowledgeCollections.sources(request.workspaceId()),
                                key.id(),
                                KnowledgeContracts.Source.class));
        return new ExtensionResponse(codec().encode(source), source.revision());
    }

    private ExtensionResponse readGeneration(ExtensionRequest request, ExtensionExecutionContext context)
            throws Exception {
        KnowledgeContracts.Key key = codec().decode(request.payload(), KnowledgeContracts.Key.class);
        KnowledgeContracts.Generation generation = context.managedStore()
                .inTransaction(
                        ID,
                        transaction -> decodeRequired(
                                transaction,
                                KnowledgeCollections.generations(request.workspaceId()),
                                key.id(),
                                KnowledgeContracts.Generation.class));
        return new ExtensionResponse(codec().encode(generation), generation.revision());
    }

    private ExtensionResponse listSources(ExtensionRequest request, ExtensionExecutionContext context)
            throws Exception {
        KnowledgeContracts.PageRequest page = codec().decode(request.payload(), KnowledgeContracts.PageRequest.class);
        KnowledgeContracts.SourcePage result = context.managedStore().inTransaction(ID, transaction -> {
            DocumentPage documents = page(transaction, KnowledgeCollections.sources(request.workspaceId()), page);
            return new KnowledgeContracts.SourcePage(
                    documents.values().stream()
                            .map(document -> codec().decode(document.payload(), KnowledgeContracts.Source.class))
                            .toList(),
                    documents.nextKey());
        });
        return new ExtensionResponse(codec().encode(result), 0);
    }

    private ExtensionResponse listGenerations(ExtensionRequest request, ExtensionExecutionContext context)
            throws Exception {
        KnowledgeContracts.PageRequest page = codec().decode(request.payload(), KnowledgeContracts.PageRequest.class);
        KnowledgeContracts.GenerationPage result = context.managedStore().inTransaction(ID, transaction -> {
            DocumentPage documents = page(transaction, KnowledgeCollections.generations(request.workspaceId()), page);
            return new KnowledgeContracts.GenerationPage(
                    documents.values().stream()
                            .map(document -> codec().decode(document.payload(), KnowledgeContracts.Generation.class))
                            .toList(),
                    documents.nextKey());
        });
        return new ExtensionResponse(codec().encode(result), 0);
    }

    private ExtensionResponse search(ExtensionRequest request, ExtensionExecutionContext context) throws Exception {
        KnowledgeContracts.SearchRequest search =
                codec().decode(request.payload(), KnowledgeContracts.SearchRequest.class);
        KnowledgeIndexSnapshot snapshot =
                context.managedStore().inTransaction(ID, transaction -> loadIndex(transaction, request));
        KnowledgeContracts.SearchResult result =
                new KnowledgeSearchEngine(context.embeddings()).search(snapshot, search, context.cancellation());
        return new ExtensionResponse(codec().encode(result), 0);
    }

    private KnowledgeIndexSnapshot loadIndex(ExtensionTransaction transaction, ExtensionRequest request) {
        List<KnowledgeContracts.Source> sources =
                listAll(transaction, KnowledgeCollections.sources(request.workspaceId())).stream()
                        .map(document -> codec().decode(document.payload(), KnowledgeContracts.Source.class))
                        .toList();
        Map<String, KnowledgeContracts.Generation> generations = new HashMap<>();
        listAll(transaction, KnowledgeCollections.generations(request.workspaceId()))
                .forEach(document -> {
                    KnowledgeContracts.Generation generation =
                            codec().decode(document.payload(), KnowledgeContracts.Generation.class);
                    generations.put(generation.id(), generation);
                });
        Map<String, List<KnowledgeContracts.Chunk>> chunks = new HashMap<>();
        listAll(transaction, KnowledgeCollections.chunks(request.workspaceId())).forEach(document -> {
            KnowledgeContracts.Chunk chunk = codec().decode(document.payload(), KnowledgeContracts.Chunk.class);
            chunks.computeIfAbsent(chunk.generationId(), ignored -> new ArrayList<>())
                    .add(chunk);
        });
        List<KnowledgeIndexSnapshot.Entry> entries = sources.stream()
                .map(source -> entry(source, generations, chunks))
                .toList();
        return new KnowledgeIndexSnapshot(entries);
    }

    private KnowledgeIndexSnapshot.Entry entry(
            KnowledgeContracts.Source source,
            Map<String, KnowledgeContracts.Generation> generations,
            Map<String, List<KnowledgeContracts.Chunk>> chunks) {
        KnowledgeContracts.Generation generation = Optional.ofNullable(generations.get(source.activeGenerationId()))
                .orElseThrow(() -> new IllegalStateException("Knowledge active Generation is missing"));
        List<KnowledgeContracts.Chunk> ordered =
                Optional.ofNullable(chunks.get(generation.id())).orElse(List.of()).stream()
                        .sorted(Comparator.comparingInt(KnowledgeContracts.Chunk::index))
                        .toList();
        if (ordered.size() != generation.chunkCount()) {
            throw new IllegalStateException("Knowledge Generation chunk count differs from metadata");
        }
        return new KnowledgeIndexSnapshot.Entry(source, generation, ordered);
    }

    private ExtensionResponse viewSources(ExtensionRequest request, ExtensionExecutionContext context)
            throws Exception {
        return viewDocuments(request, context, KnowledgeCollections.sources(request.workspaceId()));
    }

    private ExtensionResponse viewNewSource(ExtensionRequest request) {
        ViewQueryRequest query = codec().decode(request.payload(), ViewQueryRequest.class);
        if (!"newSource".equals(query.dataSourceId())
                || !query.arguments().isEmpty()
                || !query.cursor().isEmpty()) {
            throw new IllegalArgumentException("unknown Knowledge new source view request");
        }
        ViewQueryResult result =
                new ViewQueryResult(query.dataSourceId(), List.of(), codec().encode(Map.of()), "", false, 0);
        return new ExtensionResponse(codec().encode(result), 0);
    }

    private ExtensionResponse viewSource(ExtensionRequest request, ExtensionExecutionContext context) throws Exception {
        ViewQueryRequest query = codec().decode(request.payload(), ViewQueryRequest.class);
        if (!"sourceEditor".equals(query.dataSourceId())
                || !query.arguments().keySet().equals(Set.of("id", "revision"))
                || !query.cursor().isEmpty()) {
            throw new IllegalArgumentException("Knowledge source editor requires one exact selected revision");
        }
        long selectedRevision = parseRevision(query.arguments().get("revision"));
        KnowledgeContracts.Source source = context.managedStore()
                .inTransaction(
                        ID,
                        transaction -> decodeRequired(
                                transaction,
                                KnowledgeCollections.sources(request.workspaceId()),
                                query.arguments().get("id"),
                                KnowledgeContracts.Source.class));
        if (source.revision() != selectedRevision) {
            throw new IllegalArgumentException("Knowledge source editor selection is stale");
        }
        ViewQueryResult result = new ViewQueryResult(
                query.dataSourceId(), List.of(), codec().encode(source), "", false, source.revision());
        return new ExtensionResponse(codec().encode(result), source.revision());
    }

    private static long parseRevision(String value) {
        try {
            long revision = Long.parseLong(value);
            if (revision < 1) {
                throw new IllegalArgumentException("Knowledge source revision must be positive");
            }
            return revision;
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("Knowledge source revision is invalid", failure);
        }
    }

    private ExtensionResponse viewGenerations(ExtensionRequest request, ExtensionExecutionContext context)
            throws Exception {
        return viewDocuments(request, context, KnowledgeCollections.generations(request.workspaceId()));
    }

    private ExtensionResponse viewDocuments(
            ExtensionRequest request, ExtensionExecutionContext context, String collection) throws Exception {
        ViewQueryRequest query = codec().decode(request.payload(), ViewQueryRequest.class);
        requireViewRequest(request.operation(), query);
        List<VersionedDocument> fetched = context.managedStore()
                .inTransaction(ID, transaction -> transaction.list(collection, query.cursor(), query.limit() + 1));
        boolean hasMore = fetched.size() > query.limit();
        List<VersionedDocument> page = hasMore ? fetched.subList(0, query.limit()) : fetched;
        String cursor = hasMore && !page.isEmpty() ? page.getLast().key() : "";
        ViewQueryResult result = new ViewQueryResult(
                query.dataSourceId(),
                page.stream().map(VersionedDocument::payload).toList(),
                codec().encode(Map.of()),
                cursor,
                hasMore,
                page.stream().mapToLong(VersionedDocument::revision).max().orElse(0));
        return new ExtensionResponse(codec().encode(result), result.revision());
    }

    private ExtensionResponse viewJobs(ExtensionRequest request, ExtensionExecutionContext context) {
        ViewQueryRequest query = codec().decode(request.payload(), ViewQueryRequest.class);
        requireViewRequest(request.operation(), query);
        com.javaclaw.extension.spi.ExtensionJobPage page = context.jobs()
                .page(
                        Optional.of(request.workspaceId()),
                        Optional.of(ID),
                        Set.of(),
                        ExtensionJobCursors.decode(query.cursor()),
                        query.limit());
        ViewQueryResult result = new ViewQueryResult(
                query.dataSourceId(),
                page.jobs().stream().map(this::jobRow).map(codec()::encode).toList(),
                codec().encode(Map.of()),
                page.nextCursor().map(ExtensionJobCursors::encode).orElse(""),
                page.nextCursor().isPresent(),
                page.jobs().stream().mapToLong(ExtensionJob::revision).max().orElse(0));
        return new ExtensionResponse(codec().encode(result), result.revision());
    }

    private JobViewRow jobRow(ExtensionJob job) {
        return new JobViewRow(job.id(), job.state(), job.definitionId(), job.definitionRevision(), job.updatedAt());
    }

    private void requireSubmissionRevision(
            String sourceId,
            long expectedRevision,
            long targetRevision,
            String jobId,
            ExtensionExecutionContext context)
            throws Exception {
        context.managedStore().inTransaction(ID, transaction -> {
            Optional<VersionedDocument> current =
                    transaction.get(KnowledgeCollections.sources(context.workspaceId()), sourceId);
            if (current.isEmpty() && expectedRevision == 0) {
                return null;
            }
            if (current.map(VersionedDocument::revision).orElse(-1L) == expectedRevision) {
                return null;
            }
            boolean completedReplay = current.filter(document -> document.revision() == targetRevision)
                    .map(VersionedDocument::payload)
                    .map(payload -> codec().decode(payload, KnowledgeContracts.Source.class))
                    .filter(source -> source.activeGenerationId().equals("generation-" + jobId))
                    .isPresent();
            if (completedReplay) {
                return null;
            }
            throw new IllegalArgumentException("Knowledge source revision changed");
        });
    }

    private void requireCurrentRevision(String sourceId, long expectedRevision, ExtensionExecutionContext context)
            throws Exception {
        context.managedStore().inTransaction(ID, transaction -> {
            long current = transaction
                    .get(KnowledgeCollections.sources(context.workspaceId()), sourceId)
                    .map(VersionedDocument::revision)
                    .orElse(0L);
            if (current != expectedRevision) {
                throw new IllegalArgumentException("Knowledge source revision changed");
            }
            return null;
        });
    }

    private DocumentPage page(
            ExtensionTransaction transaction, String collection, KnowledgeContracts.PageRequest page) {
        List<VersionedDocument> fetched = transaction.list(collection, page.afterKey(), page.limit() + 1);
        boolean hasMore = fetched.size() > page.limit();
        List<VersionedDocument> values = hasMore ? fetched.subList(0, page.limit()) : fetched;
        return new DocumentPage(
                values, hasMore && !values.isEmpty() ? values.getLast().key() : "");
    }

    private List<VersionedDocument> listAll(ExtensionTransaction transaction, String collection) {
        List<VersionedDocument> result = new ArrayList<>();
        String afterKey = "";
        while (true) {
            List<VersionedDocument> page = transaction.list(collection, afterKey, 500);
            result.addAll(page);
            if (page.size() < 500) {
                return List.copyOf(result);
            }
            afterKey = page.getLast().key();
        }
    }

    private <T> T decodeRequired(ExtensionTransaction transaction, String collection, String id, Class<T> type) {
        return transaction
                .get(collection, id)
                .map(VersionedDocument::payload)
                .map(payload -> codec().decode(payload, type))
                .orElseThrow(() -> new IllegalArgumentException("Knowledge resource does not exist"));
    }

    private static void requireViewRequest(String operation, ViewQueryRequest query) {
        String expected = operation.substring("view.".length());
        if (!expected.equals(query.dataSourceId()) || !query.arguments().isEmpty()) {
            throw new IllegalArgumentException("unknown Knowledge view data source");
        }
    }

    private synchronized ExtensionPayloadCodec codec() {
        if (payloads == null) {
            throw new IllegalStateException("extension is not started");
        }
        return payloads;
    }

    /** 清理启动期 codec；扩展本身不拥有线程或外部资源。 */
    @Override
    public synchronized void close() {
        payloads = null;
    }

    private record CommandDigest(String operation, long expectedRevision, CanonicalPayload payload) {}

    private record DocumentPage(List<VersionedDocument> values, String nextKey) {
        private DocumentPage {
            values = List.copyOf(values);
            nextKey = Objects.requireNonNull(nextKey, "nextKey");
        }
    }

    private record JobViewRow(
            String id,
            com.javaclaw.api.ExecutionState state,
            String definitionId,
            long definitionRevision,
            java.time.Instant updatedAt) {}
}
