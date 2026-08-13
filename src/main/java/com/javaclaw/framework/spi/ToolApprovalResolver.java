package com.javaclaw.framework.spi;

import com.javaclaw.framework.api.RunRequest;
import com.javaclaw.framework.api.ToolApprovalChallenge;
import com.javaclaw.framework.api.ToolApprovalGrant;

import java.util.concurrent.CompletionStage;

/** Product boundary that turns a direct-tool approval challenge into a one-shot grant. */
@FunctionalInterface
public interface ToolApprovalResolver {
    CompletionStage<ToolApprovalGrant> resolve(
            ToolApprovalChallenge challenge, RunRequest request);
}
