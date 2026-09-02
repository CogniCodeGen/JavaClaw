package com.javaclaw.client.extension;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

import com.javaclaw.api.ThreadId;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.builtin.contracts.DocumentContracts;
import com.javaclaw.builtin.contracts.OrchestrationContracts;
import com.javaclaw.builtin.contracts.VersionedExtensionDocument;
import com.javaclaw.client.CommandOptions;
import com.javaclaw.extension.spi.ExtensionExecutionReceipt;

/** 自动化 Definition 强类型 facade 共用的 CRUD 与非阻塞启动实现。 */
final class AutomationClientSupport<T extends VersionedExtensionDocument, S> {
    private static final String DEFINITION_EXECUTION_JOB_TYPE = "definition-execution";

    private final DocumentExtensionClient<T> definitions;
    private final BuiltinClientCalls calls;
    private final String extensionId;
    private final Class<T> definitionType;
    private final String createOperation;
    private final String updateOperation;
    private final Function<S, String> requestId;

    AutomationClientSupport(
            ExtensionClient extensions,
            String extensionId,
            Class<T> definitionType,
            String createOperation,
            String updateOperation,
            Function<S, String> requestId) {
        ExtensionClient checkedExtensions = Objects.requireNonNull(extensions, "extensions");
        this.extensionId = Objects.requireNonNull(extensionId, "extensionId");
        this.definitionType = Objects.requireNonNull(definitionType, "definitionType");
        this.createOperation = Objects.requireNonNull(createOperation, "createOperation");
        this.updateOperation = Objects.requireNonNull(updateOperation, "updateOperation");
        this.requestId = Objects.requireNonNull(requestId, "requestId");
        definitions = new DocumentExtensionClient<>(checkedExtensions, extensionId, definitionType);
        calls = new BuiltinClientCalls(checkedExtensions, extensionId);
    }

    T read(WorkspaceId workspaceId, String id) {
        return definitions.read(workspaceId, id);
    }

    TypedDocumentPage<T> list(WorkspaceId workspaceId, String afterKey, int limit) {
        return definitions.list(workspaceId, afterKey, limit);
    }

    T create(WorkspaceId workspaceId, S request, CommandOptions options) {
        CommandOptions checked = requireRevision(options, true);
        return save(workspaceId, createOperation, request, checked);
    }

    T update(WorkspaceId workspaceId, S request, CommandOptions options) {
        CommandOptions checked = requireRevision(options, false);
        return save(workspaceId, updateOperation, request, checked);
    }

    DocumentContracts.Deleted delete(WorkspaceId workspaceId, String id, CommandOptions options) {
        return definitions.delete(workspaceId, id, options);
    }

    ExtensionExecutionReceipt start(
            WorkspaceId workspaceId,
            Optional<ThreadId> parentThreadId,
            OrchestrationContracts.StartRequest request,
            CommandOptions options) {
        WorkspaceId checkedWorkspace = Objects.requireNonNull(workspaceId, "workspaceId");
        OrchestrationContracts.StartRequest checkedRequest = Objects.requireNonNull(request, "request");
        CommandOptions checkedOptions = requirePositiveRevision(options, "execution start");
        ExtensionExecutionReceipt receipt = calls.commandAtRevision(
                checkedWorkspace,
                Objects.requireNonNull(parentThreadId, "parentThreadId"),
                "execution/start",
                checkedRequest,
                checkedOptions,
                ExtensionExecutionReceipt.class,
                1);
        requireExecutionOwner(receipt, checkedWorkspace);
        if (!receipt.definitionId().equals(checkedRequest.definitionId())
                || receipt.definitionRevision() != checkedOptions.expectedRevision()) {
            throw new IllegalStateException("execution definition revision mismatch");
        }
        return receipt;
    }

    ExtensionExecutionReceipt mutateExecution(
            WorkspaceId workspaceId, String operation, Object request, String jobId, CommandOptions options) {
        WorkspaceId checkedWorkspace = Objects.requireNonNull(workspaceId, "workspaceId");
        CommandOptions checkedOptions = requirePositiveRevision(options, operation);
        ExtensionExecutionReceipt receipt = calls.commandAtRevision(
                checkedWorkspace,
                Objects.requireNonNull(operation, "operation"),
                Objects.requireNonNull(request, "request"),
                checkedOptions,
                ExtensionExecutionReceipt.class,
                Math.addExact(checkedOptions.expectedRevision(), 1));
        requireExecutionOwner(receipt, checkedWorkspace);
        if (!receipt.id().equals(Objects.requireNonNull(jobId, "jobId"))) {
            throw new IllegalStateException("execution receipt Job identity mismatch");
        }
        return receipt;
    }

    private T save(WorkspaceId workspaceId, String operation, S request, CommandOptions options) {
        S checkedRequest = Objects.requireNonNull(request, "request");
        T definition = calls.commandAtRevision(
                workspaceId,
                operation,
                checkedRequest,
                options,
                definitionType,
                Math.addExact(options.expectedRevision(), 1));
        if (!definition.id().equals(requestId.apply(checkedRequest))) {
            throw new IllegalStateException("saved definition identity mismatch");
        }
        return definition;
    }

    private void requireExecutionOwner(ExtensionExecutionReceipt receipt, WorkspaceId workspaceId) {
        if (!receipt.extensionId().value().equals(extensionId)
                || !receipt.workspaceId().equals(workspaceId)
                || !receipt.jobType().equals(DEFINITION_EXECUTION_JOB_TYPE)) {
            throw new IllegalStateException("execution receipt owner mismatch");
        }
    }

    private static CommandOptions requireRevision(CommandOptions options, boolean creating) {
        CommandOptions checked = Objects.requireNonNull(options, "options");
        if (creating && checked.expectedRevision() != 0) {
            throw new IllegalArgumentException("Definition create requires expected revision zero");
        }
        if (!creating && checked.expectedRevision() < 1) {
            throw new IllegalArgumentException("Definition update requires a positive expected revision");
        }
        return checked;
    }

    private static CommandOptions requirePositiveRevision(CommandOptions options, String operation) {
        CommandOptions checked = Objects.requireNonNull(options, "options");
        if (checked.expectedRevision() < 1) {
            throw new IllegalArgumentException(operation + " requires a positive expected revision");
        }
        return checked;
    }
}
