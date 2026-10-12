package com.javaclaw.framework.spi;

import com.fasterxml.jackson.databind.JsonNode;
import com.javaclaw.framework.api.RunRequest;
import java.util.concurrent.CompletionStage;

/** Host event subscription. A wait may observe state but must never dispatch input. */
@FunctionalInterface
public interface InteractionEventSource {
    CompletionStage<JsonNode> await(RunRequest request, JsonNode wait, CancellationToken cancellation);
}
