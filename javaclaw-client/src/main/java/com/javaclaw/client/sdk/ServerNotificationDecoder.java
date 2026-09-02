package com.javaclaw.client.sdk;

import java.util.Objects;

import com.javaclaw.client.ServerNotification;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.ExtensionRpcContracts;
import com.javaclaw.protocol.JsonRpcNotification;

/** 把 wire notification 收敛为 SDK 强类型事件。 */
final class ServerNotificationDecoder {
    private ServerNotificationDecoder() {}

    static ServerNotification decode(CanonicalJson json, JsonRpcNotification notification) {
        Objects.requireNonNull(json, "json");
        JsonRpcNotification checked = Objects.requireNonNull(notification, "notification");
        if ("extension/event".equals(checked.method())) {
            return new ServerNotification.ExtensionChanged(
                    json.decode(checked.params(), ExtensionRpcContracts.ExtensionEvent.class));
        }
        return new ServerNotification.Unknown(checked);
    }
}
