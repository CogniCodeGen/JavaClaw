package com.javaclaw.framework.core;

import java.util.concurrent.CompletionStage;

/** Provider-neutral ReAct reasoning port implemented once by framework.springai. */
public interface ReasoningGateway {
    CompletionStage<ReasoningResult> execute(ReasoningRequest request);
}
