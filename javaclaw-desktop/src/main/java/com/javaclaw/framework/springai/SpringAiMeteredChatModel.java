package com.javaclaw.framework.springai;

import com.javaclaw.framework.core.RunUsageLedger;
import com.javaclaw.framework.core.ReasoningRequest;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.MessageAggregator;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Objects;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** 在 Advisor 执行工具或重试前持久化并计量每个 Provider 响应。 */
final class SpringAiMeteredChatModel implements ChatModel {
    private final ChatModel delegate;
    private final ModelStepJournal journal;
    private final AtomicInteger attempt;
    private final Supplier<RunUsageLedger.ModelCall> admission;
    private final Runnable cancelled;
    private final Consumer<ChatResponse> meter;
    private final Consumer<ManagedInferenceChatModel.ManagedInferenceModelException> failureMeter;
    private final StepContextProjector projector;
    private final ToolCatalogSession catalog;
    private final ReasoningRequest request;
    private final ProviderContextBoundary boundary;

    SpringAiMeteredChatModel(
            ChatModel delegate,
            ModelStepJournal journal,
            AtomicInteger attempt,
            Supplier<RunUsageLedger.ModelCall> admission,
            Runnable cancelled,
            Consumer<ChatResponse> meter,
            Consumer<ManagedInferenceChatModel.ManagedInferenceModelException> failureMeter,
            StepContextProjector projector,
            ToolCatalogSession catalog,
            ReasoningRequest request,
            ProviderContextBoundary boundary) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.journal = Objects.requireNonNull(journal, "journal");
        this.attempt = Objects.requireNonNull(attempt, "attempt");
        this.admission = Objects.requireNonNull(admission, "admission");
        this.cancelled = Objects.requireNonNull(cancelled, "cancelled");
        this.meter = Objects.requireNonNull(meter, "meter");
        this.failureMeter = Objects.requireNonNull(failureMeter, "failureMeter");
        this.projector = Objects.requireNonNull(projector, "projector");
        this.catalog = catalog;
        this.request = Objects.requireNonNull(request, "request");
        this.boundary = Objects.requireNonNull(boundary, "boundary");
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        try (var admitted = admission.get()) {
            cancelled.run();
            validate(prompt);
            admitted.requireInputCapacity(ModelInputBudgetPreflight.approximatePromptFloor(prompt));
            var step = journal.started(prompt, attempt.get(),
                    boundary.toolCandidateStepId());
            ChatResponse response = invoke(prompt, step);
            RuntimeException journalFailure = completeJournal(step, response);
            try {
                meter.accept(response);
            } catch (RuntimeException failure) {
                if (journalFailure != null) failure.addSuppressed(journalFailure);
                throw failure;
            }
            if (journalFailure != null) throw journalFailure;
            return response;
        }
    }

    private ChatResponse invoke(Prompt prompt, com.javaclaw.framework.api.StepId step) {
        try {
            ChatResponse response = delegate.call(prompt);
            if (response == null) throw new IllegalStateException("model returned no response");
            return response;
        } catch (ManagedInferenceChatModel.ManagedInferenceModelException failure) {
            failJournal(step, failure);
            try {
                failureMeter.accept(failure);
            } catch (RuntimeException meteringFailure) {
                meteringFailure.addSuppressed(failure);
                throw meteringFailure;
            }
            throw failure;
        } catch (RuntimeException failure) {
            failJournal(step, failure);
            throw failure;
        }
    }

    private RuntimeException completeJournal(
            com.javaclaw.framework.api.StepId step, ChatResponse response) {
        try {
            journal.completed(step, response);
            return null;
        } catch (RuntimeException failure) {
            return failure;
        }
    }

    private void failJournal(com.javaclaw.framework.api.StepId step, RuntimeException failure) {
        try {
            journal.failed(step, failure);
        } catch (RuntimeException journalFailure) {
            failure.addSuppressed(journalFailure);
        }
    }

    @Override
    public ChatOptions getOptions() {
        return delegate.getOptions();
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return Flux.create(sink -> {
            com.javaclaw.framework.api.StepId step = null;
            DecisionReplyStream reply = null;
            boolean settled = false;
            Throwable streamFailure = null;
            CompletableFuture<Void> stopped = new CompletableFuture<>();
            AtomicReference<DecisionReplyStream> displayRef = new AtomicReference<>();
            var registration = request.control().onCancel(() -> stopped.complete(null));
            sink.onCancel(() -> {
                stopped.complete(null);
                DecisionReplyStream display = displayRef.get();
                if (display != null) display.close();
            });
            // ModelCall owns a ReentrantLock: acquisition, budget check, settlement and release
            // stay on this subscription's carrier. Raw SDK draft events still arrive immediately.
            try (var admitted = admission.get()) {
                try {
                    cancelled.run();
                    validate(prompt);
                    admitted.requireInputCapacity(ModelInputBudgetPreflight.approximatePromptFloor(prompt));
                    step = journal.started(prompt, attempt.get(), boundary.toolCandidateStepId());
                    if (DecisionReplyStream.eligible(request, delegate)) {
                        reply = new DecisionReplyStream(request, step, journal.json());
                        displayRef.set(reply);
                    }
                    Flux<ChatResponse> source = reply == null ? delegate.stream(prompt)
                            : ((ReplyStreamingChatModel) delegate).stream(prompt, reply);
                    AtomicReference<ChatResponse> aggregate = new AtomicReference<>();
                    new MessageAggregator().aggregate(source.takeUntilOther(Mono.fromFuture(stopped)), aggregate::set)
                            .doOnNext(value -> { cancelled.run(); if (!sink.isCancelled()) sink.next(value); })
                            .blockLast();
                    cancelled.run();
                    if (sink.isCancelled()) throw new java.util.concurrent.CancellationException(
                            "model stream subscription cancelled");
                    ChatResponse response = aggregate.get();
                    if (response == null) throw new IllegalStateException("model returned no response");
                    RuntimeException journalFailure = completeJournal(step, response);
                    settled = true;
                    try {
                        meter.accept(response);
                    } catch (RuntimeException failure) {
                        if (journalFailure != null) failure.addSuppressed(journalFailure);
                        throw failure;
                    }
                    if (journalFailure != null) throw journalFailure;
                    if (reply != null) reply.complete(response);
                } catch (Throwable failure) {
                    streamFailure = failStreamingCall(step, reply, settled, failure);
                }
            } catch (Throwable failure) {
                if (streamFailure != null && failure != streamFailure) failure.addSuppressed(streamFailure);
                streamFailure = failure;
            } finally {
                try {
                    if (reply != null) reply.close();
                } catch (Throwable closeFailure) {
                    if (streamFailure != null) streamFailure.addSuppressed(closeFailure);
                    else streamFailure = closeFailure;
                } finally {
                    registration.close();
                }
            }
            // Completion, hence downstream guarded tool execution, follows durable settlement.
            if (streamFailure != null) sink.error(streamFailure);
            else sink.complete();
        });
    }

    private Throwable failStreamingCall(com.javaclaw.framework.api.StepId step,
                                       DecisionReplyStream reply, boolean settled, Throwable failure) {
        if (reply != null) {
            try { reply.invalidate(); }
            catch (RuntimeException displayFailure) { failure.addSuppressed(displayFailure); }
        }
        if (step == null || settled) return failure;
        RuntimeException runtime = failure instanceof RuntimeException value ? value
                : new IllegalStateException("model stream failed", failure);
        failJournal(step, runtime);
        if (failure instanceof ManagedInferenceChatModel.ManagedInferenceModelException managed) {
            try { failureMeter.accept(managed); }
            catch (RuntimeException meteringFailure) {
                meteringFailure.addSuppressed(failure);
                return meteringFailure;
            }
        }
        return failure;
    }

    private void validate(Prompt prompt) {
        if (request.plan().descriptor().stepContextPolicy() == null) return;
        if (request.plan().descriptor().onDemandContextPolicy() != null
                && (boundary.expected() == null || !StepMessageCodec.messages(
                        prompt.getInstructions()).equals(StepMessageCodec.messages(boundary.expected())))) {
            throw new IllegalStateException("provider messages differ from the planned step selection");
        }
        projector.validate(prompt.getInstructions(), request, boundary.expected());
        if (!(prompt.getOptions() instanceof ToolCallingChatOptions options)) {
            throw new IllegalStateException("provider request has no tool calling options");
        }
        List<ToolCallback> actual = options.getToolCallbacks() == null
                ? List.of() : options.getToolCallbacks();
        if (request.plan().descriptor().onDemandContextPolicy() != null) {
            if (boundary.expectedTools() == null || !actual.equals(boundary.expectedTools())) {
                throw new IllegalStateException("provider tools differ from the planned step selection");
            }
            if (actual.stream().filter(HarnessDecisionToolCallback.class::isInstance).count() != 1) {
                throw new IllegalStateException("provider selection lacks the trusted control tool");
            }
            List<ToolCallback> business = actual.stream()
                    .filter(callback -> !(callback instanceof HarnessDecisionToolCallback)).toList();
            int characters = business.stream().mapToInt(SpringAiToolCatalog::schemaCharacters).sum();
            if (business.size() > request.plan().descriptor().stepContextPolicy().maxTools()
                    || characters > request.plan().descriptor().stepContextPolicy()
                            .maxToolSchemaCharacters()) {
                throw new IllegalStateException("provider tool selection exceeds context policy");
            }
            return;
        }
        if (catalog == null) {
            if (actual.size() != 1
                    || !(actual.getFirst() instanceof HarnessDecisionToolCallback)) {
                throw new IllegalStateException("provider exposed tools outside the authorized projection");
            }
        } else {
            catalog.validateActual(actual, prompt.getInstructions());
        }
    }
}
