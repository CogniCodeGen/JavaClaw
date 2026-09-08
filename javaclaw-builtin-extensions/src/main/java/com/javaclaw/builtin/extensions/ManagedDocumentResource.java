package com.javaclaw.builtin.extensions;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.ToolDescriptor;
import com.javaclaw.api.ToolRisk;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.builtin.contracts.DocumentContracts;
import com.javaclaw.builtin.contracts.VersionedExtensionDocument;
import com.javaclaw.extension.spi.ContributionKind;
import com.javaclaw.extension.spi.ExtensionAvailability;
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
import com.javaclaw.extension.spi.ViewQueryRequest;
import com.javaclaw.extension.spi.ViewQueryResult;

/** 用托管事务实现强类型文档读写的组合资源。 */
final class ManagedDocumentResource<T extends VersionedExtensionDocument> {
    private static final String COLLECTION_PREFIX = "documents.";

    private final ExtensionDescriptor descriptor;
    private final Class<T> documentType;
    private final ManagedDocumentPresentation presentation;
    private final ManagedDocumentBehavior<T> behavior;
    private ExtensionPayloadCodec payloads;

    ManagedDocumentResource(String id, String displayName, Class<T> documentType) {
        this(
                id,
                displayName,
                documentType,
                Set.of(),
                BuiltinStoragePermission.create(id),
                ManagedDocumentBehavior.none());
    }

    ManagedDocumentResource(
            String id, String displayName, Class<T> documentType, Set<ContributionKind> additionalKinds) {
        this(
                id,
                displayName,
                documentType,
                additionalKinds,
                BuiltinStoragePermission.create(id),
                ManagedDocumentBehavior.none());
    }

    ManagedDocumentResource(
            String id,
            String displayName,
            Class<T> documentType,
            Set<ContributionKind> additionalKinds,
            PermissionProfile permissionCeiling,
            ManagedDocumentBehavior<T> behavior) {
        this.documentType = Objects.requireNonNull(documentType, "documentType");
        this.behavior = Objects.requireNonNull(behavior, "behavior");
        Set<ContributionKind> kinds = new HashSet<>(
                Set.of(ContributionKind.QUERY, ContributionKind.COMMAND, ContributionKind.VIEW, ContributionKind.TOOL));
        kinds.addAll(Set.copyOf(additionalKinds));
        descriptor = new ExtensionDescriptor(
                new ExtensionId(id),
                displayName,
                "5.0.0",
                1,
                Set.copyOf(kinds),
                new ExtensionRequirements(
                        ExtensionTrust.BUILT_IN,
                        ExtensionAvailability.OPTIONAL,
                        2,
                        Objects.requireNonNull(permissionCeiling, "permissionCeiling")));
        presentation = new ManagedDocumentPresentation(descriptor);
    }

    ExtensionDescriptor descriptor() {
        return descriptor;
    }

    synchronized List<ExtensionContribution> start(ExtensionContext context) {
        return start(context, Set.of("put", "delete"), true);
    }

    /**
     * 启动只允许强类型管理命令写入的文档资源。
     *
     * <p>通用 {@code put} 不会发布，避免客户端伪造 revision、时间或内容摘要；删除仍复用托管文档事务。
     */
    synchronized List<ExtensionContribution> startWithManagedWrites(ExtensionContext context) {
        return start(context, Set.of("delete"), false);
    }

    /**
     * 启动包含敏感引用、只能由领域投影读取的文档资源。
     *
     * <p>该模式只发布通用删除命令并初始化 codec。领域扩展必须自行发布脱敏 query、View 与 Tool，避免完整持久对象越过安全边界。
     */
    synchronized List<ExtensionContribution> startWithManagedWrites(
            ExtensionContext context, boolean publishPublicDocuments) {
        if (publishPublicDocuments) {
            return startWithManagedWrites(context);
        }
        if (payloads != null) {
            throw new IllegalStateException("extension is already started");
        }
        payloads = Objects.requireNonNull(context, "context").payloads();
        return List.of(new ExtensionContributions.Command("documents.command", Set.of("delete"), this::command));
    }

    private List<ExtensionContribution> start(
            ExtensionContext context, Set<String> documentCommands, boolean publishGenericView) {
        if (payloads != null) {
            throw new IllegalStateException("extension is already started");
        }
        payloads = Objects.requireNonNull(context, "context").payloads();
        List<ExtensionContribution> contributions = new ArrayList<>();
        contributions.add(
                new ExtensionContributions.Query("documents.query", Set.of("read", "list", "view.list"), this::query));
        contributions.add(new ExtensionContributions.Command("documents.command", documentCommands, this::command));
        contributions.add(new ExtensionContributions.Tool(
                "documents.read.tool",
                readOnlyTool(
                        "read",
                        "按标识读取一个" + descriptor.displayName() + "文档",
                        presentation.keySchema(payloads()),
                        Set.of()),
                this::read));
        contributions.add(new ExtensionContributions.Tool(
                "documents.list.tool",
                readOnlyTool(
                        "list",
                        "分页列出" + descriptor.displayName() + "文档",
                        presentation.pageSchema(payloads()),
                        Set.of()),
                this::list));
        if (publishGenericView) {
            contributions.add(new ExtensionContributions.View("documents.view", presentation.view()));
        }
        return List.copyOf(contributions);
    }

    List<ExtensionSchema> schemas() {
        return List.of(presentation.documentSchema(requireStarted()));
    }

    private ExtensionResponse query(ExtensionRequest request, ExtensionExecutionContext context) throws Exception {
        requireStarted();
        return switch (request.operation()) {
            case "read" -> read(request, context);
            case "list" -> list(request, context);
            case "view.list" -> viewList(request, context);
            default -> throw new IllegalArgumentException("unknown query operation: " + request.operation());
        };
    }

    private ExtensionResponse command(ExtensionRequest request, ExtensionExecutionContext context) throws Exception {
        ExtensionPayloadCodec codec = requireStarted();
        behavior.validate(this, request, context);
        String idempotencyKey = request.idempotencyKey()
                .orElseThrow(() -> new IllegalArgumentException("extension command requires idempotency key"));
        CanonicalPayload digest = commandDigest(request, codec);
        ExtensionResponse response = context.managedStore()
                .inCommand(
                        descriptor.id(),
                        request.operation(),
                        idempotencyKey,
                        digest.sha256(),
                        transaction -> mutate(request, transaction));
        behavior.afterCommit(this, request, response, context);
        return response;
    }

    private ExtensionResponse mutate(ExtensionRequest request, ExtensionTransaction transaction) {
        ExtensionResponse response =
                switch (request.operation()) {
                    case "put" -> put(request, transaction);
                    case "delete" -> delete(request, transaction);
                    default -> throw new IllegalArgumentException("unknown command operation: " + request.operation());
                };
        behavior.afterMutation(this, request, response, transaction);
        return response;
    }

    private ExtensionResponse read(ExtensionRequest request, ExtensionExecutionContext context) throws Exception {
        DocumentContracts.Key key = requireStarted().decode(request.payload(), DocumentContracts.Key.class);
        VersionedDocument document = context.managedStore()
                .inTransaction(descriptor.id(), transaction -> transaction.get(collection(request), key.id()))
                .orElseThrow(() -> new IllegalArgumentException("extension document does not exist"));
        requireStarted().decode(document.payload(), documentType);
        return new ExtensionResponse(document.payload(), document.revision());
    }

    private ExtensionResponse list(ExtensionRequest request, ExtensionExecutionContext context) throws Exception {
        ExtensionPayloadCodec codec = requireStarted();
        DocumentContracts.PageRequest page = codec.decode(request.payload(), DocumentContracts.PageRequest.class);
        List<VersionedDocument> documents = context.managedStore()
                .inTransaction(
                        descriptor.id(),
                        transaction -> transaction.list(collection(request), page.afterKey(), page.limit()));
        documents.forEach(document -> codec.decode(document.payload(), documentType));
        String nextKey =
                documents.isEmpty() ? page.afterKey() : documents.getLast().key();
        DocumentContracts.Page result = new DocumentContracts.Page(
                documents.stream().map(VersionedDocument::payload).toList(), nextKey);
        return new ExtensionResponse(codec.encode(result), 0);
    }

    private ExtensionResponse viewList(ExtensionRequest request, ExtensionExecutionContext context) throws Exception {
        ExtensionPayloadCodec codec = requireStarted();
        ViewQueryRequest query = codec.decode(request.payload(), ViewQueryRequest.class);
        if (!"documents".equals(query.dataSourceId()) || !query.arguments().isEmpty()) {
            throw new IllegalArgumentException("unknown document view data source");
        }
        List<VersionedDocument> fetched = context.managedStore()
                .inTransaction(
                        descriptor.id(),
                        transaction ->
                                transaction.list(collection(request), query.cursor(), Math.addExact(query.limit(), 1)));
        boolean hasMore = fetched.size() > query.limit();
        List<VersionedDocument> page = hasMore ? fetched.subList(0, query.limit()) : fetched;
        String nextCursor = hasMore && !page.isEmpty() ? page.getLast().key() : "";
        ViewQueryResult result = new ViewQueryResult(
                query.dataSourceId(),
                page.stream().map(VersionedDocument::payload).toList(),
                codec.encode(Map.of()),
                nextCursor,
                hasMore,
                0);
        return new ExtensionResponse(codec.encode(result), 0);
    }

    private ExtensionResponse put(ExtensionRequest request, ExtensionTransaction transaction) {
        ExtensionPayloadCodec codec = requireStarted();
        T document = codec.decode(request.payload(), documentType);
        long expectedDocumentRevision = Math.addExact(request.expectedRevision(), 1);
        if (document.revision() != expectedDocumentRevision) {
            throw new IllegalArgumentException("document revision must equal expected revision plus one");
        }
        long revision =
                transaction.put(collection(request), document.id(), request.expectedRevision(), request.payload());
        return new ExtensionResponse(request.payload(), revision);
    }

    /**
     * 读取当前 Workspace 的全部强类型文档，供启动恢复使用。
     *
     * @param context Workspace 级上下文
     * @return 按稳定键排序的文档
     * @throws Exception 托管存储失败
     */
    List<T> documents(ExtensionExecutionContext context) throws Exception {
        List<T> result = new ArrayList<>();
        String afterKey = "";
        while (true) {
            context.cancellation().throwIfCancelled();
            String cursor = afterKey;
            List<VersionedDocument> page = context.managedStore()
                    .inTransaction(
                            descriptor.id(),
                            transaction -> transaction.list(collection(context.workspaceId()), cursor, 500));
            page.stream()
                    .map(document -> requireStarted().decode(document.payload(), documentType))
                    .forEach(result::add);
            if (page.size() < 500) {
                return List.copyOf(result);
            }
            afterKey = page.getLast().key();
        }
    }

    /** 读取并校验编排目标的精确版本。 */
    T requireDocument(ExtensionRequest request, String id, ExtensionExecutionContext context) throws Exception {
        return requireDocument(id, request.expectedRevision(), context);
    }

    /** 读取并校验当前 Workspace 文档的精确版本。 */
    T requireDocument(String id, long expectedRevision, ExtensionExecutionContext context) throws Exception {
        VersionedDocument document = context.managedStore()
                .inTransaction(descriptor.id(), transaction -> transaction.get(collection(context.workspaceId()), id))
                .orElseThrow(() -> new IllegalArgumentException("extension document does not exist"));
        T decoded = requireStarted().decode(document.payload(), documentType);
        if (document.revision() != expectedRevision || decoded.revision() != expectedRevision) {
            throw new IllegalArgumentException("extension document revision changed");
        }
        return decoded;
    }

    /** 返回启动期共享 payload codec。 */
    ExtensionPayloadCodec payloads() {
        return requireStarted();
    }

    /** 返回当前扩展标识。 */
    ExtensionId extensionId() {
        return descriptor.id();
    }

    /** 返回托管文档的强类型解码类型，仅供同扩展管理边界使用。 */
    Class<T> documentType() {
        return documentType;
    }

    /** 创建一个只读工具描述，并固定 producer 与 Bundle revision。 */
    ToolDescriptor readOnlyTool(
            String operation, String description, CanonicalPayload inputSchema, Set<String> additionalTags) {
        return presentation.readOnlyTool(requireStarted(), operation, description, inputSchema, additionalTags);
    }

    /** 创建一个受风险、审批和实时撤权治理的工具描述。 */
    ToolDescriptor governedTool(
            String operation,
            String description,
            CanonicalPayload inputSchema,
            ToolRisk risk,
            Set<String> additionalTags) {
        return presentation.governedTool(requireStarted(), operation, description, inputSchema, risk, additionalTags);
    }

    private ExtensionResponse delete(ExtensionRequest request, ExtensionTransaction transaction) {
        if (request.expectedRevision() < 1) {
            throw new IllegalArgumentException("delete expected revision must be positive");
        }
        ExtensionPayloadCodec codec = requireStarted();
        DocumentContracts.Key key = codec.decode(request.payload(), DocumentContracts.Key.class);
        behavior.deleteRelated(this, request, key.id(), transaction);
        transaction.delete(collection(request), key.id(), request.expectedRevision());
        return new ExtensionResponse(
                codec.encode(new DocumentContracts.Deleted(key.id())), Math.addExact(request.expectedRevision(), 1));
    }

    private synchronized ExtensionPayloadCodec requireStarted() {
        if (payloads == null) {
            throw new IllegalStateException("extension is not started");
        }
        return payloads;
    }

    private static CanonicalPayload commandDigest(ExtensionRequest request, ExtensionPayloadCodec codec) {
        return codec.encode(new CommandDigest(request.operation(), request.expectedRevision(), request.payload()));
    }

    private static String collection(ExtensionRequest request) {
        return collection(request.workspaceId());
    }

    private static String collection(WorkspaceId workspaceId) {
        return COLLECTION_PREFIX + workspaceId;
    }

    /**
     * 返回指定 Workspace 的主文档集合名，供需要原子提交派生记录的子类使用。
     *
     * @param workspaceId Workspace
     * @return 扩展私有集合名
     */
    String documentCollection(WorkspaceId workspaceId) {
        return collection(workspaceId);
    }

    /** 丢弃启动期端口；组合资源不拥有领域 Worker 或 Timer。 */
    synchronized void close() {
        payloads = null;
    }

    private record CommandDigest(String operation, long expectedRevision, CanonicalPayload payload) {}
}
