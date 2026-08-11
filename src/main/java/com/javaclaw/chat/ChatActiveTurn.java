package com.javaclaw.chat;

import com.javaclaw.api.conversation.ConversationHandle;

/** Mutable state owned by one conversation turn and never shared between turns. */
final class ChatActiveTurn {

    final int generation;
    final String modeId;
    final long startedAtNanos = System.nanoTime();
    long inputTokens;
    long outputTokens;
    DeliveryState deliveryState = DeliveryState.COMPLETE;
    volatile ConversationHandle handle;

    ChatActiveTurn(int generation, String modeId) {
        this.generation = generation;
        this.modeId = modeId;
    }

    TurnMetrics metrics() {
        long duration = Math.max(0, (System.nanoTime() - startedAtNanos) / 1_000_000L);
        return new TurnMetrics(inputTokens, outputTokens, duration);
    }
}
