package com.javaclaw.server.transport;

import java.util.Map;
import java.util.function.Consumer;

import com.javaclaw.protocol.JsonRpcNotification;

/** Per-process RPC API definition; a session contributes only its subscription cursor. */
interface SessionRpcApi {
    RpcRouter router(ThreadSubscriptionAccess subscriptions, Consumer<JsonRpcNotification> notifications);

    Map<String, Boolean> capabilities();
}
