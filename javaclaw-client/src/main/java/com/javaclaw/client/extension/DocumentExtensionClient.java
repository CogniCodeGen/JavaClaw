package com.javaclaw.client.extension;

import java.util.Objects;

import com.javaclaw.api.WorkspaceId;
import com.javaclaw.builtin.contracts.DocumentContracts;
import com.javaclaw.builtin.contracts.VersionedExtensionDocument;
import com.javaclaw.client.CommandOptions;

/**
 * 内置扩展版本化文档的强类型 SDK facade。
 *
 * @param <T> 扩展公开文档类型
 */
public final class DocumentExtensionClient<T extends VersionedExtensionDocument> {
    private final BuiltinClientCalls calls;
    private final Class<T> documentType;

    /**
     * 创建 typed facade。
     *
     * @param extensions 通用扩展客户端
     * @param extensionId 扩展标识
     * @param documentType 领域文档类型
     */
    public DocumentExtensionClient(ExtensionClient extensions, String extensionId, Class<T> documentType) {
        calls = new BuiltinClientCalls(
                Objects.requireNonNull(extensions, "extensions"), Objects.requireNonNull(extensionId, "extensionId"));
        this.documentType = Objects.requireNonNull(documentType, "documentType");
    }

    /**
     * 读取文档。
     *
     * @param workspaceId Workspace
     * @param id 文档标识
     * @return 当前文档
     */
    public T read(WorkspaceId workspaceId, String id) {
        String requestedId = Objects.requireNonNull(id, "id");
        T document = calls.query(workspaceId, "read", new DocumentContracts.Key(requestedId), documentType);
        if (!document.id().equals(requestedId)) {
            throw new IllegalStateException("extension document identity mismatch");
        }
        return document;
    }

    /**
     * 分页列出文档。
     *
     * @param workspaceId Workspace
     * @param afterKey 排他游标
     * @param limit 页大小
     * @return typed 文档页
     */
    public TypedDocumentPage<T> list(WorkspaceId workspaceId, String afterKey, int limit) {
        return calls.page(workspaceId, "list", new DocumentContracts.PageRequest(afterKey, limit), documentType);
    }

    /**
     * 条件删除文档。
     *
     * @param workspaceId Workspace
     * @param id 文档标识
     * @param options 幂等键与当前 revision
     * @return 删除确认
     */
    public DocumentContracts.Deleted delete(WorkspaceId workspaceId, String id, CommandOptions options) {
        String requestedId = Objects.requireNonNull(id, "id");
        CommandOptions checked = requirePositiveRevision(options, "document delete");
        DocumentContracts.Deleted deleted = calls.commandAtRevision(
                workspaceId,
                "delete",
                new DocumentContracts.Key(requestedId),
                checked,
                DocumentContracts.Deleted.class,
                Math.addExact(checked.expectedRevision(), 1));
        if (!deleted.id().equals(requestedId)) {
            throw new IllegalStateException("deleted document identity mismatch");
        }
        return deleted;
    }

    private static CommandOptions requirePositiveRevision(CommandOptions options, String operation) {
        CommandOptions checked = Objects.requireNonNull(options, "options");
        if (checked.expectedRevision() < 1) {
            throw new IllegalArgumentException(operation + " requires a positive expected revision");
        }
        return checked;
    }
}
