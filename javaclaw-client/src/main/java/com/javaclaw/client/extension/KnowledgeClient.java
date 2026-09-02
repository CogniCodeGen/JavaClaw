package com.javaclaw.client.extension;

import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.WorkspaceId;
import com.javaclaw.builtin.contracts.BuiltinExtensionIds;
import com.javaclaw.builtin.contracts.DocumentContracts;
import com.javaclaw.builtin.contracts.KnowledgeContracts;
import com.javaclaw.client.CommandOptions;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.ExtensionRpcContracts;

/** Knowledge 强类型 SDK facade；导入只接受已经上传的 Core Attachment 引用。 */
public final class KnowledgeClient {
    private final ExtensionClient extensions;
    private final CanonicalJson json = new CanonicalJson();

    /**
     * 创建 Knowledge facade。
     *
     * @param extensions 通用扩展客户端
     */
    public KnowledgeClient(ExtensionClient extensions) {
        this.extensions = Objects.requireNonNull(extensions, "extensions");
    }

    /**
     * 读取当前来源。
     *
     * @param workspaceId Workspace
     * @param id 来源标识
     * @return 当前来源
     */
    public KnowledgeContracts.Source readSource(WorkspaceId workspaceId, String id) {
        return query(workspaceId, "source/read", new KnowledgeContracts.Key(id), KnowledgeContracts.Source.class);
    }

    /**
     * 分页列出当前来源。
     *
     * @param workspaceId Workspace
     * @param request 分页条件
     * @return 来源页
     */
    public KnowledgeContracts.SourcePage listSources(WorkspaceId workspaceId, KnowledgeContracts.PageRequest request) {
        return query(workspaceId, "source/list", request, KnowledgeContracts.SourcePage.class);
    }

    /**
     * 读取不可变 Generation。
     *
     * @param workspaceId Workspace
     * @param id Generation 标识
     * @return Generation
     */
    public KnowledgeContracts.Generation readGeneration(WorkspaceId workspaceId, String id) {
        return query(
                workspaceId, "generation/read", new KnowledgeContracts.Key(id), KnowledgeContracts.Generation.class);
    }

    /**
     * 分页列出已完成 Generation。
     *
     * @param workspaceId Workspace
     * @param request 分页条件
     * @return Generation 页
     */
    public KnowledgeContracts.GenerationPage listGenerations(
            WorkspaceId workspaceId, KnowledgeContracts.PageRequest request) {
        return query(workspaceId, "generation/list", request, KnowledgeContracts.GenerationPage.class);
    }

    /**
     * 提交 Attachment 解析与索引 Job；方法立即返回，不等待 Worker 或 Provider。
     *
     * @param workspaceId Workspace
     * @param request Attachment 与分块配置
     * @param options 幂等键及来源当前 revision；创建来源时 revision 为 0
     * @return 已持久化 Job 引用
     */
    public KnowledgeContracts.ImportAccepted importSource(
            WorkspaceId workspaceId, KnowledgeContracts.ImportRequest request, CommandOptions options) {
        KnowledgeContracts.ImportRequest checkedRequest = Objects.requireNonNull(request, "request");
        CommandOptions checkedOptions = Objects.requireNonNull(options, "options");
        ExtensionRpcContracts.CallResult result =
                extensions.command(call(workspaceId, "source/import", checkedRequest), checkedOptions);
        KnowledgeContracts.ImportAccepted accepted =
                json.decode(result.payload(), KnowledgeContracts.ImportAccepted.class);
        long targetRevision = Math.addExact(checkedOptions.expectedRevision(), 1);
        if (!accepted.sourceId().equals(checkedRequest.id())
                || accepted.targetSourceRevision() != targetRevision
                || result.revision() != targetRevision) {
            throw new IllegalStateException("Knowledge import response differs from submitted source revision");
        }
        return accepted;
    }

    /**
     * 删除当前来源；历史 Generation 仍保留为只读审计记录。
     *
     * @param workspaceId Workspace
     * @param id 来源标识
     * @param options 幂等键及来源当前 revision
     * @return 删除结果
     */
    public DocumentContracts.Deleted deleteSource(WorkspaceId workspaceId, String id, CommandOptions options) {
        ExtensionRpcContracts.CallResult result =
                extensions.command(call(workspaceId, "source/delete", new KnowledgeContracts.Key(id)), options);
        return json.decode(result.payload(), DocumentContracts.Deleted.class);
    }

    /**
     * 检索当前已激活 Generation。
     *
     * @param workspaceId Workspace
     * @param request 检索条件
     * @return 检索结果和本次 Embedding 降级标记
     */
    public KnowledgeContracts.SearchResult search(WorkspaceId workspaceId, KnowledgeContracts.SearchRequest request) {
        return query(workspaceId, "search", request, KnowledgeContracts.SearchResult.class);
    }

    private <T> T query(WorkspaceId workspaceId, String operation, Object payload, Class<T> type) {
        ExtensionRpcContracts.CallResult result = extensions.query(call(workspaceId, operation, payload));
        return json.decode(result.payload(), type);
    }

    private ExtensionRpcContracts.CallPayload call(WorkspaceId workspaceId, String operation, Object payload) {
        return new ExtensionRpcContracts.CallPayload(
                BuiltinExtensionIds.KNOWLEDGE,
                Objects.requireNonNull(workspaceId, "workspaceId"),
                Optional.empty(),
                Optional.empty(),
                operation,
                json.encode(payload));
    }
}
