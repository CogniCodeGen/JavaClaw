package com.javaclaw.framework.springai;

import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientAsync;
import com.openai.core.JsonField;
import com.openai.core.http.AsyncStreamResponse;
import com.openai.models.chat.completions.ChatCompletionChunk;
import com.openai.models.chat.completions.ChatCompletionChunk.Choice;
import com.openai.models.chat.completions.ChatCompletionChunk.Choice.Delta.ToolCall;
import com.openai.services.async.ChatServiceAsync;
import com.openai.services.async.chat.ChatCompletionServiceAsync;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.setup.OpenAiSetup;
import reactor.core.publisher.Flux;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/** Observes typed SDK deltas before Spring AI aggregates tool arguments. */
final class OpenAiReplyStreamingChatModel implements ReplyStreamingChatModel {
    private final OpenAiChatOptions options;
    private final ObservationRegistry observations;
    private final OpenAIClient sync;
    private final OpenAIClientAsync async;
    private final OpenAiChatModel delegate;

    OpenAiReplyStreamingChatModel(OpenAiChatOptions options, ObservationRegistry observations,
                                 List<AutoCloseable> resources) {
        this.options = options;
        this.observations = observations;
        sync = OpenAiSetup.setupSyncClient(options.getBaseUrl(), options.getApiKey(),
                options.getCredential(), options.getMicrosoftDeploymentName(),
                options.getMicrosoftFoundryServiceVersion(), options.getOrganizationId(),
                options.isMicrosoftFoundry(), options.isGitHubModels(), options.getModel(),
                options.getTimeout(), options.getMaxRetries(), options.getProxy(),
                options.getCustomHeaders(), observations, null, List.of());
        resources.add(sync::close);
        async = OpenAiSetup.setupAsyncClient(options.getBaseUrl(), options.getApiKey(),
                options.getCredential(), options.getMicrosoftDeploymentName(),
                options.getMicrosoftFoundryServiceVersion(), options.getOrganizationId(),
                options.isMicrosoftFoundry(), options.isGitHubModels(), options.getModel(),
                options.getTimeout(), options.getMaxRetries(), options.getProxy(),
                options.getCustomHeaders(), observations, null, List.of());
        resources.add(async::close);
        delegate = model(async);
    }

    private OpenAiChatModel model(OpenAIClientAsync client) {
        return OpenAiChatModel.builder().options(options).observationRegistry(observations)
                .openAiClient(sync).openAiClientAsync(client).build();
    }

    @Override public ChatResponse call(Prompt prompt) { return delegate.call(prompt); }
    @Override public ChatOptions getOptions() { return delegate.getOptions(); }
    @Override public Flux<ChatResponse> stream(Prompt prompt) { return delegate.stream(prompt); }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt, DecisionReplyStream reply) {
        // Each physical subscription gets its own closed-over scope. No header, global registry,
        // or thread-local association is sent through the provider or recursive advisor.
        return model(observe(async, reply)).stream(prompt);
    }

    private static OpenAIClientAsync observe(OpenAIClientAsync client, DecisionReplyStream reply) {
        return proxy(OpenAIClientAsync.class, client, (method, args) -> {
            Object value = invoke(client, method, args);
            if (method.getName().equals("chat")) return observeChat((ChatServiceAsync) value, reply);
            if (method.getName().equals("withOptions")) return observe((OpenAIClientAsync) value, reply);
            return value;
        });
    }

    private static ChatServiceAsync observeChat(ChatServiceAsync chat, DecisionReplyStream reply) {
        return proxy(ChatServiceAsync.class, chat, (method, args) -> {
            Object value = invoke(chat, method, args);
            if (method.getName().equals("completions")) {
                return proxy(ChatCompletionServiceAsync.class, value, (completion, parameters) -> {
                    Object response = invoke(value, completion, parameters);
                    if (completion.getName().equals("createStreaming")) {
                        @SuppressWarnings("unchecked")
                        AsyncStreamResponse<ChatCompletionChunk> stream =
                                (AsyncStreamResponse<ChatCompletionChunk>) response;
                        reply.attachTransport(stream::close);
                        return observeStream(stream, reply);
                    }
                    return response;
                });
            }
            return value;
        });
    }

    private static AsyncStreamResponse<ChatCompletionChunk> observeStream(
            AsyncStreamResponse<ChatCompletionChunk> stream, DecisionReplyStream reply) {
        CompletableFuture<Void> completion = new CompletableFuture<>();
        stream.onCompleteFuture().whenComplete((unused, error) -> {
            if (error == null) completion.complete(null);
            else completion.completeExceptionally(error);
        });
        return new AsyncStreamResponse<>() {
            private final CompleteToolCalls tools = new CompleteToolCalls();
            private Handler<ChatCompletionChunk> observer(Handler<? super ChatCompletionChunk> target) {
                return new Handler<>() {
                    @Override public void onNext(ChatCompletionChunk chunk) {
                        for (var choice : chunk.choices()) {
                            if (choice.index() != 0) { reply.invalidate(); continue; }
                            choice.delta().toolCalls().ifPresent(calls -> calls.forEach(call ->
                                    reply.toolDelta(call.index(), call.id().orElse(""),
                                            call.function().flatMap(function -> function.name()).orElse(""),
                                            call.function().flatMap(function -> function.arguments()).orElse(""))));
                        }
                        target.onNext(tools.accept(chunk));
                    }
                    @Override public void onComplete(Optional<Throwable> error) {
                        Optional<Throwable> outcome = error;
                        try {
                            // Spring AI 2.0 treats every present tool ID as a new call and can
                            // leave repeated-ID/name-late fragments incomplete. Keep raw draft
                            // observation live, but expose complete typed calls only at EOF.
                            if (error.isEmpty()) tools.complete().ifPresent(target::onNext);
                        } catch (Throwable failure) {
                            outcome = Optional.of(failure);
                        }
                        try {
                            target.onComplete(outcome);
                        } catch (Throwable notificationFailure) {
                            if (outcome.isPresent()) {
                                if (outcome.get() != notificationFailure) outcome.get().addSuppressed(notificationFailure);
                            }
                            else outcome = Optional.of(notificationFailure);
                        } finally {
                            if (outcome.isPresent()) completion.completeExceptionally(outcome.get());
                            else completion.complete(null);
                        }
                    }
                };
            }
            @Override public AsyncStreamResponse<ChatCompletionChunk> subscribe(Handler<? super ChatCompletionChunk> handler) {
                stream.subscribe(observer(handler));
                return this;
            }
            @Override public AsyncStreamResponse<ChatCompletionChunk> subscribe(
                    Handler<? super ChatCompletionChunk> handler, Executor executor) {
                stream.subscribe(observer(handler), executor);
                return this;
            }
            @Override public CompletableFuture<Void> onCompleteFuture() { return completion; }
            @Override public void close() { stream.close(); }
        };
    }

    /** Transport compatibility only: no tool is interpreted or executed by this assembler. */
    private static final class CompleteToolCalls {
        private final Map<Long, Choice> choices = new LinkedHashMap<>();
        private final Map<CallKey, ToolFragments> calls = new LinkedHashMap<>();
        private final Map<String, CallKey> identities = new LinkedHashMap<>();
        private ChatCompletionChunk template;

        ChatCompletionChunk accept(ChatCompletionChunk chunk) {
            template = chunk;
            var withoutCalls = chunk.choices().stream().map(choice -> {
                choices.put(choice.index(), choice);
                choice.delta().toolCalls().ifPresent(fragments -> fragments.forEach(fragment -> {
                    if (fragment.index() < 0) throw new IllegalStateException("invalid provider tool index");
                    CallKey key = new CallKey(choice.index(), fragment.index());
                    fragment.id().filter(id -> !id.isEmpty()).ifPresent(id -> {
                        CallKey previous = identities.putIfAbsent(id, key);
                        if (previous != null && !previous.equals(key)) {
                            throw new IllegalStateException("provider tool ID belongs to conflicting indexes");
                        }
                    });
                    calls.computeIfAbsent(key, ignored -> new ToolFragments()).append(fragment);
                }));
                return choice.toBuilder().delta(choice.delta().toBuilder()
                        .toolCalls(JsonField.<List<ToolCall>>ofNullable(null)).build()).build();
            }).toList();
            return chunk.toBuilder().choices(withoutCalls).build();
        }

        Optional<ChatCompletionChunk> complete() {
            if (calls.isEmpty()) return Optional.empty();
            if (choices.size() != 1 || !choices.containsKey(0L)) {
                throw new IllegalStateException("ambiguous provider tool choices");
            }
            var completeChoices = choices.values().stream().map(choice -> {
                var completeCalls = calls.entrySet().stream()
                        .filter(entry -> entry.getKey().choice() == choice.index())
                        .map(entry -> entry.getValue().complete()).toList();
                // Content/refusal already travelled in their original chunks. Replay only tools,
                // retaining the original choice, finish reason and response usage/metadata.
                return choice.toBuilder().delta(Choice.Delta.builder()
                        .toolCalls(completeCalls).build()).build();
            }).toList();
            return Optional.of(template.toBuilder().choices(completeChoices).build());
        }
    }

    private record CallKey(long choice, long index) { }

    private static final class ToolFragments {
        private ToolCall template;
        private ToolCall.Function function;
        private String id;
        private final StringBuilder name = new StringBuilder();
        private final StringBuilder arguments = new StringBuilder();
        private boolean hasArguments;

        void append(ToolCall fragment) {
            fragment.type().ifPresent(type -> {
                if (!ToolCall.Type.FUNCTION.equals(type)) {
                    throw new IllegalStateException("unsupported provider tool call type");
                }
            });
            if (template == null) template = fragment;
            else template = template.toBuilder()
                    .putAllAdditionalProperties(fragment._additionalProperties()).build();
            fragment.type().ifPresent(type -> template = template.toBuilder().type(type).build());
            fragment.id().filter(value -> !value.isEmpty()).ifPresent(value -> {
                if (id != null && !id.equals(value)) {
                    throw new IllegalStateException("provider tool ID changed within one index");
                }
                id = value;
            });
            fragment.function().ifPresent(value -> {
                if (function == null) function = value;
                else function = function.toBuilder()
                        .putAllAdditionalProperties(value._additionalProperties()).build();
                value.name().filter(part -> !part.isEmpty()).ifPresent(part -> {
                    name.append(part);
                });
                value.arguments().ifPresent(part -> {
                    hasArguments = true;
                    arguments.append(part);
                });
            });
        }

        ToolCall complete() {
            if (id == null || name.isEmpty() || function == null || !hasArguments) {
                throw new IllegalStateException("provider tool call is incomplete");
            }
            return template.toBuilder().id(id).function(function.toBuilder()
                    .name(name.toString()).arguments(arguments.toString()).build()).build();
        }
    }

    @FunctionalInterface private interface Invocation { Object invoke(Method method, Object[] args) throws Throwable; }
    private static <T> T proxy(Class<T> type, Object target, Invocation invocation) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (ignored, method, args) -> invocation.invoke(method, args)));
    }
    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try { return method.invoke(target, args); }
        catch (InvocationTargetException failure) { throw failure.getCause(); }
    }
}
