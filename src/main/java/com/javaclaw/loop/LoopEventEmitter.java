package com.javaclaw.loop;

import com.javaclaw.api.conversation.ConversationCallbacks;
import com.javaclaw.api.conversation.ConversationEvent;
import com.javaclaw.loop.model.CompletionCheck;
import com.javaclaw.loop.model.Decision;
import com.javaclaw.loop.model.IterationResult;
import com.javaclaw.loop.model.LoopStatus;
import com.javaclaw.loop.model.LoopVerdict;
import com.javaclaw.loop.model.StopReason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Maps loop decisions to the stable conversation event protocol consumed by the UI. */
final class LoopEventEmitter {

    private static final Logger log = LoggerFactory.getLogger(LoopEventEmitter.class);

    void iterationStarted(ConversationCallbacks callbacks, int iteration) {
        if (iteration > 1) {
            callbacks.onEvent(new ConversationEvent.Reply(
                    "\n\n---\n\n**🔁 第 " + iteration + " 轮**\n\n"));
        }
        callbacks.onEvent(new ConversationEvent.Progress(
                LoopConstants.EVENT_STAGE_PREFIX + iteration,
                LoopConstants.EVENT_STAGE_LABEL_PREFIX + iteration
                        + LoopConstants.EVENT_STAGE_LABEL_SUFFIX,
                ConversationEvent.Progress.Status.RUNNING,
                null));
    }

    void iterationFinished(
            ConversationCallbacks callbacks,
            int iteration,
            IterationResult result,
            LoopVerdict verdict) {
        closeStage(callbacks, iteration,
                result.threw() ? ConversationEvent.Progress.Status.ERROR
                        : ConversationEvent.Progress.Status.DONE,
                verdict.message());
    }

    void closeStage(
            ConversationCallbacks callbacks,
            int iteration,
            ConversationEvent.Progress.Status status,
            String message) {
        callbacks.onEvent(new ConversationEvent.Progress(
                LoopConstants.EVENT_STAGE_PREFIX + iteration,
                LoopConstants.EVENT_STAGE_LABEL_PREFIX + iteration
                        + LoopConstants.EVENT_STAGE_LABEL_SUFFIX,
                status, message));
    }

    void status(
            ConversationCallbacks callbacks,
            int iteration,
            LoopVerdict verdict,
            CompletionCheck check,
            long tokensUsed,
            long nextDelaySeconds) {
        callbacks.onEvent(new ConversationEvent.Custom(
                LoopConstants.EVENT_STATUS_KIND,
                new LoopStatus(iteration, verdict.decision(), verdict.message(),
                        check.satisfied(), check.total(), tokensUsed, nextDelaySeconds)));
    }

    void stopped(
            ConversationCallbacks callbacks,
            int iteration,
            StopReason reason,
            String message,
            int satisfied,
            int total,
            long tokensUsed) {
        callbacks.onEvent(new ConversationEvent.Custom(
                LoopConstants.EVENT_STATUS_KIND,
                new LoopStatus(iteration, Decision.STOP, message, satisfied, total,
                        tokensUsed, 0L)));
        log.info("循环停止：轮次={} 原因={} 说明={}", iteration, reason, message);
    }
}
