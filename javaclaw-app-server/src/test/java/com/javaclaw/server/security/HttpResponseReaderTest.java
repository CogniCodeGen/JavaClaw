package com.javaclaw.server.security;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpResponseReaderTest {
    @Test
    void readsInterimFixedChunkedAndEofDelimitedResponses() throws Exception {
        var interim = read(
                "HTTP/1.1 100 Continue\r\n\r\nHTTP/1.1 200 OK\r\nContent-Length: 2, 2\r\nX-A: one\r\nX-A: two\r\n\r\nok",
                "GET",
                10);
        var chunked = read(
                "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n2;name=value\r\nok\r\n0\r\nX-Trailer: ignored\r\n\r\n",
                "GET",
                10);
        var eof = read("HTTP/1.0 200 OK\r\n\r\nbody", "GET", 10);
        var truncatedEof = read("HTTP/1.1 200 OK\r\n\r\nlong-body", "GET", 4);

        assertArrayEquals(bytes("ok"), interim.body());
        assertEquals(2, interim.headers().get("x-a").size());
        assertArrayEquals(bytes("ok"), chunked.body());
        assertFalse(chunked.truncated());
        assertArrayEquals(bytes("body"), eof.body());
        assertArrayEquals(bytes("long"), truncatedEof.body());
        assertTrue(truncatedEof.truncated());
    }

    @Test
    void bodylessStatusesIgnoreAdvertisedBodies() throws Exception {
        assertArrayEquals(
                new byte[0],
                read("HTTP/1.1 200 OK\r\nContent-Length: 9\r\n\r\n", "HEAD", 1).body());
        assertArrayEquals(
                new byte[0], read("HTTP/1.1 204 No Content\r\n\r\n", "GET", 1).body());
        assertArrayEquals(
                new byte[0], read("HTTP/1.1 304 Not Modified\r\n\r\n", "GET", 1).body());
    }

    @Test
    void rejectsInvalidStatusAndHeaderSyntax() {
        assertInvalid("ICY 200 OK\r\n\r\n", "GET", 1);
        assertInvalid("HTTP/1.1 200 OK\r\n folded\r\n\r\n", "GET", 1);
        assertInvalid("HTTP/1.1 200 OK\r\nmissing-colon\r\n\r\n", "GET", 1);
        assertInvalid("HTTP/1.1 200 OK\r\nbad name: value\r\n\r\n", "GET", 1);
        assertInvalid("HTTP/1.1 200 OK\rX", "GET", 1);
        assertInvalid("HTTP/1.1 200 OK", "GET", 1);
        assertInvalid("HTTP/1.1 101 Switching Protocols\r\n\r\n", "GET", 1);
        String interim = "HTTP/1.1 100 Continue\r\n\r\n".repeat(5);
        assertInvalid(interim + "HTTP/1.1 200 OK\r\n\r\n", "GET", 1);
    }

    @Test
    void rejectsAmbiguousAndUnsupportedBodyFraming() {
        assertInvalid("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nContent-Length: 1\r\n\r\n0\r\n\r\n", "GET", 1);
        assertInvalid("HTTP/1.1 200 OK\r\nTransfer-Encoding: gzip\r\n\r\n", "GET", 1);
        assertInvalid("HTTP/1.1 200 OK\r\nTransfer-Encoding: gzip, chunked\r\n\r\n", "GET", 1);
        assertInvalid("HTTP/1.1 200 OK\r\nContent-Length: x\r\n\r\n", "GET", 1);
        assertInvalid("HTTP/1.1 200 OK\r\nContent-Length: -1\r\n\r\n", "GET", 1);
        assertInvalid("HTTP/1.1 200 OK\r\nContent-Length: 1, 2\r\n\r\nxx", "GET", 2);
        assertInvalid("HTTP/1.1 200 OK\r\nContent-Length: 4\r\n\r\nhi", "GET", 4);
        assertThrows(IllegalArgumentException.class, () -> read("HTTP/1.1 200 OK\r\n\r\n", "GET", -1));
    }

    @Test
    void rejectsMalformedChunkBoundariesAndSizes() {
        assertInvalid("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n\r\n", "GET", 2);
        assertInvalid("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\nxyz\r\n", "GET", 2);
        assertInvalid("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n10000000000000000\r\n", "GET", 2);
        assertInvalid("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\nffffffffffffffff\r\n", "GET", 2);
        assertInvalid("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n1\r\naX\r\n", "GET", 2);
        assertInvalid("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n5\r\nhi", "GET", 10);
        try {
            var truncated = read("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n5\r\nhello", "GET", 2);
            assertArrayEquals(bytes("he"), truncated.body());
            assertTrue(truncated.truncated());
        } catch (IOException failure) {
            throw new AssertionError(failure);
        }
    }

    @Test
    void enforcesHeaderCountLineAndAggregateByteBounds() {
        StringBuilder tooMany = new StringBuilder("HTTP/1.1 200 OK\r\n");
        for (int index = 0; index < 129; index++) {
            tooMany.append("X-").append(index).append(": value\r\n");
        }
        assertInvalid(tooMany.append("\r\n").toString(), "GET", 1);
        assertInvalid("HTTP/1.1 200 OK\r\nX: " + "a".repeat(8_193) + "\r\n\r\n", "GET", 1);
        StringBuilder tooLarge = new StringBuilder("HTTP/1.1 200 OK\r\n");
        for (int index = 0; index < 9; index++) {
            tooLarge.append("X-")
                    .append(index)
                    .append(": ")
                    .append("a".repeat(8_100))
                    .append("\r\n");
        }
        assertInvalid(tooLarge.append("\r\n").toString(), "GET", 1);
    }

    private static HttpWireExchange.WireResponse read(String response, String method, int maximumBytes)
            throws IOException {
        return new HttpResponseReader(new ByteArrayInputStream(bytes(response))).read(method, maximumBytes);
    }

    private static void assertInvalid(String response, String method, int maximumBytes) {
        assertThrows(IOException.class, () -> read(response, method, maximumBytes));
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.ISO_8859_1);
    }
}
