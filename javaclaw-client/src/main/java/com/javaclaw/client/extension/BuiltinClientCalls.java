package com.javaclaw.client.extension;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.ThreadId;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.builtin.contracts.DocumentContracts;
import com.javaclaw.builtin.contracts.VersionedExtensionDocument;
import com.javaclaw.client.CommandOptions;
import com.javaclaw.extension.spi.ExtensionExecutionReceipt;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.ExtensionRpcContracts;

/** 内置强类型 facade 共用的 Extension 调用与 revision 校验。 */
final class BuiltinClientCalls {
    private final ExtensionClient extensions;
    private final String extensionId;
    private final CanonicalJson json = new CanonicalJson();

    BuiltinClientCalls(ExtensionClient extensions, String extensionId) {
        this.extensions = Objects.requireNonNull(extensions, "extensions");
        this.extensionId = Objects.requireNonNull(extensionId, "extensionId");
    }

    <T> T query(WorkspaceId workspaceId, String operation, Object request, Class<T> resultType) {
        return query(workspaceId, Optional.empty(), Optional.empty(), operation, request, resultType);
    }

    <T> T queryAtRevision(
            WorkspaceId workspaceId,
            String operation,
            Object request,
            Class<T> resultType,
            long expectedResponseRevision) {
        ExtensionRpcContracts.CallResult result =
                extensions.query(call(workspaceId, Optional.empty(), Optional.empty(), operation, request));
        return decodeAtRevision(result, resultType, expectedResponseRevision);
    }

    <T> T query(
            WorkspaceId workspaceId,
            Optional<ThreadId> threadId,
            Optional<TurnId> turnId,
            String operation,
            Object request,
            Class<T> resultType) {
        ExtensionRpcContracts.CallResult result =
                extensions.query(call(workspaceId, threadId, turnId, operation, request));
        return decode(result, resultType);
    }

    <T> T command(
            WorkspaceId workspaceId, String operation, Object request, CommandOptions options, Class<T> resultType) {
        ExtensionRpcContracts.CallResult result = extensions.command(
                call(workspaceId, Optional.empty(), Optional.empty(), operation, request),
                Objects.requireNonNull(options, "options"));
        return decode(result, resultType);
    }

    <T> T commandAtRevision(
            WorkspaceId workspaceId,
            String operation,
            Object request,
            CommandOptions options,
            Class<T> resultType,
            long expectedResponseRevision) {
        return commandAtRevision(
                workspaceId, Optional.empty(), operation, request, options, resultType, expectedResponseRevision);
    }

    <T> T commandAtRevision(
            WorkspaceId workspaceId,
            Optional<ThreadId> threadId,
            String operation,
            Object request,
            CommandOptions options,
            Class<T> resultType,
            long expectedResponseRevision) {
        ExtensionRpcContracts.CallResult result = extensions.command(
                call(workspaceId, threadId, Optional.empty(), operation, request),
                Objects.requireNonNull(options, "options"));
        return decodeAtRevision(result, resultType, expectedResponseRevision);
    }

    <T> TypedDocumentPage<T> page(WorkspaceId workspaceId, String operation, Object request, Class<T> documentType) {
        DocumentContracts.Page page = queryAtRevision(workspaceId, operation, request, DocumentContracts.Page.class, 0);
        List<T> values = page.documents().stream()
                .map(payload -> json.decode(payload, documentType))
                .toList();
        return new TypedDocumentPage<>(values, page.nextKey());
    }

    private ExtensionRpcContracts.CallPayload call(
            WorkspaceId workspaceId,
            Optional<ThreadId> threadId,
            Optional<TurnId> turnId,
            String operation,
            Object request) {
        return new ExtensionRpcContracts.CallPayload(
                extensionId,
                Objects.requireNonNull(workspaceId, "workspaceId"),
                Objects.requireNonNull(threadId, "threadId"),
                Objects.requireNonNull(turnId, "turnId"),
                Objects.requireNonNull(operation, "operation"),
                json.encode(Objects.requireNonNull(request, "request")));
    }

    private <T> T decode(ExtensionRpcContracts.CallResult result, Class<T> resultType) {
        T value = json.decode(result.payload(), Objects.requireNonNull(resultType, "resultType"));
        if (value instanceof VersionedExtensionDocument document && document.revision() != result.revision()) {
            throw new IllegalStateException("extension result revision mismatch");
        }
        if (value instanceof ExtensionExecutionReceipt receipt && receipt.revision() != result.revision()) {
            throw new IllegalStateException("extension execution revision mismatch");
        }
        return value;
    }

    private <T> T decodeAtRevision(
            ExtensionRpcContracts.CallResult result, Class<T> resultType, long expectedResponseRevision) {
        if (expectedResponseRevision < 0) {
            throw new IllegalArgumentException("expectedResponseRevision must not be negative");
        }
        T value = decode(result, resultType);
        if (result.revision() != expectedResponseRevision) {
            throw new IllegalStateException("extension response revision mismatch");
        }
        return value;
    }
}
