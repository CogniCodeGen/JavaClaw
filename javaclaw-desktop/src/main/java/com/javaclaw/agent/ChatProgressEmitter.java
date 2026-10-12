package com.javaclaw.agent;

import com.javaclaw.api.conversation.ConversationCallbacks;
import com.javaclaw.api.conversation.ConversationEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Emits optional pipeline progress without allowing a presentation callback to abort a turn. */
final class ChatProgressEmitter {

    private static final Logger log = LoggerFactory.getLogger(ChatProgressEmitter.class);

    private ChatProgressEmitter() { }

    static void emit(
            ConversationCallbacks callbacks,
            String stageId,
            String label,
            ConversationEvent.Progress.Status status,
            String detail) {
        try {
            callbacks.onEvent(new ConversationEvent.Progress(stageId, label, status, detail));
        } catch (Throwable failure) {
            log.debug("发送 Progress 事件失败（忽略）: {}/{} — {}",
                    stageId, status, failure.getMessage());
        }
    }
}
