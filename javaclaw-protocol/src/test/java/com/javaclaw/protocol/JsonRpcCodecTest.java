package com.javaclaw.protocol;

import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JsonRpcCodecTest {
    private final JsonRpcCodec codec = new JsonRpcCodec();

    @Test
    void preservesTheStandardJsonRpcHeader() throws Exception {
        var request =
                new JsonRpcRequest("2.0", IntNode.valueOf(1), "thread/list", JsonNodeFactory.instance.objectNode());
        String encoded = codec.encode(request);
        JsonRpcRequest decoded = assertInstanceOf(JsonRpcRequest.class, codec.decode(encoded));
        assertEquals("2.0", decoded.jsonrpc());
        assertEquals("thread/list", decoded.method());
        assertEquals(1, decoded.id().asInt());
    }

    @Test
    void rejectsFramesWithoutVersion() {
        assertThrows(IllegalArgumentException.class, () -> codec.decode("{\"id\":1,\"method\":\"thread/list\"}"));
    }

    @Test
    void rejectsCoercedMethodsFractionalIdsAndScalarParams() {
        assertThrows(
                IllegalArgumentException.class, () -> codec.decode("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":42}"));
        assertThrows(
                IllegalArgumentException.class,
                () -> codec.decode("{\"jsonrpc\":\"2.0\",\"id\":1.5,\"method\":\"thread/list\"}"));
        assertThrows(
                IllegalArgumentException.class,
                () -> codec.decode("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"thread/list\",\"params\":true}"));
    }

    @Test
    void rejectsAmbiguousOrMalformedResponses() {
        assertThrows(
                IllegalArgumentException.class,
                () -> codec.decode(
                        "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{},\"error\":{\"code\":-1,\"message\":\"bad\"}}"));
        assertThrows(
                IllegalArgumentException.class,
                () -> codec.decode("{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":\"-1\",\"message\":\"bad\"}}"));
        assertThrows(IllegalArgumentException.class, () -> codec.decode("{\"jsonrpc\":\"2.0\",\"id\":1}"));
    }

    @Test
    void enforcesDefaultParserNestingLimit() {
        String nested = "[".repeat(JsonRpcCodec.MAX_NESTING_DEPTH + 1) + "]".repeat(JsonRpcCodec.MAX_NESTING_DEPTH + 1);
        String line = "{\"jsonrpc\":\"2.0\",\"method\":\"test/event\",\"params\":" + nested + "}";
        assertThrows(com.fasterxml.jackson.core.exc.StreamConstraintsException.class, () -> codec.decode(line));
    }

    @Test
    void unknownItemKindsRoundTripAsOpenPayload() throws Exception {
        String line = "{\"jsonrpc\":\"2.0\",\"method\":\"item/completed\","
                + "\"params\":{\"kind\":\"futureItem\",\"payload\":{\"x\":1}}}";
        JsonRpcNotification decoded = assertInstanceOf(JsonRpcNotification.class, codec.decode(line));
        assertEquals("futureItem", decoded.params().path("kind").asText());
    }
}
