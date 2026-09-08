package com.javaclaw.extension.spi;

import com.javaclaw.api.ItemId;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.WorkspaceId;

/** 只读核验 Core Item 归属和逐字文本的扩展端口。 */
@FunctionalInterface
public interface ItemEvidencePort {
    /**
     * 核验 Item 属于指定 Workspace/Thread，且某个文本字段逐字包含证据。
     *
     * @param workspaceId Workspace
     * @param threadId Thread
     * @param itemId Item
     * @param verbatim 要逐字匹配的非空内容
     * @return 归属与内容均匹配时为 {@code true}
     */
    boolean containsVerbatim(WorkspaceId workspaceId, ThreadId threadId, ItemId itemId, String verbatim);

    /**
     * 核验来源是指定 Workspace/Thread 中真实的 Core USER 消息，不能信任扩展自报来源类别。
     *
     * @param workspaceId 当前工作空间
     * @param threadId 原始会话
     * @param itemId 原始消息
     * @return 归属、消息类型与 USER 角色均由平台确认时为 true；旧宿主默认拒绝自动接受
     */
    default boolean isUserText(WorkspaceId workspaceId, ThreadId threadId, ItemId itemId) {
        return false;
    }

    /**
     * 判断来源 Item 是否代表失败或结果不确定的执行。
     *
     * <p>默认实现用于不保存 Core Item 语义的最小宿主；正式 App Server 必须覆盖此方法并从权威 Item payload 判定，不能信任扩展请求自报结果状态。
     *
     * @param workspaceId Workspace
     * @param threadId Thread
     * @param itemId Item
     * @return 来源失败、结果未知或无法安全确认时为 {@code true}
     */
    default boolean isUncertainOutcome(WorkspaceId workspaceId, ThreadId threadId, ItemId itemId) {
        return false;
    }
}
