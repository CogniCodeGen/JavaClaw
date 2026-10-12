package com.javaclaw.framework.core;

import com.javaclaw.framework.api.AgentStep;
import com.javaclaw.framework.api.RunId;
import com.javaclaw.framework.api.RunRequest;
import com.javaclaw.framework.spi.FrameworkTool;
import com.javaclaw.framework.spi.RunStore;
import com.javaclaw.framework.springai.SpringAiAnnotatedToolRegistry;

import java.util.Set;

/** Restores a host question after established work without repeating the physical operation. */
public final class BrowserUserInputRecovery {
    private BrowserUserInputRecovery() { }

    public static boolean candidate(RunRequest request, AgentStep step) {
        if (!InteractionExecutionPolicy.isInteraction(request) || step == null
                || step.kind() != AgentStep.Kind.TOOL || step.state() != AgentStep.State.COMPLETED
                || step.input() == null || step.output() == null
                || !step.output().path("waitingInput").isBoolean() || !step.output().path("waitingInput").booleanValue()) return false;
        var context = step.output().path("inputContext");
        if (!context.isObject() || context.path("challengeId").asText("").isBlank()
                || context.path("challengeId").asText().length() > 128) return false;
        String tool = step.input().path("tool").asText();
        return switch (context.path("kind").asText()) {
            case "browser.account_selection_required" -> Set.of("web_navigate", "site_select_account").contains(tool);
            case "browser.authentication_required" -> Set.of("web_navigate", "site_login_interactive",
                    "site_login_now", "site_auth_check").contains(tool);
            default -> false;
        };
    }

    public static void restore(RunRequest request, RunStore runs, RunId runId, AgentStep step, FrameworkTool tool) {
        if (!candidate(request, step)) return;
        if (!step.turnId().equals(runId) || !step.input().path("tool").asText().equals(tool.descriptor().name())
                || !SpringAiAnnotatedToolRegistry.isTrustedUserInputSource(tool))
            throw new ToolRecoveryRequiredException(step.id().value(), "host browser input continuation is unavailable");
        var owner = runs.find(runId).orElseThrow(() -> new ToolRecoveryRequiredException(step.id().value()));
        if (!InteractionExecutionPolicy.isInteraction(owner.request()) || !owner.request().scope().equals(request.scope())
                || !runs.readable(owner.request().scope())) throw new ToolRecoveryRequiredException(step.id().value());
        var events = runs.eventsAfter(runId, 0);
        boolean started = events.stream().anyMatch(event -> event.sequence() == step.startSequence()
                && event.type().equals("core.step.started") && event.schemaVersion() == 1
                && event.producer().equals("framework.core")
                && event.payload().path("stepId").asText().equals(step.id().value())
                && event.payload().path("kind").asText().equals("TOOL")
                && event.payload().path("input").equals(step.input()));
        boolean completed = events.stream().anyMatch(event -> event.sequence() == step.lastSequence()
                && event.type().equals("core.step.completed") && event.schemaVersion() == 1
                && event.producer().equals("framework.core")
                && event.payload().path("stepId").asText().equals(step.id().value())
                && event.payload().path("output").equals(step.output()));
        if (!started || !completed) throw new ToolRecoveryRequiredException(step.id().value(), "host browser input journal is inconsistent");
        var context = step.output().path("inputContext");
        long questionSequence = events.stream().filter(event -> event.sequence() > step.lastSequence()
                && event.type().equals("core.run.waiting_input") && event.schemaVersion() == 1
                && event.producer().equals("framework.core")
                && event.payload().path("output").path("kind").equals(context.path("kind"))
                && event.payload().path("output").path("challengeId").equals(context.path("challengeId")))
                .mapToLong(com.javaclaw.framework.api.RunEventEnvelope::sequence).max().orElse(0);
        boolean answered = questionSequence > 0 && events.stream().anyMatch(event -> event.sequence() > questionSequence
                && event.type().equals("core.run.resumed") && event.schemaVersion() == 1
                && event.producer().equals("framework.core")
                && event.payload().path("commandType").asText().equals("input")
                && !event.payload().path("command").path("text").asText("").isBlank());
        if (!answered) throw new ToolInputRequiredException(context,
                context.path("kind").asText().equals("browser.account_selection_required")
                        ? "BROWSER_ACCOUNT_REQUIRED" : "BROWSER_AUTHENTICATION_REQUIRED");
    }
}
