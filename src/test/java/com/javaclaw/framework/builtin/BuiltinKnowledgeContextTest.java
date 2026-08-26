package com.javaclaw.framework.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.javaclaw.framework.api.*;
import com.javaclaw.framework.spi.RetrieverContribution;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuiltinKnowledgeContextTest {

    @Test
    void missingAndTrueGatePreserveRetrievalWhileFalseDisablesIt() {
        AtomicInteger calls = new AtomicInteger();
        RetrieverContribution retriever = (query, request) -> {
            calls.incrementAndGet();
            return List.of(JsonNodeFactory.instance.textNode("knowledge"));
        };

        assertEquals(1, BuiltinExtensionCatalog.boundedKnowledgeContext(
                retriever, "query", request(Map.of())).size());
        assertEquals(1, BuiltinExtensionCatalog.boundedKnowledgeContext(
                retriever, "query", request(Map.of("framework.enableKnowledgeContext",
                        JsonNodeFactory.instance.booleanNode(true)))).size());
        assertTrue(BuiltinExtensionCatalog.boundedKnowledgeContext(
                retriever, "query", request(Map.of("framework.enableKnowledgeContext",
                        JsonNodeFactory.instance.booleanNode(false)))).isEmpty());
        assertEquals(2, calls.get());
    }

    @Test
    void retrievedContextRemainsBoundedToThreeDocumentsAndFifteenHundredTokens() {
        String large = "knowledge ".repeat(1_000);
        RetrieverContribution retriever = (query, request) -> List.of(
                text(large), text(large), text(large), text(large));

        List<JsonNode> bounded = BuiltinExtensionCatalog.boundedKnowledgeContext(
                retriever, "query", request(Map.of()));

        assertTrue(bounded.size() <= 3);
        assertTrue(bounded.stream().map(JsonNode::toString)
                .mapToInt(com.javaclaw.util.TokenEstimator::estimate).sum() <= 1_500);
    }

    private static JsonNode text(String value) {
        return JsonNodeFactory.instance.textNode(value);
    }

    private static RunRequest request(Map<String, JsonNode> attributes) {
        return RunRequest.builder()
                .agent(AgentDefinitionRef.latest("agent"))
                .profile(RunProfileRef.latest("chat"))
                .source(InvocationSource.chat())
                .scope(new RunScope("workspace", "user", "session"))
                .input(InputBlock.text("query"))
                .attributes(attributes)
                .permissionCeiling(PermissionSet.NONE)
                .budget(new RunBudget(Duration.ofMinutes(1), 10_000, 1_000,
                        0, BigDecimal.ONE))
                .build();
    }
}
