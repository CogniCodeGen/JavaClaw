package com.javaclaw.server.persistence;

import java.util.Objects;

import com.javaclaw.runtime.ConversationWindow;

/**
 * 已持久化的模型窗口，仅由服务端恢复。
 *
 * @param window 完整窗口与可选 opaque state
 * @param throughSequence 已覆盖的 Thread Item sequence
 */
public record TurnContextSnapshot(ConversationWindow window, long throughSequence) {
    /** 校验窗口与覆盖位置。 */
    public TurnContextSnapshot {
        Objects.requireNonNull(window, "window");
        if (throughSequence < 0) {
            throw new IllegalArgumentException("negative context sequence");
        }
    }
}
