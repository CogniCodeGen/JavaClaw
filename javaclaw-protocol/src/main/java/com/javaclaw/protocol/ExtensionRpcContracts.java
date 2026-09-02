package com.javaclaw.protocol;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.WorkspaceId;

/** Protocol v2 Extension 通用方法的 wire 契约。 */
public final class ExtensionRpcContracts {
    private ExtensionRpcContracts() {}

    /**
     * Extension query 或 command 的业务 payload。
     *
     * @param extensionId 扩展标识
     * @param workspaceId Workspace
     * @param threadId 可选 Thread
     * @param turnId 可选 Turn
     * @param operation 扩展内操作
     * @param payload 规范参数
     */
    public record CallPayload(
            String extensionId,
            WorkspaceId workspaceId,
            Optional<ThreadId> threadId,
            Optional<TurnId> turnId,
            String operation,
            CanonicalPayload payload) {
        /** 校验调用。 */
        public CallPayload {
            extensionId = text(extensionId, "extensionId");
            Objects.requireNonNull(workspaceId, "workspaceId");
            threadId = Objects.requireNonNull(threadId, "threadId");
            turnId = Objects.requireNonNull(turnId, "turnId");
            operation = text(operation, "operation");
            Objects.requireNonNull(payload, "payload");
        }
    }

    /**
     * Extension 调用结果。
     *
     * @param payload 规范结果
     * @param revision 资源版本；无版本查询为 0
     */
    public record CallResult(CanonicalPayload payload, long revision) {
        /** 校验结果。 */
        public CallResult {
            Objects.requireNonNull(payload, "payload");
            if (revision < 0) {
                throw new IllegalArgumentException("revision must not be negative");
            }
        }
    }

    /**
     * 提醒客户端重新读取扩展权威状态的失效通知。
     *
     * <p>该通知只携带定位资源所需的标识和版本，禁止承载业务正文、Secret 或命令 payload。
     *
     * @param workspaceId 资源所属 Workspace
     * @param extensionId 扩展标识
     * @param scope 扩展内稳定资源类别
     * @param resourceId 资源类别内的稳定标识
     * @param operation 导致状态失效的扩展命令名
     * @param revision 已提交的权威资源版本
     */
    public record ExtensionEvent(
            WorkspaceId workspaceId,
            String extensionId,
            String scope,
            String resourceId,
            String operation,
            long revision) {
        /** 校验通知只指向一个已经提交的资源版本。 */
        public ExtensionEvent {
            Objects.requireNonNull(workspaceId, "workspaceId");
            extensionId = identifier(extensionId, "extensionId");
            scope = identifier(scope, "scope");
            resourceId = identifier(resourceId, "resourceId");
            operation = identifier(operation, "operation");
            if (revision < 1) {
                throw new IllegalArgumentException("revision must be positive");
            }
        }
    }

    /**
     * 扩展列表项。
     *
     * @param id 扩展标识
     * @param displayName 展示名称
     * @param version Bundle 版本
     * @param revision Bundle revision
     * @param state 实时状态
     * @param trust 信任层
     * @param contributionKinds 贡献类别
     */
    public record Summary(
            String id,
            String displayName,
            String version,
            long revision,
            String state,
            String trust,
            Set<String> contributionKinds) {
        /** 校验摘要。 */
        public Summary {
            id = text(id, "id");
            displayName = text(displayName, "displayName");
            version = text(version, "version");
            if (revision < 1) {
                throw new IllegalArgumentException("revision must be positive");
            }
            state = text(state, "state");
            trust = text(trust, "trust");
            contributionKinds = Set.copyOf(contributionKinds);
        }
    }

    /**
     * 扩展列表结果。
     *
     * @param extensions 扩展摘要
     */
    public record ListResult(List<Summary> extensions) {
        /** 复制结果。 */
        public ListResult {
            extensions = List.copyOf(extensions);
        }
    }

    /**
     * Schema 读取参数。
     *
     * @param extensionId 扩展标识
     * @param schemaId schema 标识
     */
    public record SchemaReadPayload(String extensionId, String schemaId) {
        /** 校验标识。 */
        public SchemaReadPayload {
            extensionId = text(extensionId, "extensionId");
            schemaId = text(schemaId, "schemaId");
        }
    }

    /**
     * Schema 读取结果。
     *
     * @param extensionId 扩展标识
     * @param schemaId schema 标识
     * @param schema JSON Schema
     */
    public record SchemaResult(String extensionId, String schemaId, CanonicalPayload schema) {
        /** 校验结果。 */
        public SchemaResult {
            extensionId = text(extensionId, "extensionId");
            schemaId = text(schemaId, "schemaId");
            Objects.requireNonNull(schema, "schema");
        }
    }

    /**
     * ViewSchema 列表参数。
     *
     * @param extensionId 可选扩展过滤
     */
    public record ViewListPayload(Optional<String> extensionId) {
        /** 规范化过滤条件。 */
        public ViewListPayload {
            extensionId = Objects.requireNonNull(extensionId, "extensionId").map(value -> text(value, "extensionId"));
        }
    }

    /**
     * 编码后的安全 ViewSchema。
     *
     * @param extensionId 扩展标识
     * @param viewId 页面标识
     * @param schema ViewSchema 对象
     */
    public record ViewDocument(String extensionId, String viewId, CanonicalPayload schema) {
        /** 校验页面。 */
        public ViewDocument {
            extensionId = text(extensionId, "extensionId");
            viewId = text(viewId, "viewId");
            Objects.requireNonNull(schema, "schema");
        }
    }

    /**
     * ViewSchema 列表结果。
     *
     * @param views 页面
     */
    public record ViewListResult(List<ViewDocument> views) {
        /** 复制结果。 */
        public ViewListResult {
            views = List.copyOf(views);
        }
    }

    private static String text(String value, String name) {
        String normalized = Objects.requireNonNull(value, name).strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }

    private static String identifier(String value, String name) {
        String normalized = text(value, name);
        if (normalized.length() > 240 || !normalized.matches("[A-Za-z0-9][A-Za-z0-9._:/-]*")) {
            throw new IllegalArgumentException(name + " contains unsupported characters");
        }
        return normalized;
    }
}
