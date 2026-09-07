package com.javaclaw.server.security;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ArtifactHttpReaderTest {
    @Test
    void streamsLengthChunkedAndEofBodies() throws Exception {
        assertEquals("hello", read("Content-Length: 5\r\n", "hello", 5));
        assertEquals(
                "hello",
                read("Transfer-Encoding: chunked\r\n", "2\r\nhe\r\n3;label=x\r\nllo\r\n0\r\nX-Trace: ok\r\n\r\n", 5));
        assertEquals("hello", read("", "hello", 5));
    }

    @Test
    void rejectsAmbiguityTruncationAndAllFramingBudgetOverflows() {
        for (String headers : List.of(
                "Content-Length: 5\r\nContent-Length: 5\r\n",
                "Content-Length: 5\r\nTransfer-Encoding: chunked\r\n",
                "Content-Length: +5\r\n",
                "Transfer-Encoding: gzip, chunked\r\n",
                "Content-Encoding: gzip\r\n")) {
            assertThrows(IOException.class, () -> read(headers, "hello", 10));
        }
        assertThrows(IOException.class, () -> read("Content-Length: 6\r\n", "hello", 10));
        assertThrows(IOException.class, () -> read("Content-Length: 5\r\n", "hello", 4));
        assertThrows(IOException.class, () -> read("", "hello", 4));
        assertThrows(IOException.class, () -> read("Transfer-Encoding: chunked\r\n", "5\r\nhello\r\n0\r\n\r\n", 4));
        assertThrows(
                IOException.class, () -> read("Transfer-Encoding: chunked\r\n", "0\r\nContent-Length: 0\r\n\r\n", 4));
    }

    @Test
    void rejectsHeaderFoldingBareLfAndSwitching() {
        for (String response : List.of(
                "HTTP/1.1 200 OK\r\n folded: x\r\n\r\n", "HTTP/1.1 200 OK\n\n", "HTTP/1.1 101 Switch\r\n\r\n")) {
            assertThrows(IOException.class, () -> reader(response).head());
        }
    }

    private static String read(String headers, String body, long maximum) throws IOException {
        var reader = reader("HTTP/1.1 200 OK\r\n" + headers + "\r\n" + body);
        var target = new ByteArrayOutputStream();
        reader.body(reader.head(), target, maximum);
        return target.toString(StandardCharsets.UTF_8);
    }

    private static ArtifactHttpReader reader(String response) {
        return new ArtifactHttpReader(new ByteArrayInputStream(response.getBytes(StandardCharsets.ISO_8859_1)));
    }
}
