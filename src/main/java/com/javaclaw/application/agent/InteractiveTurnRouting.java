package com.javaclaw.application.agent;

import com.javaclaw.framework.api.InputBlock;
import com.javaclaw.framework.api.RunRequest;
import com.javaclaw.framework.api.RunState;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/** A paused delivery is not an implicit request to continue its old task. */
final class InteractiveTurnRouting {
    enum Intent { CONTINUE, NEW_TASK }
    private static final Set<String> CONTINUATION_COMMANDS = Set.of(
            "继续", "继续执行", "继续任务", "重试", "重试本轮", "恢复", "恢复任务",
            "continue", "resume", "retry", "go on");

    private InteractiveTurnRouting() { }

    static Intent intent(RunState state, RunRequest incoming, RunRequest original) {
        var explicit = incoming.attributes().get("framework.turnIntent");
        if (explicit != null) {
            if (!explicit.isTextual()) throw new IllegalArgumentException("turn intent must be textual");
            return Intent.valueOf(explicit.asText());
        }
        List<InputBlock> current = humanInputs(incoming);
        // Attachments and multiple input blocks cannot be discarded as a short command.
        if (current.size() == 1 && current.getFirst().type().equals("core.text")) {
            String command = current.getFirst().data().path("text").asText("")
                    .strip().toLowerCase(Locale.ROOT).replaceAll("[。.!！]+$", "");
            if (CONTINUATION_COMMANDS.contains(command)) return Intent.CONTINUE;
        }
        if (original != null && !current.isEmpty() && current.equals(humanInputs(original))) {
            return Intent.CONTINUE;
        }
        // A waiting-input turn has explicitly asked for an answer. A generic pause has not.
        return state == RunState.WAITING_INPUT ? Intent.CONTINUE : Intent.NEW_TASK;
    }

    private static List<InputBlock> humanInputs(RunRequest request) {
        return request.inputs().stream().filter(input -> !input.type().equals("core.message")).toList();
    }
}
