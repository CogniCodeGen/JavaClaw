package com.javaclaw.sdk;

import java.io.ByteArrayOutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.javaclaw.protocol.JsonRpcCodec;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonRpcConnectionTest {
    @Test
    void oversizedServerFrameTerminatesConnectionAndFailsPendingRequest() throws Exception {
        try (PipedInputStream clientInput = new PipedInputStream();
                PipedOutputStream serverOutput = new PipedOutputStream(clientInput);
                JsonRpcConnection connection =
                        new JsonRpcConnection(clientInput, new ByteArrayOutputStream(), new JsonRpcCodec(), 32)) {
            var pending = connection.request(
                    "thread/list", new JsonRpcCodec().mapper().createObjectNode());

            serverOutput.write(("x".repeat(33) + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            serverOutput.flush();

            ExecutionException failure = assertThrows(ExecutionException.class, () -> pending.get(2, TimeUnit.SECONDS));
            assertTrue(failure.getCause().getMessage().contains("exceeds 32"));
            assertThrows(IllegalStateException.class, () -> connection.request("thread/list", null));
        }
    }
}
