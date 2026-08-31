package com.javaclaw.browser;

import java.io.StringReader;
import java.io.StringWriter;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BrowserServiceMainTest {
    @Test
    void requiresInitializationAndNegotiatesANetworklessProtocol() throws Exception {
        String input = """
                {"jsonrpc":"2.0","id":"early","method":"health","params":{}}
                {"jsonrpc":"2.0","id":"init","method":"initialize","params":{"protocolVersion":1}}
                {"jsonrpc":"2.0","id":"health","method":"health","params":{}}
                {"jsonrpc":"2.0","id":"stop","method":"shutdown","params":{}}
                """;
        StringWriter output = new StringWriter();
        BrowserServiceMain.serve(new StringReader(input), output);
        var lines = output.toString().lines().toList();
        ObjectMapper json = new ObjectMapper();
        assertEquals(
                -32002, json.readTree(lines.get(0)).path("error").path("code").asInt());
        assertEquals(
                "disabled",
                json.readTree(lines.get(1)).path("result").path("network").asText());
        assertEquals(
                "ok", json.readTree(lines.get(2)).path("result").path("status").asText());
        assertEquals(
                true, json.readTree(lines.get(3)).path("result").path("stopped").asBoolean());
    }
}
