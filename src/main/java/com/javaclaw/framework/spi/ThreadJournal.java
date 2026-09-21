package com.javaclaw.framework.spi;

import com.fasterxml.jackson.databind.JsonNode;
import com.javaclaw.framework.api.RunScope;

/** Durable idempotent append for graph revisions and other thread-owned projections. */
public interface ThreadJournal {
    long appendOnce(RunScope scope, String mutationId, String type, JsonNode payload);
}
