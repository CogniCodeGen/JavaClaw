package com.javaclaw.server.security;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConnectRequestReaderTest {
    @Test
    void normalizesAuthorityAndLeavesTunnelBytesUnread() throws Exception {
        var input = bytes("CONNECT Repo.Example:443 HTTP/1.1\r\nHost: REPO.EXAMPLE:443\r\n"
                + "Proxy-Connection: keep-alive\r\nContent-Length: 0\r\n\r\nTLS");
        assertEquals("repo.example:443", ConnectRequestReader.read(input));
        assertEquals("TLS", new String(input.readAllBytes(), StandardCharsets.US_ASCII));
    }

    @Test
    void acceptsMavenDefaultHttpsHostWithoutChangingTheAuthorizedTarget() throws Exception {
        assertEquals(
                "repo.example:443",
                ConnectRequestReader.read(bytes("CONNECT repo.example:443 HTTP/1.1\r\nHost: REPO.EXAMPLE\r\n\r\n")));
        for (String target : List.of("repo.example:8443", "repo.example:80")) {
            assertRejected("CONNECT " + target + " HTTP/1.1\r\nHost: repo.example\r\n\r\n");
        }
        for (String host : List.of("other.example", "repo.example:80", "repo.example:0443", "repo.example.")) {
            assertRejected("CONNECT repo.example:443 HTTP/1.1\r\nHost: " + host + "\r\n\r\n");
        }
    }

    @Test
    void rejectsNonCanonicalAuthorityAndUnsupportedRequestLines() {
        for (String target : List.of(
                "https://repo.example:443/",
                "user@repo.example:443",
                "127.0.0.1:443",
                "[::1]:443",
                "repo.example:0",
                "repo.example:65536",
                "repo.example:0443",
                "repo.example.:443",
                ".repo.example:443",
                "repo..example:443",
                "-repo.example:443",
                "repo-.example:443",
                "a".repeat(64) + ".example:443")) {
            assertRejected("CONNECT " + target + " HTTP/1.1\r\nHost: " + target + "\r\n\r\n");
        }
        for (String line : List.of(
                "GET repo.example:443 HTTP/1.1",
                "connect repo.example:443 HTTP/1.1",
                "CONNECT  repo.example:443 HTTP/1.1",
                "CONNECT repo.example:443 HTTP/1.0")) {
            assertRejected(line + "\r\nHost: repo.example:443\r\n\r\n");
        }
    }

    @Test
    void rejectsHostAmbiguityCredentialsAndRequestBodies() {
        for (String headers : List.of(
                "",
                "Host: other.example:443\r\n",
                "Host: repo.example:443\r\nhost: repo.example:443\r\n",
                "Host: repo.example:443\r\nContent-Length: 1\r\n",
                "Host: repo.example:443\r\nContent-Length: 00\r\n",
                "Host: repo.example:443\r\nTransfer-Encoding: chunked\r\n",
                "Host: repo.example:443\r\nUpgrade: TLS\r\n",
                "Host: repo.example:443\r\nProxy-Authorization: secret\r\n",
                "Host: repo.example:443\r\nAuthorization: secret\r\n",
                "Host: repo.example:443\r\n folded\r\n",
                "Host: repo.example:443\r\nX: one\r\nx: two\r\n")) {
            assertRejected("CONNECT repo.example:443 HTTP/1.1\r\n" + headers + "\r\n");
        }
    }

    @Test
    void rejectsInvalidDelimitersControlBytesAndTruncation() {
        for (String suffix : List.of(
                "\nHost: repo.example:443\n\n",
                "\rHost: repo.example:443\r\n\r\n",
                "\r\nHost: repo.example:443\r\nX: \u0000\r\n\r\n",
                "\r\nHost: repo.example:443\r",
                "\r\nHost: repo.example:443\r\nX: \u007f\r\n\r\n")) {
            assertRejected("CONNECT repo.example:443 HTTP/1.1" + suffix);
        }
        assertThrows(
                IOException.class, () -> ConnectRequestReader.read(new ByteArrayInputStream(new byte[] {(byte) 255})));
    }

    @Test
    void boundsIndividualLinesTotalHeadersAndHeaderCount() {
        String prefix = "CONNECT repo.example:443 HTTP/1.1\r\nHost: repo.example:443\r\n";
        assertRejected(prefix + "X: " + "x".repeat(8192) + "\r\n\r\n");
        assertRejected(prefix + "X: " + "x".repeat(8100) + "\r\nY: " + "y".repeat(8100) + "\r\nZ: " + "z".repeat(200)
                + "\r\n\r\n");
        StringBuilder many = new StringBuilder(prefix);
        for (int index = 0; index < 64; index++) {
            many.append("X-").append(index).append(": x\r\n");
        }
        assertRejected(many.append("\r\n").toString());
    }

    private static void assertRejected(String request) {
        assertThrows(IOException.class, () -> ConnectRequestReader.read(bytes(request)), request);
    }

    private static ByteArrayInputStream bytes(String request) {
        return new ByteArrayInputStream(request.getBytes(StandardCharsets.US_ASCII));
    }
}
