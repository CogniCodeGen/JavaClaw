package com.javaclaw.desktop;

import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.LongFunction;
import java.util.function.LongSupplier;

import com.javaclaw.api.ConversationThread;
import com.javaclaw.api.ItemEnvelope;
import com.javaclaw.client.sdk.JavaClawClient;
import com.javaclaw.desktop.state.TranscriptState;
import com.javaclaw.protocol.CoreRpcContracts;

/**
 * 旧协议没有尾部查询；只在进入会话时向前翻页，始终保留最近 100 条。
 *
 * <p>每页前检查导航取消和中断；无进展立即失败。连续满页超过十秒时明确失败，不能把尚未读到末尾的历史 当作已完成恢复并允许发送新 Turn。上限限制一次恢复的追尾时间，不改变服务端任务或持久游标。
 */
final class DesktopLegacyHistory {
    private static final int PAGE_SIZE = 100;
    private static final long MAXIMUM_NANOS = TimeUnit.SECONDS.toNanos(10);

    private DesktopLegacyHistory() {}

    static TranscriptState latest(JavaClawClient client, ConversationThread thread, BooleanSupplier cancelled) {
        return read(cursor -> client.items().list(thread.id(), cursor, PAGE_SIZE), cancelled, System::nanoTime);
    }

    static TranscriptState read(
            LongFunction<CoreRpcContracts.ItemListResult> pages, BooleanSupplier cancelled, LongSupplier nanoTime) {
        long started = nanoTime.getAsLong();
        long cursor = 0;
        ArrayDeque<ItemEnvelope> window = new ArrayDeque<>(PAGE_SIZE);
        while (true) {
            checkCurrent(cancelled, nanoTime.getAsLong() - started);
            var page = pages.apply(cursor);
            checkCurrent(cancelled, nanoTime.getAsLong() - started);
            requireProgress(page, cursor);
            for (ItemEnvelope item : page.items()) {
                if (window.size() == PAGE_SIZE) {
                    window.removeFirst();
                }
                window.addLast(item);
            }
            cursor = page.nextSequence();
            if (page.items().size() < PAGE_SIZE) {
                var result = new TranscriptState(List.copyOf(window), cursor, true);
                if (cursor > 0
                        && (result.items().isEmpty() || result.items().getLast().sequence() != cursor)) {
                    throw new IllegalStateException("旧协议末条消息超过历史缓存上限，无法安全恢复活动状态");
                }
                return result;
            }
        }
    }

    private static void checkCurrent(BooleanSupplier cancelled, long elapsed) {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
            throw new CancellationException("会话历史读取已失效");
        }
        if (elapsed >= MAXIMUM_NANOS) {
            throw new IllegalStateException("旧协议聊天历史未能在限定时间内读到末尾，请重新进入会话后重试");
        }
    }

    private static void requireProgress(CoreRpcContracts.ItemListResult page, long cursor) {
        long previous = cursor;
        for (ItemEnvelope item : page.items()) {
            if (item.sequence() <= previous) {
                throw new IllegalStateException("旧协议历史分页没有严格推进");
            }
            previous = item.sequence();
        }
        if (page.nextSequence() != previous || page.items().size() > PAGE_SIZE) {
            throw new IllegalStateException("旧协议历史分页游标与返回记录不一致");
        }
    }
}
