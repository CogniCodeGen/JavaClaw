package com.javaclaw.api.conversation;

import java.util.Objects;

/** 对话运行唯一且不可逆的终态。 */
public sealed interface ConversationOutcome
        permits ConversationOutcome.Completed, ConversationOutcome.Cancelled, ConversationOutcome.Failed {

    /** 正常完成。 */
    record Completed() implements ConversationOutcome {}

    /** 已取消。 */
    record Cancelled(CancellationReason reason, boolean userInitiated)
            implements ConversationOutcome {
        public Cancelled {
            Objects.requireNonNull(reason, "reason");
        }
    }

    /** 运行失败。 */
    record Failed(Throwable error) implements ConversationOutcome {
        public Failed {
            Objects.requireNonNull(error, "error");
        }
    }

    static Completed completed() {
        return new Completed();
    }

    static Cancelled cancelled(CancellationReason reason) {
        return new Cancelled(reason, reason == CancellationReason.USER_REQUEST);
    }

    static Failed failed(Throwable error) {
        return new Failed(error);
    }
}
