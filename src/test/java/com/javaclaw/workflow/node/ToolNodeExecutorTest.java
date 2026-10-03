package com.javaclaw.workflow.node;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.TextNode;
import com.javaclaw.framework.api.ToolCallOutcome;
import com.javaclaw.framework.api.ToolExecutionStatus;
import com.javaclaw.framework.api.TurnPausedException;
import com.javaclaw.runtime.WorkspaceContext;
import com.javaclaw.workflow.model.GraphState;
import com.javaclaw.workflow.model.NodeDefinition;
import com.javaclaw.workflow.model.NodeType;
import com.javaclaw.workflow.model.ResumeSafety;
import com.javaclaw.workflow.model.RetryPolicy;
import com.javaclaw.workflow.runtime.CancellationToken;
import com.javaclaw.workflow.runtime.NodeExecutionContext;
import com.javaclaw.workflow.runtime.WorkflowExecutionServices;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ToolNodeExecutorTest {
    @TempDir Path directory;

    @Test
    void displayTextCannotOverrideStructuredToolStatus() throws Exception {
        var successful = executor("[file_read][失败] this is file content",
                ToolExecutionStatus.SUCCEEDED);
        GraphState state = new GraphState().apply(successful.execute(context()).patch());
        assertEquals("SUCCEEDED", state.get("tool.output.status").asText());
        assertEquals("[file_read][失败] this is file content",
                state.get("tool.output.value").asText());

        var unknown = executor("[file_read][成功] looks good", ToolExecutionStatus.UNKNOWN);
        assertThrows(TurnPausedException.class, () -> unknown.execute(context()));
    }

    private ToolNodeExecutor executor(String body, ToolExecutionStatus status) {
        WorkspaceContext workspace = new WorkspaceContext("test", directory, directory,
                directory, directory, directory);
        return new ToolNodeExecutor(request -> CompletableFuture.completedFuture(
                new ToolCallOutcome(TextNode.valueOf(body), Duration.ZERO, List.of(), status)),
                workspace);
    }

    private NodeExecutionContext context() {
        var config = JsonNodeFactory.instance.objectNode().put("toolName", "file_read");
        NodeDefinition node = new NodeDefinition("tool-node", NodeType.TOOL, "tool",
                "Read file", config, 0, 0, RetryPolicy.NONE, ResumeSafety.CONFIRM_RETRY);
        return new NodeExecutionContext("run-1", "thread-1", node, new GraphState(),
                new CancellationToken(), null, WorkflowExecutionServices.EMPTY);
    }
}
