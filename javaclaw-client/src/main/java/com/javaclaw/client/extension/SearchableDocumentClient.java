package com.javaclaw.client.extension;

import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.WorkspaceId;
import com.javaclaw.builtin.contracts.DocumentContracts;
import com.javaclaw.builtin.contracts.VersionedExtensionDocument;
import com.javaclaw.client.CommandOptions;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.ExtensionRpcContracts;

/**
 * 同时提供版本化 CRUD 与领域检索的内置扩展 facade。
 *
 * @param <T> 文档类型
 * @param <Q> 检索条件类型
 * @param <R> 检索结果类型
 */
public final class SearchableDocumentClient<T extends VersionedExtensionDocument, Q, R> {
    private final DocumentExtensionClient<T> documents;
    private final ExtensionClient extensions;
    private final String extensionId;
    private final Class<R> resultType;
    private final CanonicalJson json = new CanonicalJson();

    /**
     * 创建领域 facade。
     *
     * @param extensions 通用扩展客户端
     * @param extensionId 扩展标识
     * @param documentType 文档类型
     * @param resultType 检索结果类型
     */
    public SearchableDocumentClient(
            ExtensionClient extensions, String extensionId, Class<T> documentType, Class<R> resultType) {
        this.extensions = Objects.requireNonNull(extensions, "extensions");
        this.extensionId = Objects.requireNonNull(extensionId, "extensionId");
        this.resultType = Objects.requireNonNull(resultType, "resultType");
        documents = new DocumentExtensionClient<>(extensions, extensionId, documentType);
    }

    /** 按标识读取文档。 */
    public T read(WorkspaceId workspaceId, String id) {
        return documents.read(workspaceId, id);
    }

    /** 分页列出文档。 */
    public TypedDocumentPage<T> list(WorkspaceId workspaceId, String afterKey, int limit) {
        return documents.list(workspaceId, afterKey, limit);
    }

    /** 条件删除文档。 */
    public DocumentContracts.Deleted delete(WorkspaceId workspaceId, String id, CommandOptions options) {
        return documents.delete(workspaceId, id, options);
    }

    /**
     * 执行当前领域的无副作用检索。
     *
     * @param workspaceId Workspace
     * @param request 强类型检索条件
     * @return 强类型结果
     */
    public R search(WorkspaceId workspaceId, Q request) {
        Objects.requireNonNull(request, "request");
        ExtensionRpcContracts.CallPayload call = new ExtensionRpcContracts.CallPayload(
                extensionId,
                Objects.requireNonNull(workspaceId, "workspaceId"),
                Optional.empty(),
                Optional.empty(),
                "search",
                json.encode(request));
        return json.decode(extensions.query(call).payload(), resultType);
    }
}
