package com.javaclaw.server.transport;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Flow;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.javaclaw.agent.runtime.LiveItemSource;
import com.javaclaw.agent.runtime.RuntimeStreams;
import com.javaclaw.agent.runtime.ThreadUseCases;
import com.javaclaw.core.api.ItemId;
import com.javaclaw.core.api.LiveItemEvent;
import com.javaclaw.core.api.ThreadEvent;
import com.javaclaw.core.api.ThreadId;
import com.javaclaw.protocol.InitializeParams;
import com.javaclaw.protocol.InitializeResult;
import com.javaclaw.protocol.JsonRpcError;
import com.javaclaw.protocol.JsonRpcFrame;
import com.javaclaw.protocol.JsonRpcNotification;
import com.javaclaw.protocol.JsonRpcRequest;
import com.javaclaw.protocol.JsonRpcResponse;
import com.javaclaw.protocol.ProtocolVersion;
import com.javaclaw.protocol.RpcMethods;

/** One local JSON-RPC connection: handshake, routing, replay cursors and notifications. */
public final class AppServerSession implements AutoCloseable, ThreadSubscriptionAccess {
    private final ThreadUseCases threads;
    private final ObjectMapper json;
    private final ProtocolMapper wire;
    private final Consumer<JsonRpcNotification> notifications;
    private final LiveItemSource liveItems;
    private final Map<String, Boolean> capabilities;
    private final RpcRouter router;
    private final String connectionId = "conn_" + UUID.randomUUID().toString().replace("-", "");
    private final Map<ThreadId, SubscriptionState> cursors = new ConcurrentHashMap<>();
    private volatile boolean initialized;
    private volatile Flow.Subscription liveSubscription;
    private volatile Flow.Subscription liveItemSubscription;

    AppServerSession(
            ThreadUseCases threads,
            Flow.Publisher<ThreadEvent> liveEvents,
            ObjectMapper json,
            Consumer<JsonRpcNotification> notifications,
            LiveItemSource liveItems,
            SessionRpcApi api) {
        this.threads = Objects.requireNonNull(threads, "threads");
        this.json = Objects.requireNonNull(json, "json");
        this.wire = new ProtocolMapper(json);
        this.notifications = Objects.requireNonNull(notifications, "notifications");
        this.liveItems = Objects.requireNonNull(liveItems, "liveItems");
        SessionRpcApi enabled = Objects.requireNonNull(api, "api");
        capabilities = Map.copyOf(enabled.capabilities());
        router = enabled.router(this, notifications);
        if (liveEvents != null) {
            liveEvents.subscribe(new LiveSubscriber());
        }
        if (liveItems != LiveItemSource.EMPTY) {
            liveItems.subscribe(new LiveItemSubscriber());
        }
    }

    /** 处理单个连接帧并推进初始化、路由或订阅状态。请求返回响应，通知及客户端响应返回空值；调用方应按连接输入顺序调用。 */
    public Optional<JsonRpcResponse> handle(JsonRpcFrame frame) {
        Objects.requireNonNull(frame, "frame");
        if (frame instanceof JsonRpcNotification notification) {
            handleNotification(notification);
            return Optional.empty();
        }
        if (!(frame instanceof JsonRpcRequest request)) {
            return Optional.empty();
        }
        try {
            if (!initialized && !RpcMethods.INITIALIZE.equals(request.method())) {
                return Optional.of(
                        failure(request, JsonRpcError.NOT_INITIALIZED, "initialize must be the first request", null));
            }
            JsonNode result =
                    switch (request.method()) {
                        case RpcMethods.INITIALIZE -> initialize(request.params());
                        case RpcMethods.CAPABILITIES -> json.valueToTree(capabilities);
                        default -> router.dispatch(request.method(), request.params());
                    };
            return Optional.of(JsonRpcResponse.success(request.id(), result));
        } catch (RpcRouter.MethodNotFound failure) {
            return Optional.of(failure(request, JsonRpcError.METHOD_NOT_FOUND, failure.getMessage(), null));
        } catch (UnsupportedVersion failure) {
            return Optional.of(failure(request, JsonRpcError.UNSUPPORTED_VERSION, failure.getMessage(), null));
        } catch (NoSuchElementException failure) {
            return Optional.of(failure(request, JsonRpcError.NOT_FOUND, failure.getMessage(), null));
        } catch (IllegalStateException failure) {
            return Optional.of(failure(request, JsonRpcError.CONFLICT, failure.getMessage(), null));
        } catch (IllegalArgumentException failure) {
            return Optional.of(failure(request, JsonRpcError.INVALID_PARAMS, failure.getMessage(), null));
        } catch (Throwable failure) {
            ObjectNode data = JsonNodeFactory.instance.objectNode();
            data.put("exception", failure.getClass().getName());
            return Optional.of(failure(request, JsonRpcError.INTERNAL_ERROR, "internal app server error", data));
        }
    }

    private JsonNode initialize(JsonNode params) {
        if (initialized) {
            throw new IllegalStateException("connection is already initialized");
        }
        InitializeParams value;
        try {
            value = json.treeToValue(params, InitializeParams.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new IllegalArgumentException("invalid initialize parameters", failure);
        }
        if (value.protocolVersion() != ProtocolVersion.CURRENT) {
            throw new UnsupportedVersion(value.protocolVersion());
        }
        initialized = true;
        return json.valueToTree(new InitializeResult(
                ProtocolVersion.CURRENT, "JavaClaw App Server", "4.0.0-SNAPSHOT", capabilities, connectionId));
    }

    @Override
    public List<ThreadEvent> subscribeAndRead(ThreadId threadId, long afterSequence) {
        if (afterSequence < 0) {
            throw new IllegalArgumentException("afterSequence must be non-negative");
        }
        SubscriptionState fresh = new SubscriptionState(afterSequence);
        SubscriptionState state;
        synchronized (fresh) {
            state = cursors.putIfAbsent(threadId, fresh);
            if (state == null) {
                return readReplayLocked(threadId, afterSequence, fresh);
            }
        }
        synchronized (state) {
            return readReplayLocked(threadId, afterSequence, state);
        }
    }

    @Override
    public void register(ThreadId threadId, long sequence) {
        SubscriptionState fresh = new SubscriptionState(sequence);
        synchronized (fresh) {
            SubscriptionState existing = cursors.putIfAbsent(threadId, fresh);
            if (existing == null) {
                return;
            }
            synchronized (existing) {
                existing.sequence = Math.max(existing.sequence, sequence);
            }
        }
    }

    @Override
    public List<LiveItemEvent> activeItems(ThreadId threadId) {
        return liveItems.active(threadId);
    }

    @Override
    public void remove(ThreadId threadId) {
        cursors.remove(threadId);
    }

    private List<ThreadEvent> readReplayLocked(ThreadId threadId, long afterSequence, SubscriptionState state) {
        List<ThreadEvent> replay = threads.eventsAfter(threadId, afterSequence, 10_000);
        long replaySequence =
                replay.isEmpty() ? afterSequence : replay.getLast().sequence();
        state.sequence = Math.max(state.sequence, replaySequence);
        return replay;
    }

    private void emit(ThreadEvent event) {
        SubscriptionState state = cursors.get(event.threadId());
        if (state == null) {
            return;
        }
        synchronized (state) {
            if (event.sequence() <= state.sequence) {
                return;
            }
            if (event.sequence() != state.sequence + 1) {
                notifyResync(event.threadId().value(), state.sequence);
                state.sequence = event.sequence();
                return;
            }
            notifications.accept(new JsonRpcNotification("2.0", notificationMethod(event), notificationPayload(event)));
            state.sequence = event.sequence();
        }
    }

    private JsonNode notificationPayload(ThreadEvent event) {
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        result.set("event", json.valueToTree(wire.event(event)));
        String itemId = event.payload().get("itemId");
        if (itemId != null) {
            threads.readItem(new ItemId(itemId))
                    .ifPresent(item -> result.set("item", json.valueToTree(wire.item(item))));
        }
        if (event.turnId() != null) {
            threads.readTurn(event.turnId()).ifPresent(turn -> result.set("turn", json.valueToTree(wire.turn(turn))));
        }
        return result;
    }

    private static String notificationMethod(ThreadEvent event) {
        return switch (event.type()) {
            case "turn/queued", "turn/started" -> RpcMethods.TURN_STARTED;
            case "turn/completed" -> RpcMethods.TURN_COMPLETED;
            case "item/started" -> RpcMethods.ITEM_STARTED;
            case "item/completed" -> RpcMethods.ITEM_COMPLETED;
            case "approval/requested" -> RpcMethods.APPROVAL_REQUESTED;
            case "userInput/requested" -> RpcMethods.USER_INPUT_REQUESTED;
            case "usage/updated" -> RpcMethods.USAGE_UPDATED;
            default -> RpcMethods.THREAD_EVENT;
        };
    }

    private void notifyResync(String threadId, long afterSequence) {
        ObjectNode params = JsonNodeFactory.instance.objectNode();
        params.put("threadId", threadId);
        params.put("afterSequence", afterSequence);
        notifications.accept(new JsonRpcNotification("2.0", RpcMethods.RESYNC_REQUIRED, params));
    }

    private void handleNotification(JsonRpcNotification notification) {
        if (RpcMethods.INITIALIZED.equals(notification.method()) && initialized) {
            return;
        }
        // Notifications never receive a JSON-RPC response.
    }

    private static JsonRpcResponse failure(JsonRpcRequest request, int code, String message, JsonNode data) {
        return JsonRpcResponse.failure(request.id(), code, message == null ? "request failed" : message, data);
    }

    @Override
    public void close() {
        Flow.Subscription subscription = liveSubscription;
        if (subscription != null) {
            subscription.cancel();
        }
        Flow.Subscription itemSubscription = liveItemSubscription;
        if (itemSubscription != null) {
            itemSubscription.cancel();
        }
        router.close();
        cursors.clear();
    }

    private final class LiveItemSubscriber implements Flow.Subscriber<LiveItemEvent> {
        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            liveItemSubscription = subscription;
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(LiveItemEvent event) {
            if (event.phase() != LiveItemEvent.Phase.DELTA || !cursors.containsKey(event.threadId())) {
                return;
            }
            ObjectNode params = JsonNodeFactory.instance.objectNode();
            params.put("threadId", event.threadId().value());
            params.put("turnId", event.turnId().value());
            params.put("itemId", event.itemId().value());
            params.put("kind", event.kind());
            params.put("deltaSequence", event.deltaSequence());
            params.set("delta", wire.itemDelta(event.delta()));
            params.put("timestamp", event.timestamp().toString());
            notifications.accept(new JsonRpcNotification("2.0", RpcMethods.ITEM_DELTA, params));
        }

        @Override
        public void onError(Throwable throwable) {
            cursors.forEach((threadId, state) -> notifyResync(threadId.value(), state.sequence));
        }

        @Override
        public void onComplete() {}
    }

    private final class LiveSubscriber implements RuntimeStreams.ResyncAwareSubscriber {
        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            liveSubscription = subscription;
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(ThreadEvent item) {
            emit(item);
        }

        @Override
        public void onError(Throwable throwable) {
            cursors.forEach((threadId, state) -> notifyResync(threadId.value(), state.sequence));
        }

        @Override
        public void onComplete() {}

        @Override
        public void resyncRequired(String threadId, long afterSequence) {
            SubscriptionState state = cursors.get(new ThreadId(threadId));
            notifyResync(threadId, state == null ? afterSequence : state.sequence);
        }
    }

    private static final class SubscriptionState {
        private long sequence;

        private SubscriptionState(long sequence) {
            this.sequence = sequence;
        }
    }

    private static final class UnsupportedVersion extends IllegalArgumentException {
        private UnsupportedVersion(int version) {
            super("unsupported protocol version " + version + "; supported version is " + ProtocolVersion.CURRENT);
        }
    }
}
