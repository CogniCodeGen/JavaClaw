package com.javaclaw.framework.api;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TaskResultJsonTest {
    @Test
    void roundTripsEvidenceAndRejectsMalformedOutcome() {
        TaskResult result = new TaskResult(TaskOutcome.PARTIAL,
                List.of("calendar.agenda"), "观察失败", List.of("run:1/event:2"));
        assertEquals(result, TaskResultJson.decode(TaskResultJson.encode(result)));
        assertEquals(TaskResult.delivered(),
                TaskResultJson.decode(TaskResultJson.encode(TaskResult.delivered())));
        assertNull(TaskResultJson.decode(JsonNodeFactory.instance.objectNode()
                .put("outcome", "COMPLETED")));
    }
}
