package com.javaclaw.framework.spi;

import com.fasterxml.jackson.databind.JsonNode;
import com.javaclaw.framework.api.InteractionMode;
import com.javaclaw.framework.api.InteractionResult;
import com.javaclaw.framework.api.InteractionTask;

/** Host-owned delegation and mode transitions; implementations may suspend through the AgentEngine. */
public interface InteractionDelegateGateway {
    InteractionResult delegate(ToolContext parent, ToolExecutionContext execution, InteractionTask task);

    JsonNode selectMode(ToolContext child, ToolExecutionContext execution, InteractionMode mode);

    /** 可选业务检查点只保存必要数据，不产生观察证明、句柄或新权限。 */
    default JsonNode selectMode(ToolContext child, ToolExecutionContext execution,
            InteractionMode mode, JsonNode checkpoint) {
        return selectMode(child, execution, mode);
    }

    /** Waits on host event delivery, rather than issuing repeated observations or blocking tool polls. */
    default JsonNode waitForEvent(ToolContext child, ToolExecutionContext execution, JsonNode input) {
        throw new UnsupportedOperationException("interaction event waiting is unavailable");
    }

    /** Bounded business-result retrieval, subject to the same parent ownership and scope checks. */
    default JsonNode readResult(ToolContext parent, ToolExecutionContext execution, JsonNode input) {
        throw new UnsupportedOperationException("interaction result retrieval is unavailable");
    }
}
