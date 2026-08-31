package com.javaclaw.desktop;

/** Transcript 自动跟随与未读计数的纯状态模型；流式 token delta 不会重复累计消息数。 */
final class TranscriptFollowState {
    private boolean following = true;
    private int unread;
    private int persistentSize;
    private boolean streamPresent;

    Update contentChanged(int newPersistentSize, boolean newStreamPresent, boolean incoming) {
        int newMessages = Math.max(0, newPersistentSize - persistentSize);
        if (newStreamPresent && !streamPresent) {
            newMessages++;
        }
        persistentSize = Math.max(0, newPersistentSize);
        streamPresent = newStreamPresent;
        if (incoming && !following && newMessages > 0) {
            unread += newMessages;
        }
        return snapshot();
    }

    Update userScrolledUp() {
        following = false;
        return snapshot();
    }

    Update viewportChanged(double oldValue, double value, double maximum) {
        if (value >= maximum - 0.001) {
            following = true;
            unread = 0;
        } else if (value < oldValue) {
            following = false;
        }
        return snapshot();
    }

    Update followLatest() {
        following = true;
        unread = 0;
        return snapshot();
    }

    Update reset(int newPersistentSize, boolean newStreamPresent) {
        following = true;
        unread = 0;
        persistentSize = Math.max(0, newPersistentSize);
        streamPresent = newStreamPresent;
        return snapshot();
    }

    Update snapshot() {
        return new Update(following, unread);
    }

    /**
     * @param following 是否应将视口保持在底部
     * @param unread 用户停止跟随后累计的逻辑消息数
     */
    record Update(boolean following, int unread) {}
}
