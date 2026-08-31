package com.javaclaw.core.api;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ThreadItemTest {
    @Test
    void exposesStableWireKinds() {
        List<ThreadItem> values = List.of(
                new ThreadItem.UserMessage("hello"),
                new ThreadItem.AgentMessage("world"),
                new ThreadItem.ErrorItem("failed", "boom", false));
        assertEquals(
                List.of("userMessage", "agentMessage", "error"),
                values.stream().map(ThreadItem::kind).toList());
    }
}
