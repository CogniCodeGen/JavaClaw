package com.javaclaw.desktop.state;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.ItemEnvelope;
import com.javaclaw.api.ItemHistoryEntry;
import com.javaclaw.api.ItemHistoryResult;
import com.javaclaw.api.TurnId;
import com.javaclaw.client.facade.TurnStreamSnapshot;

/**
 * 当前 Thread 的 ItemEnvelope 投影与分页游标。
 *
 * @param items 按 sequence 严格递增的 Item
 * @param nextSequence 已接收的最大 sequence；空列表时为零
 * @param following 是否在新 Item 到达后自动滚动到底部
 * @param stream SDK 已顺序归并的活动正文；没有活动流时为空
 * @param hasEarlier 有更早历史可按100条分页读取
 * @param history 带来源身份的摘要缓存，与完整 Item 分开保存
 * @param outgoing 当前对话的用户发送回显，尚未发送或已被权威历史替换时为空
 */
public record TranscriptState(
        List<ItemEnvelope> items,
        long nextSequence,
        boolean following,
        Optional<TurnStreamSnapshot> stream,
        boolean hasEarlier,
        List<ItemHistoryEntry> history,
        Optional<OutgoingMessage> outgoing) {
    /**
     * 创建没有本地发送回显的兼容快照。
     *
     * @param items 已提交消息
     * @param nextSequence 最大持久化序号
     * @param following 是否跟随
     * @param stream 活动正文，可为空
     * @param hasEarlier 是否存在更早历史
     * @param history 权威摘要缓存
     */
    public TranscriptState(
            List<ItemEnvelope> items,
            long nextSequence,
            boolean following,
            Optional<TurnStreamSnapshot> stream,
            boolean hasEarlier,
            List<ItemHistoryEntry> history) {
        this(items, nextSequence, following, stream, hasEarlier, history, Optional.empty());
    }

    /**
     * @param items 已提交消息
     * @param nextSequence 最大游标
     * @param following 是否跟随；兼容无流调用方
     */
    public TranscriptState(List<ItemEnvelope> items, long nextSequence, boolean following) {
        this(items, nextSequence, following, Optional.empty(), false, List.of());
    }

    /** 复制 Item 并校验严格递增 sequence。 */
    public TranscriptState {
        outgoing = Objects.requireNonNull(outgoing, "outgoing");
        if (outgoing.isPresent() && outgoing.orElseThrow().committed(items, history)) {
            outgoing = Optional.empty();
        }
        items = bounded(items);
        stream = Objects.requireNonNull(stream, "stream");
        history = List.copyOf(history.size() > 500 ? history.subList(history.size() - 500, history.size()) : history);
        if (nextSequence < 0) {
            throw new IllegalArgumentException("nextSequence must not be negative");
        }
        long previous = 0;
        for (ItemEnvelope item : items) {
            Objects.requireNonNull(item, "item");
            if (item.sequence() <= previous || item.sequence() > nextSequence) {
                throw new IllegalArgumentException("transcript sequence must be strictly increasing");
            }
            previous = item.sequence();
        }
    }

    /** @return 空 Transcript */
    public static TranscriptState empty() {
        return new TranscriptState(List.of(), 0, true);
    }

    /**
     * 追加下一页；重复或倒序页会被拒绝。
     *
     * @param page 新 Item
     * @param cursor 服务端返回游标
     * @return 合并快照
     */
    public TranscriptState append(List<ItemEnvelope> page, long cursor) {
        List<ItemEnvelope> copy = List.copyOf(page);
        if (cursor < nextSequence || (!copy.isEmpty() && copy.getFirst().sequence() <= nextSequence)) {
            throw new IllegalArgumentException("transcript page is stale or overlaps current items");
        }
        ArrayList<ItemEnvelope> merged = new ArrayList<>(items.size() + copy.size());
        merged.addAll(items);
        merged.addAll(copy);
        return new TranscriptState(
                merged, cursor, following, stream, hasEarlier || merged.size() > 500, history, outgoing);
    }

    /**
     * @param value 已应用 cursor 对应的暂态消息
     * @return 保留持久事实的流快照
     */
    public TranscriptState stream(TurnStreamSnapshot value) {
        return new TranscriptState(items, nextSequence, following, Optional.of(value), hasEarlier, history, outgoing);
    }

    /**
     * @param value 是否跟随最新消息
     * @return 保留当前读取位置策略的新状态
     */
    public TranscriptState following(boolean value) {
        if (following == value) {
            return this;
        }
        return new TranscriptState(items, nextSequence, value, stream, hasEarlier, history, outgoing);
    }

    /**
     * 发布当前发送的本地回显；发送意图立即跟随到底部，历史序号保持不变。
     *
     * @param value 当前命令回显，不可空
     * @return 包含发送回显的快照
     */
    public TranscriptState outgoing(OutgoingMessage value) {
        return new TranscriptState(items, nextSequence, true, stream, hasEarlier, history, Optional.of(value));
    }

    /**
     * 仅为同一次发送绑定服务端回执；迟到的其他身份不能修改当前回显。
     *
     * @param id 本地发送身份
     * @param turnId 服务端接受的 Turn
     * @return 绑定回执并与已有权威事实去重后的快照
     */
    public TranscriptState acknowledged(String id, TurnId turnId) {
        return outgoing.filter(value -> value.id().equals(id))
                .map(value -> accepted(value, turnId))
                .orElse(this);
    }

    private TranscriptState accepted(OutgoingMessage value, TurnId turnId) {
        // 新 Turn 已被接受后结束旧轮暂态展示，避免旧 CLOSED 尾文排在新权威用户消息之后。
        // 等待回执或提交失败时仍保留旧轮内容；已到达的同 Turn 正文不得因启动回执而被清除。
        return new TranscriptState(
                items,
                nextSequence,
                following,
                stream.filter(snapshot -> snapshot.turnId().equals(turnId)),
                hasEarlier,
                history,
                Optional.of(new OutgoingMessage(
                        value.id(),
                        value.text(),
                        Optional.of(turnId),
                        OutgoingMessage.Status.ACCEPTED,
                        value.attempt())));
    }

    /**
     * 标记同一次发送未得到确认，不声称服务端没有接受，也不删除其正文。
     *
     * @param id 本地发送身份
     * @return 保留回显及阅读位置的快照
     */
    public TranscriptState unconfirmed(String id) {
        return outgoing.filter(value -> value.id().equals(id))
                .map(value -> replaceOutgoing(new OutgoingMessage(
                        value.id(), value.text(), value.turnId(), OutgoingMessage.Status.UNCONFIRMED, value.attempt())))
                .orElse(this);
    }

    private TranscriptState replaceOutgoing(OutgoingMessage value) {
        return new TranscriptState(items, nextSequence, following, stream, hasEarlier, history, Optional.of(value));
    }

    /**
     * @param result 更早摘要页
     * @return 保持升序、至多500条的历史窗口，向前翻页时从最新端淘汰
     */
    public TranscriptState earlier(ItemHistoryResult result) {
        var merged = new java.util.TreeMap<Long, ItemHistoryEntry>();
        history.forEach(item -> merged.put(item.sequence(), item));
        result.items().forEach(item -> merged.put(item.sequence(), item));
        List<ItemHistoryEntry> window = merged.values().stream().limit(500).toList();
        return new TranscriptState(
                items,
                Math.max(nextSequence, result.latestSequence()),
                false,
                stream,
                result.hasEarlier(),
                window,
                reconciled(result));
    }

    /**
     * @param result 最近摘要事实
     * @return 已提交正文替换相同消息身份后的状态；尾页断开时保留连续窗口，避免旧缓存遮住中间缺页
     */
    public TranscriptState committed(ItemHistoryResult result) {
        if (!history.isEmpty()
                && !result.items().isEmpty()
                && result.items().getFirst().sequence() - 1 > history.getLast().sequence()) {
            // 跟随时从新尾页继续向前翻；暂停跟随时只更新末端水位，保留当前阅读锚点与其分页入口。
            return new TranscriptState(
                    items,
                    Math.max(nextSequence, result.latestSequence()),
                    following,
                    stream,
                    following || hasEarlier,
                    following ? result.items() : history,
                    reconciled(result));
        }
        var merged = new java.util.TreeMap<Long, ItemHistoryEntry>();
        history.forEach(item -> merged.put(item.sequence(), item));
        result.items().forEach(item -> merged.put(item.sequence(), item));
        var values = new ArrayList<>(merged.values());
        return new TranscriptState(
                items,
                Math.max(nextSequence, result.latestSequence()),
                following,
                stream,
                hasEarlier || result.hasEarlier(),
                following ? values : values.stream().limit(500).toList(),
                reconciled(result));
    }

    private Optional<OutgoingMessage> reconciled(ItemHistoryResult result) {
        // 即使阅读中的窗口不采纳新尾页，也必须确认该页中的权威事实，不能留下已经持久化的重复回显。
        return outgoing.filter(value -> !value.committed(List.of(), result.items()));
    }

    private static List<ItemEnvelope> bounded(List<ItemEnvelope> input) {
        int start = input.size();
        long bytes = 0;
        while (start > 0 && input.size() - start < 500) {
            ItemEnvelope item = Objects.requireNonNull(input.get(start - 1), "item");
            long weight = item.payload().json().length() * 2L + 1024;
            if (bytes + weight > 8L * 1024 * 1024) {
                break;
            }
            bytes += weight;
            start--;
        }
        // 已有不可变窗口无需因每个正文片段重新复制；截断仍保留连续尾部及原有字节上限。
        return List.copyOf(start == 0 ? input : input.subList(start, input.size()));
    }
}
