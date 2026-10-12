package com.javaclaw.framework.api;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

/** Ordered thread history, including its turn and step events. */
public record ThreadEvent(RunScope scope, long sequence, Instant timestamp, String type,
                          TurnId turnId, JsonNode payload) {
    public ThreadEvent { payload = payload.deepCopy(); }
    @Override public JsonNode payload() { return payload.deepCopy(); }
}
