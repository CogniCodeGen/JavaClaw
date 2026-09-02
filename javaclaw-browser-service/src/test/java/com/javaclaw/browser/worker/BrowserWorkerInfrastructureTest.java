package com.javaclaw.browser.worker;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.browser.protocol.BrowserFrameIo;
import com.javaclaw.browser.protocol.BrowserWorkerProtocol;
import com.javaclaw.protocol.CanonicalJson;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BrowserWorkerInfrastructureTest {
    private final CanonicalJson json = new CanonicalJson();

    @TempDir
    Path temporaryDirectory;

    @Test
    void networkChannelFramesRequestAndDefensivelyCopiesResponse() throws Exception {
        BrowserWorkerProtocol.NetworkResponse metadata =
                new BrowserWorkerProtocol.NetworkResponse(206, Map.of("set-cookie", List.of("private=value")), true);
        byte[] hostInput = hostResponse(
                BrowserWorkerProtocol.HostMessage.success(7, 1, json.encode(metadata), 4),
                "body".getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream workerOutput = new ByteArrayOutputStream();
        BrowserNetworkChannel channel =
                new BrowserNetworkChannel(7, new ByteArrayInputStream(hostInput), workerOutput, json);

        byte[] requestBody = "request".getBytes(StandardCharsets.UTF_8);
        BrowserNetworkChannel.NetworkResult result = channel.exchange(request(), requestBody);

        assertEquals(206, result.statusCode());
        assertEquals(true, result.truncated());
        assertArrayEquals("body".getBytes(StandardCharsets.UTF_8), result.body());
        byte[] returned = result.body();
        returned[0] = 0;
        assertArrayEquals("body".getBytes(StandardCharsets.UTF_8), result.body());

        ByteArrayInputStream framed = new ByteArrayInputStream(workerOutput.toByteArray());
        BrowserWorkerProtocol.WorkerMessage message =
                BrowserFrameIo.readJson(framed, json, BrowserWorkerProtocol.WorkerMessage.class);
        assertEquals(7, message.commandId());
        assertEquals(1, message.sequence());
        assertArrayEquals(requestBody, BrowserFrameIo.readBinary(framed, requestBody.length, 1024));
    }

    @Test
    void networkChannelRejectsBrokerDenialIdentityMismatchAndInvalidInput() throws Exception {
        byte[] denied = hostResponse(BrowserWorkerProtocol.HostMessage.failure(7, 1, "NETWORK_DENIED"), new byte[0]);
        BrowserNetworkChannel deniedChannel =
                new BrowserNetworkChannel(7, new ByteArrayInputStream(denied), new ByteArrayOutputStream(), json);
        assertThrows(IllegalStateException.class, () -> deniedChannel.exchange(request(), new byte[0]));

        byte[] mismatched = hostResponse(
                BrowserWorkerProtocol.HostMessage.success(
                        8, 1, json.encode(new BrowserWorkerProtocol.NetworkResponse(204, Map.of(), false)), 0),
                new byte[0]);
        BrowserNetworkChannel mismatchedChannel =
                new BrowserNetworkChannel(7, new ByteArrayInputStream(mismatched), new ByteArrayOutputStream(), json);
        assertThrows(IllegalStateException.class, () -> mismatchedChannel.exchange(request(), new byte[0]));

        assertThrows(
                IllegalArgumentException.class,
                () -> new BrowserNetworkChannel(
                        0, new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream(), json));
        assertThrows(
                NullPointerException.class,
                () -> new BrowserNetworkChannel(1, null, new ByteArrayOutputStream(), json));
        assertThrows(
                NullPointerException.class,
                () -> new BrowserNetworkChannel(1, new ByteArrayInputStream(new byte[0]), null, json));
        assertThrows(
                NullPointerException.class,
                () -> new BrowserNetworkChannel(
                        1, new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream(), null));
    }

    @Test
    void loginControlAcceptsOnlyFixedAsciiDecisionsAndDeletesSignal() throws Exception {
        String sessionId = java.util.UUID.randomUUID().toString();
        Path signal = temporaryDirectory.resolve("login-" + sessionId + ".control");
        try (FileBrowserLoginControl control = new FileBrowserLoginControl(temporaryDirectory, sessionId)) {
            assertEquals(BrowserLoginControl.Decision.WAIT, control.decision());
            Files.writeString(signal, " SAVE \n", StandardCharsets.US_ASCII);
            assertEquals(BrowserLoginControl.Decision.SAVE, control.decision());
        }
        assertFalse(Files.exists(signal));

        Files.writeString(signal, "CANCEL", StandardCharsets.US_ASCII);
        try (FileBrowserLoginControl control = new FileBrowserLoginControl(temporaryDirectory, sessionId)) {
            assertEquals(BrowserLoginControl.Decision.CANCEL, control.decision());
        }
        assertThrows(
                IllegalArgumentException.class, () -> new FileBrowserLoginControl(temporaryDirectory, "not-a-uuid"));
    }

    @Test
    void loginControlRejectsUnknownOversizedAndNonRegularSignals() throws Exception {
        String sessionId = java.util.UUID.randomUUID().toString();
        Path signal = temporaryDirectory.resolve("login-" + sessionId + ".control");
        Files.writeString(signal, "UNKNOWN", StandardCharsets.US_ASCII);
        assertThrows(
                SecurityException.class, () -> new FileBrowserLoginControl(temporaryDirectory, sessionId).decision());

        Files.writeString(signal, "0123456789abcdefg", StandardCharsets.US_ASCII);
        assertThrows(
                SecurityException.class, () -> new FileBrowserLoginControl(temporaryDirectory, sessionId).decision());

        Files.delete(signal);
        Files.createDirectory(signal);
        assertThrows(
                SecurityException.class, () -> new FileBrowserLoginControl(temporaryDirectory, sessionId).decision());
    }

    @Test
    void oauthControlAcceptsOnlyCancelAndDeletesSignal() throws Exception {
        String sessionId = java.util.UUID.randomUUID().toString();
        Path signal = temporaryDirectory.resolve("oauth-" + sessionId + ".control");
        try (FileBrowserOAuthControl control = new FileBrowserOAuthControl(temporaryDirectory, sessionId)) {
            assertEquals(BrowserLoginControl.Decision.WAIT, control.decision());
            Files.writeString(signal, " CANCEL \n", StandardCharsets.US_ASCII);
            assertEquals(BrowserLoginControl.Decision.CANCEL, control.decision());
        }
        assertFalse(Files.exists(signal));

        Files.writeString(signal, "SAVE", StandardCharsets.US_ASCII);
        assertThrows(
                SecurityException.class, () -> new FileBrowserOAuthControl(temporaryDirectory, sessionId).decision());
        assertThrows(
                IllegalArgumentException.class, () -> new FileBrowserOAuthControl(temporaryDirectory, "not-a-uuid"));
    }

    @Test
    void interruptionTypesExposeOnlyTheirStableCodes() {
        assertEquals("LOGIN_CANCELLED", new BrowserLoginInterruptedException("LOGIN_CANCELLED").errorCode());
        assertEquals("LOGIN_EXPIRED", new BrowserLoginInterruptedException("LOGIN_EXPIRED").errorCode());
        assertThrows(IllegalArgumentException.class, () -> new BrowserLoginInterruptedException("DETAIL"));

        assertEquals("OAUTH_CANCELLED", new BrowserOAuthInterruptedException("OAUTH_CANCELLED").errorCode());
        assertEquals("OAUTH_EXPIRED", new BrowserOAuthInterruptedException("OAUTH_EXPIRED").errorCode());
        assertThrows(IllegalArgumentException.class, () -> new BrowserOAuthInterruptedException("DETAIL"));
    }

    @Test
    void workerReplyValidatesLengthAndReturnsDefensiveBytes() {
        BrowserWorkerProtocol.WorkerMessage message =
                BrowserWorkerProtocol.WorkerMessage.sensitiveSuccess(1, json.parse("{}"), 2);
        byte[] source = new byte[] {1, 2};
        try (BrowserWorkerReply reply = new BrowserWorkerReply(message, source)) {
            source[0] = 9;
            assertArrayEquals(new byte[] {1, 2}, reply.sensitiveBytes());
            byte[] copy = reply.sensitiveBytes();
            copy[0] = 8;
            assertArrayEquals(new byte[] {1, 2}, reply.sensitiveBytes());
        }
        assertThrows(IllegalArgumentException.class, () -> new BrowserWorkerReply(message, new byte[] {1}));
        assertThrows(NullPointerException.class, () -> new BrowserWorkerReply(null, new byte[0]));
        assertThrows(
                NullPointerException.class,
                () -> new BrowserWorkerReply(BrowserWorkerProtocol.WorkerMessage.success(1, json.parse("{}")), null));
    }

    private BrowserWorkerProtocol.NetworkRequest request() {
        return new BrowserWorkerProtocol.NetworkRequest(
                URI.create("https://docs.example.com/resource"), "POST", Map.of("content-type", List.of("text/plain")));
    }

    private byte[] hostResponse(BrowserWorkerProtocol.HostMessage response, byte[] body) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        BrowserFrameIo.writeJson(output, json, response);
        BrowserFrameIo.writeBinary(output, body, BrowserWorkerProtocol.MAXIMUM_NETWORK_BYTES);
        return output.toByteArray();
    }
}
