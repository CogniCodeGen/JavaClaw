package com.javaclaw.framework.springai;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionEligibilityChecker;
import org.springframework.ai.model.tool.ToolExecutionResult;
import reactor.core.publisher.Flux;

import java.util.List;

/** Spring AI 的唯一递归工具 Advisor，向 Provider 发送有界的消息投影。 */
final class SpringAiToolCallingAdvisor extends ToolCallingAdvisor {
    private final StepContextProjector projector;
    private final ToolCatalogSession catalog;
    private final ProviderContextBoundary boundary;
    private final OnDemandContextSession onDemand;
    private final InteractionStreamWait interactionWait;

    private SpringAiToolCallingAdvisor(
            ToolCallingManager toolCallingManager,
            ToolExecutionEligibilityChecker eligibilityChecker,
            int advisorOrder,
            StepContextProjector projector,
            ToolCatalogSession catalog,
            ProviderContextBoundary boundary,
            OnDemandContextSession onDemand,
            InteractionStreamWait interactionWait) {
        super(toolCallingManager, eligibilityChecker, advisorOrder, false);
        this.projector = projector;
        this.catalog = catalog;
        this.boundary = boundary;
        this.onDemand = onDemand;
        this.interactionWait = interactionWait;
    }

    static Builder builder(StepContextProjector projector, ToolCatalogSession catalog,
                           ProviderContextBoundary boundary) {
        return new Builder(projector, catalog, boundary, null);
    }

    static Builder builder(StepContextProjector projector, ToolCatalogSession catalog,
                           ProviderContextBoundary boundary, OnDemandContextSession onDemand) {
        return new Builder(projector, catalog, boundary, onDemand);
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(
            ChatClientRequest request, StreamAdvisorChain chain) {
        Flux<ChatClientResponse> responses = super.adviseStream(request, chain);
        if (interactionWait == null) return responses;
        // Tool recursion runs after the provider response has been aggregated and settled.
        // A reserved child/event wait must finish the outer advisor/client aggregators normally;
        // the gateway transfers it to the host after that completion, without a model retry.
        return responses.onErrorResume(failure -> interactionWait.capture(failure)
                ? Flux.empty() : Flux.error(failure));
    }

    @Override
    protected ChatClientRequest doInitializeLoop(
            ChatClientRequest request, CallAdvisorChain chain) {
        return project(request);
    }

    @Override
    protected List<Message> doGetNextInstructionsForToolCall(
            ChatClientRequest request,
            ChatClientResponse response,
            ToolExecutionResult result) {
        return onDemand == null ? projector.project(result.conversationHistory()).messages()
                : result.conversationHistory();
    }

    @Override
    protected ChatClientRequest doInitializeLoopStream(
            ChatClientRequest request, StreamAdvisorChain chain) {
        return project(request);
    }

    @Override
    protected List<Message> doGetNextInstructionsForToolCallStream(
            ChatClientRequest request,
            ChatClientResponse response,
            ToolExecutionResult result) {
        return onDemand == null ? projector.project(result.conversationHistory()).messages()
                : result.conversationHistory();
    }

    @Override
    protected ChatClientRequest doBeforeCall(ChatClientRequest request, CallAdvisorChain chain) {
        return projectAndSelect(request);
    }

    @Override
    protected ChatClientRequest doBeforeStream(ChatClientRequest request, StreamAdvisorChain chain) {
        return projectAndSelect(request);
    }

    private ChatClientRequest project(ChatClientRequest request) {
        if (onDemand != null) return request;
        List<Message> messages = projector.project(request.prompt().getInstructions()).messages();
        Prompt prompt = new Prompt(messages, request.prompt().getOptions());
        return request.mutate().prompt(prompt).build();
    }

    private ChatClientRequest projectAndSelect(ChatClientRequest request) {
        if (onDemand != null) {
            if (!(request.prompt().getOptions() instanceof ToolCallingChatOptions options)) {
                throw new IllegalStateException("on-demand context requires tool calling chat options");
            }
            var selected = onDemand.select(request.prompt().getInstructions());
            boundary.expect(selected.messages(), selected.callbacks(),
                    selected.toolCandidateStepId());
            var chosen = options.mutate().toolCallbacks(selected.callbacks()).build();
            return request.mutate().prompt(new Prompt(selected.messages(), chosen)).build();
        }
        List<Message> messages = projector.project(request.prompt().getInstructions()).messages();
        boundary.expect(messages);
        if (catalog == null) {
            return request.mutate().prompt(new Prompt(messages, request.prompt().getOptions())).build();
        }
        if (!(request.prompt().getOptions() instanceof ToolCallingChatOptions options)) {
            throw new IllegalStateException("tool context policy requires tool calling chat options");
        }
        var projected = catalog.project(messages);
        var selectedOptions = options.mutate().toolCallbacks(projected.callbacks()).build();
        return request.mutate().prompt(new Prompt(messages, selectedOptions)).build();
    }

    /** Builder for the JavaClaw-owned context-projecting ToolCallingAdvisor. */
    static final class Builder extends ToolCallingAdvisor.Builder<Builder> {
        private final StepContextProjector projector;
        private final ToolCatalogSession catalog;
        private final ProviderContextBoundary boundary;
        private final OnDemandContextSession onDemand;
        private InteractionStreamWait interactionWait;

        private Builder(StepContextProjector projector, ToolCatalogSession catalog,
                        ProviderContextBoundary boundary, OnDemandContextSession onDemand) {
            this.projector = projector;
            this.catalog = catalog;
            this.boundary = boundary;
            this.onDemand = onDemand;
        }

        @Override
        protected Builder self() {
            return this;
        }

        Builder interactionWait(InteractionStreamWait value) {
            interactionWait = java.util.Objects.requireNonNull(value, "interactionWait");
            return this;
        }

        @Override
        protected Builder newCopy() {
            return new Builder(projector, catalog, boundary, onDemand);
        }

        @Override
        public Builder copy() {
            Builder copy = newCopy();
            copy.toolCallingManager(getToolCallingManager());
            copy.toolExecutionEligibilityChecker(getToolExecutionEligibilityChecker());
            copy.advisorOrder(getAdvisorOrder());
            copy.conversationHistoryEnabled(false);
            copy.interactionWait = interactionWait;
            return copy;
        }

        @Override
        public SpringAiToolCallingAdvisor build() {
            return new SpringAiToolCallingAdvisor(
                    getToolCallingManager(), getToolExecutionEligibilityChecker(),
                    getAdvisorOrder(), projector, catalog, boundary, onDemand, interactionWait);
        }
    }
}
