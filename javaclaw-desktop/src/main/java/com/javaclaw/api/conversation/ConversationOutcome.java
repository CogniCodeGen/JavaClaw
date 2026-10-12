package com.javaclaw.api.conversation;

import com.javaclaw.framework.api.TaskResult;

import java.util.Objects;

/** 对话运行唯一且不可逆的终态。 */
public sealed interface ConversationOutcome
        permits ConversationOutcome.Completed, ConversationOutcome.Cancelled, ConversationOutcome.Failed,
                ConversationOutcome.WaitingInput {

    /** This delivery ended; its durable Agent turn remains open for the next user input. */
    record WaitingInput(String turnId, String reason) implements ConversationOutcome {
        public WaitingInput { Objects.requireNonNull(turnId, "turnId"); }
    }

    /** The run ended normally; taskResult separately describes whether its task was fulfilled. */
    record Completed(TaskResult taskResult) implements ConversationOutcome {
        /** Compatibility for non-framework modes and older callers without task evidence. */
        public Completed() { this(null); }
    }

    /** 已取消。 */
    record Cancelled(CancellationReason reason, boolean userInitiated,
                     TaskResult taskResult, String detail)
            implements ConversationOutcome {
        public Cancelled(CancellationReason reason, boolean userInitiated) {
            this(reason, userInitiated, null, "");
        }
        public Cancelled {
            Objects.requireNonNull(reason, "reason");
            detail = detail == null ? "" : detail;
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

    static Completed completed(TaskResult taskResult) {
        return new Completed(taskResult);
    }

    static Cancelled cancelled(CancellationReason reason) {
        return new Cancelled(reason, reason == CancellationReason.USER_REQUEST);
    }

    static Cancelled cancelled(CancellationReason reason, TaskResult taskResult, String detail) {
        return new Cancelled(reason, reason == CancellationReason.USER_REQUEST,
                taskResult, detail);
    }

    static Failed failed(Throwable error) {
        return new Failed(error);
    }
}
