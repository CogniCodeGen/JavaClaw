package com.javaclaw.desktop;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Set;

import com.javaclaw.sdk.ItemDeltaNotification;

/** 单会话的有界临时文本投影；持久 Item 到达后移除 delta，重复或切换前的通知不得污染当前 UI。 */
final class TranscriptStreamBuffer {
    static final int MAXIMUM_ITEMS = 8;
    static final int MAXIMUM_CHARACTERS = 16_384;
    private final LinkedHashMap<String, Text> items = new LinkedHashMap<>();
    private final Set<String> completed = new LinkedHashSet<>();
    private String threadId;

    synchronized void select(String id) {
        threadId = id;
        items.clear();
        completed.clear();
    }

    synchronized boolean append(ItemDeltaNotification delta) {
        if (!delta.threadId().equals(threadId) || completed.contains(delta.itemId()) || delta.deltaSequence() < 1) {
            return false;
        }
        Text value = items.computeIfAbsent(delta.itemId(), ignored -> new Text());
        if (delta.deltaSequence() <= value.sequence) {
            return false;
        }
        value.incomplete |= delta.deltaSequence() != value.sequence + 1;
        value.sequence = delta.deltaSequence();
        String fragment = delta.text() == null ? "" : delta.text();
        if (fragment.length() > MAXIMUM_CHARACTERS) {
            fragment = fragment.substring(fragment.length() - MAXIMUM_CHARACTERS);
            value.incomplete = true;
        }
        value.text.append(fragment);
        if (value.text.length() > MAXIMUM_CHARACTERS) {
            value.text.delete(0, value.text.length() - MAXIMUM_CHARACTERS);
            value.incomplete = true;
        }
        while (items.size() > MAXIMUM_ITEMS) {
            items.remove(items.keySet().iterator().next());
        }
        return true;
    }

    synchronized void completed(String itemId) {
        items.remove(itemId);
        completed.add(itemId);
        while (completed.size() > 256) {
            completed.remove(completed.iterator().next());
        }
    }

    synchronized String text() {
        StringBuilder result = new StringBuilder();
        items.values().forEach(value -> {
            if (!result.isEmpty()) {
                result.append('\n');
            }
            if (value.incomplete) {
                result.append("[流式预览不完整，最终内容以持久记录为准]\n");
            }
            result.append(value.text);
        });
        return result.toString();
    }

    private static final class Text {
        private long sequence;
        private boolean incomplete;
        private final StringBuilder text = new StringBuilder();
    }
}
