package com.javaclaw.server.transport;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.databind.JsonNode;

/** Immutable method-to-domain routing table with duplicate-route detection. */
final class RpcRouter implements AutoCloseable {
    private final Map<String, RpcHandler> routes;
    private final List<? extends RpcHandler> handlers;

    RpcRouter(List<? extends RpcHandler> handlers) {
        this.handlers = List.copyOf(Objects.requireNonNull(handlers, "handlers"));
        LinkedHashMap<String, RpcHandler> registered = new LinkedHashMap<>();
        for (RpcHandler handler : this.handlers) {
            Objects.requireNonNull(handler, "handler");
            for (String method : handler.methods()) {
                RpcHandler duplicate = registered.putIfAbsent(method, handler);
                if (duplicate != null) {
                    throw new IllegalArgumentException("duplicate RPC route: " + method);
                }
            }
        }
        routes = Map.copyOf(registered);
    }

    JsonNode dispatch(String method, JsonNode params) {
        RpcHandler handler = routes.get(method);
        if (handler == null) {
            throw new MethodNotFound(method);
        }
        return handler.handle(method, params);
    }

    boolean handles(String method) {
        return routes.containsKey(method);
    }

    @Override
    public void close() {
        RuntimeException aggregate = null;
        for (int index = handlers.size() - 1; index >= 0; index--) {
            try {
                handlers.get(index).close();
            } catch (Exception failure) {
                if (aggregate == null) {
                    aggregate = new IllegalStateException("RPC handler could not close");
                }
                aggregate.addSuppressed(failure);
            }
        }
        if (aggregate != null) {
            throw aggregate;
        }
    }

    static final class MethodNotFound extends IllegalArgumentException {
        MethodNotFound(String method) {
            super("method not found: " + method);
        }
    }
}
