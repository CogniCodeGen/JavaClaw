package com.javaclaw.api;

import java.util.Objects;
import java.util.Optional;

/**
 * Attachment 的显式所有权范围。
 *
 * <p>摘要只标识内容，不能证明调用方拥有该内容。平台必须为每次上传和读取携带本范围，并从持久 claim 核验所有权。
 *
 * @param kind 所有权范围类型
 * @param workspaceId WORKSPACE 范围的 Workspace；GLOBAL 范围必须为空
 */
public record AttachmentScope(Kind kind, Optional<WorkspaceId> workspaceId) {
    /** Attachment 所有权范围类型。 */
    public enum Kind {
        /** 安装级平台资源，例如第三方 Bundle 与信任公钥。 */
        GLOBAL,
        /** 只能由一个 Workspace 使用的业务资源。 */
        WORKSPACE
    }

    /** 校验范围类型与 Workspace 的一一对应关系。 */
    public AttachmentScope {
        Objects.requireNonNull(kind, "kind");
        workspaceId = Objects.requireNonNull(workspaceId, "workspaceId");
        if ((kind == Kind.WORKSPACE) != workspaceId.isPresent()) {
            throw new IllegalArgumentException("WORKSPACE Attachment scope requires exactly one WorkspaceId");
        }
    }

    /**
     * 创建安装级全局范围。
     *
     * @return 不属于任何 Workspace 的 GLOBAL 范围
     */
    public static AttachmentScope global() {
        return new AttachmentScope(Kind.GLOBAL, Optional.empty());
    }

    /**
     * 创建 Workspace 所有范围。
     *
     * @param workspaceId 所有者 Workspace
     * @return WORKSPACE 范围
     */
    public static AttachmentScope workspace(WorkspaceId workspaceId) {
        return new AttachmentScope(Kind.WORKSPACE, Optional.of(Objects.requireNonNull(workspaceId, "workspaceId")));
    }
}
