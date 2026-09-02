package com.javaclaw.builtin.extensions;

import java.util.List;
import java.util.Optional;

import com.javaclaw.api.WorkspaceId;
import com.javaclaw.builtin.contracts.DocumentContracts;
import com.javaclaw.builtin.contracts.ScheduleContracts;
import com.javaclaw.extension.spi.ExtensionExecutionContext;
import com.javaclaw.extension.spi.ExtensionJobRegistration;
import com.javaclaw.extension.spi.ExtensionJobRuntimeContext;
import com.javaclaw.extension.spi.ExtensionRequest;
import com.javaclaw.extension.spi.ExtensionResponse;
import com.javaclaw.extension.spi.ExtensionTransaction;
import com.javaclaw.extension.spi.VersionedDocument;

/** 协调 Schedule Definition 与 Timer 投影，保证 H2 文档始终是权威状态。 */
final class ScheduleDefinitionLifecycle implements ManagedDocumentBehavior<ScheduleContracts.Definition> {
    private static final String PROJECTION_COLLECTION = "projection-outbox.";

    private final ScheduleEngine engine = new ScheduleEngine();

    /** 绑定可恢复 Occurrence executor 与调度端口。 */
    List<ExtensionJobRegistration> jobExecutors(ExtensionJobRuntimeContext context) {
        engine.bindRuntime(context.payloads(), context.scheduledCommands(), context.scheduleLifecycle());
        return List.of(new ExtensionJobRegistration(
                ScheduleOccurrenceJobExecutor.JOB_TYPE, new ScheduleOccurrenceJobExecutor(context)));
    }

    /** 从权威 Definition 重建 Timer 投影并确认已应用的 Outbox。 */
    void restore(ManagedDocumentResource<ScheduleContracts.Definition> documents, ExtensionExecutionContext context)
            throws Exception {
        engine.restore(context, documents.documents(context));
        acknowledgeProjectionOutbox(documents, context);
    }

    /** 投递已经原子记录的 Occurrence。 */
    ScheduleContracts.Occurrence dispatchRecorded(
            ExtensionExecutionContext context, ScheduleContracts.Occurrence occurrence) throws Exception {
        return engine.dispatchRecorded(context, occurrence);
    }

    /** 校验强类型管理命令构造的 Definition 时间不变量。 */
    void validateManaged(ScheduleContracts.Definition definition) {
        ScheduleTimes.validate(definition.timing());
    }

    /** 在主文档同一事务中记录强类型管理写入的 Timer 投影。 */
    void afterManagedMutation(
            ManagedDocumentResource<ScheduleContracts.Definition> documents,
            WorkspaceId workspaceId,
            ScheduleContracts.Definition definition,
            ExtensionTransaction transaction) {
        recordProjection(
                documents, workspaceId, definition.id(), Optional.of(definition), definition.revision(), transaction);
    }

    /** 提交强类型管理写入后重建 Timer 投影并确认 Outbox。 */
    void afterManagedCommit(
            ManagedDocumentResource<ScheduleContracts.Definition> documents, ExtensionExecutionContext context)
            throws Exception {
        engine.changed(context, documents.documents(context));
        acknowledgeProjectionOutbox(documents, context);
    }

    @Override
    public void afterMutation(
            ManagedDocumentResource<ScheduleContracts.Definition> documents,
            ExtensionRequest request,
            ExtensionResponse response,
            ExtensionTransaction transaction) {
        ScheduleContracts.ProjectionChange change = projectionChange(documents, request, response);
        recordProjection(
                documents,
                change.workspaceId(),
                change.scheduleId(),
                change.definition(),
                change.sourceRevision(),
                transaction);
    }

    @Override
    public void afterCommit(
            ManagedDocumentResource<ScheduleContracts.Definition> documents,
            ExtensionRequest request,
            ExtensionResponse response,
            ExtensionExecutionContext context)
            throws Exception {
        engine.changed(context, documents.documents(context));
        acknowledgeProjectionOutbox(documents, context);
    }

    /** 关闭 Timer 投影拥有的资源。 */
    void close() {
        engine.close();
    }

    private static ScheduleContracts.ProjectionChange projectionChange(
            ManagedDocumentResource<ScheduleContracts.Definition> documents,
            ExtensionRequest request,
            ExtensionResponse response) {
        return switch (request.operation()) {
            case "delete" -> {
                DocumentContracts.Deleted deleted =
                        documents.payloads().decode(response.payload(), DocumentContracts.Deleted.class);
                yield new ScheduleContracts.ProjectionChange(
                        request.workspaceId(), deleted.id(), Optional.empty(), response.revision());
            }
            default -> throw new IllegalArgumentException("unknown Schedule document command");
        };
    }

    private static void recordProjection(
            ManagedDocumentResource<ScheduleContracts.Definition> documents,
            WorkspaceId workspaceId,
            String scheduleId,
            Optional<ScheduleContracts.Definition> definition,
            long sourceRevision,
            ExtensionTransaction transaction) {
        ScheduleContracts.ProjectionChange change =
                new ScheduleContracts.ProjectionChange(workspaceId, scheduleId, definition, sourceRevision);
        String key = change.scheduleId() + "-" + String.format("%020d", change.sourceRevision());
        transaction.put(
                projectionCollection(workspaceId), key, 0, documents.payloads().encode(change));
    }

    private static void acknowledgeProjectionOutbox(
            ManagedDocumentResource<ScheduleContracts.Definition> documents, ExtensionExecutionContext context)
            throws Exception {
        context.managedStore().inTransaction(documents.extensionId(), transaction -> {
            String collection = projectionCollection(context.workspaceId());
            String cursor = "";
            while (true) {
                List<VersionedDocument> page = transaction.list(collection, cursor, 500);
                for (VersionedDocument document : page) {
                    transaction.delete(collection, document.key(), document.revision());
                }
                if (page.size() < 500) {
                    return null;
                }
                cursor = page.getLast().key();
            }
        });
    }

    private static String projectionCollection(WorkspaceId workspaceId) {
        return PROJECTION_COLLECTION + workspaceId;
    }
}
