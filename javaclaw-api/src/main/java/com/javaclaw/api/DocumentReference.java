package com.javaclaw.api;

import java.util.Objects;
import java.util.Optional;

/**
 * 不包含宿主路径或权限配置的文档引用，来源由服务端重新验证。
 *
 * @param kind 引用类别，不可空
 * @param workspaceId 当前 Workspace，不可空
 * @param sourceItemId 消息或文件来源；Attachment 可为空
 * @param selector 来源中的稳定选择器：body、fence:N、link:N 或 file:N，N 从零开始
 * @param attachment 附件引用；仅 ATTACHMENT 存在
 */
public record DocumentReference(
        Kind kind,
        WorkspaceId workspaceId,
        Optional<ItemId> sourceItemId,
        String selector,
        Optional<AttachmentRef> attachment) {
    /** 受支持的来源类别，不允许客户端提供任意路径。 */
    public enum Kind {
        /** 当前 Workspace 已拥有的内容寻址附件。 */
        ATTACHMENT,
        /** 来源 Item 中声明的当前工作区文件。 */
        WORKSPACE_FILE,
        /** 已持久化消息正文或代码围栏。 */
        MESSAGE_CONTENT
    }

    /** 校验引用字段互斥和选择器的有界语法。 */
    public DocumentReference {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(workspaceId, "workspaceId");
        sourceItemId = Objects.requireNonNull(sourceItemId, "sourceItemId");
        selector = Objects.requireNonNull(selector, "selector");
        attachment = Objects.requireNonNull(attachment, "attachment");
        boolean valid =
                switch (kind) {
                    case ATTACHMENT -> attachment.isPresent() && selector.isEmpty();
                    case WORKSPACE_FILE ->
                        sourceItemId.isPresent() && attachment.isEmpty() && selector.matches("(link|file):[0-9]{1,6}");
                    case MESSAGE_CONTENT ->
                        sourceItemId.isPresent()
                                && attachment.isEmpty()
                                && (selector.equals("body") || selector.matches("fence:[0-9]{1,6}"));
                };
        if (!valid) {
            throw new IllegalArgumentException("文档引用的来源与选择器不匹配");
        }
    }

    /**
     * @param workspaceId 当前项目
     * @param value 已上传附件
     * @return 无路径的附件引用
     */
    public static DocumentReference attachment(WorkspaceId workspaceId, AttachmentRef value) {
        return new DocumentReference(Kind.ATTACHMENT, workspaceId, Optional.empty(), "", Optional.of(value));
    }

    /**
     * 引用消息明确携带的附件，允许服务端验证同一消息中的相对附件资源。
     *
     * @param workspaceId 当前项目
     * @param item 来源消息
     * @param value 消息的已上传附件
     * @return 具有消息关联的附件引用
     */
    public static DocumentReference attachment(WorkspaceId workspaceId, ItemId item, AttachmentRef value) {
        return new DocumentReference(Kind.ATTACHMENT, workspaceId, Optional.of(item), "", Optional.of(value));
    }

    /**
     * @param workspaceId 当前项目
     * @param item 来源消息
     * @param selector body 或 fence:N
     * @return 消息内容引用
     */
    public static DocumentReference message(WorkspaceId workspaceId, ItemId item, String selector) {
        return new DocumentReference(Kind.MESSAGE_CONTENT, workspaceId, Optional.of(item), selector, Optional.empty());
    }

    /**
     * @param workspaceId 当前项目
     * @param item 来源 Item
     * @param selector link:N 或 file:N
     * @return 需重新授权的文件引用
     */
    public static DocumentReference file(WorkspaceId workspaceId, ItemId item, String selector) {
        return new DocumentReference(Kind.WORKSPACE_FILE, workspaceId, Optional.of(item), selector, Optional.empty());
    }
}
