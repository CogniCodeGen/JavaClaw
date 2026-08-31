package com.javaclaw.agent.runtime;

import com.javaclaw.core.api.ItemDelta;
import com.javaclaw.core.api.ItemId;
import com.javaclaw.core.api.ModelUsage;
import com.javaclaw.core.api.ProviderConversationState;
import com.javaclaw.core.api.StoredItem;
import com.javaclaw.core.api.ThreadItem;

/** Runtime lifecycle boundary for durable Items and transient deltas. */
public interface ItemSink {
    /** 一次性追加完整 Item 并返回持久投影；运行时负责同事务事件及 Outbox。 */
    StoredItem append(ThreadItem item);

    /** Starts a durable lifecycle. Production implementations persist start and terminal state. */
    default ItemEmitter start(String kind) {
        ItemId provisional = ItemId.random();
        return new ItemEmitter() {
            private boolean terminal;
            private long sequence;

            @Override
            public ItemId id() {
                return provisional;
            }

            @Override
            public synchronized void delta(ItemDelta value) {
                if (terminal) {
                    throw new IllegalStateException("item is already terminal");
                }
                java.util.Objects.requireNonNull(value, "value");
                sequence++;
            }

            @Override
            public synchronized StoredItem complete(ThreadItem value) {
                if (terminal) {
                    throw new IllegalStateException("item is already terminal");
                }
                if (!kind.equals(value.kind())) {
                    throw new IllegalArgumentException("completed item kind changed");
                }
                terminal = true;
                return append(value);
            }

            @Override
            public synchronized StoredItem fail(String code, String message, boolean retryable) {
                if (terminal) {
                    throw new IllegalStateException("item is already terminal");
                }
                terminal = true;
                return append(new ThreadItem.ErrorItem(code, message, retryable));
            }
        };
    }

    /** Records one provider-reported usage delta without manufacturing a transcript item. */
    default void usage(ModelUsage delta) {}

    /** 保存普通原生 Responses 调用的 canonical 状态；不支持持久窗口的测试夹具可忽略。 */
    default void providerConversationState(String model, ProviderConversationState state, ModelUsage usage) {}

    /** 记录实际模型调用的模板及内容地址；生产实现必须持久化，测试夹具可只观察本次回调。 */
    default void promptSnapshot(com.javaclaw.agent.prompt.PromptSnapshot snapshot, int invocationOrdinal) {}

    /** 保存当前 Turn 的累计调用与保守 token 占用；仅自动化入口需要持久化，用于崩溃后的有限预算恢复。 */
    default void budget(int usedCalls, long committedTokens) {}

    /** Resolves a durable approval when its local rendezvous ends. */
    default void approvalResolved(String approvalId, boolean approved) {}

    /** 单 Item 的生命周期句柄；可多次 delta，但 complete/fail 只能有一个终态。 */
    interface ItemEmitter {
        /** 返回已分配的稳定 Item 标识，用于关联增量和最终内容。 */
        ItemId id();

        /** 发送新增片段，不写入持久事件序列；终态后不允许继续生成。 */
        void delta(ItemDelta value);

        /** 保存最终内容并将 Item 收敛为 COMPLETED；重复收尾应被拒绝。 */
        StoredItem complete(ThreadItem value);

        /** 将 id-only 压缩 Item 与替换窗口原子收尾；默认夹具仅保存 Item。 */
        default StoredItem completeCompaction(
                ThreadItem.ContextCompaction value,
                com.javaclaw.agent.conversation.ConversationWindow.Replacement replacement) {
            java.util.Objects.requireNonNull(replacement, "replacement");
            return complete(value);
        }

        /** 保存脱敏 ErrorItem 并收敛为 FAILED；retryable 只描述可重试性，不自动执行重试。 */
        StoredItem fail(String code, String message, boolean retryable);
    }
}
