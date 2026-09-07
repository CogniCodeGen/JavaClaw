package com.javaclaw.server.security;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 制品下载专用 HTTP/1.x 流式解码器；严格 framing，不缓冲整个制品或改变普通 Broker 行为。 */
final class ArtifactHttpReader {
    private final InputStream input;
    private final byte[] buffer = new byte[32 * 1024];

    ArtifactHttpReader(InputStream input) {
        this.input = input;
    }

    Head head() throws IOException {
        int consumed = 0;
        for (int interim = 0; interim < 5; interim++) {
            String statusLine = line();
            consumed += statusLine.length() + 2;
            if (!statusLine.matches("HTTP/1\\.[01] [0-9]{3}( .*)?")) {
                throw new IOException("invalid artifact HTTP status line");
            }
            int status = Integer.parseInt(statusLine.substring(9, 12));
            Map<String, List<String>> headers = headers(consumed);
            if (status == 101) {
                throw new IOException("artifact HTTP protocol switching is forbidden");
            }
            if (status < 100 || status >= 200) {
                return new Head(status, headers);
            }
        }
        throw new IOException("too many artifact HTTP interim responses");
    }

    void body(Head head, OutputStream target, long maximumBytes) throws IOException {
        List<String> encoding = head.headers().getOrDefault("content-encoding", List.of());
        if (!encoding.isEmpty() && (encoding.size() != 1 || !encoding.getFirst().equalsIgnoreCase("identity"))) {
            throw new IOException("artifact content encoding must be identity");
        }
        List<String> transfers = head.headers().getOrDefault("transfer-encoding", List.of());
        List<String> lengths = head.headers().getOrDefault("content-length", List.of());
        if (!transfers.isEmpty() && !lengths.isEmpty()) {
            throw new IOException("ambiguous artifact HTTP body framing");
        }
        if (!transfers.isEmpty()) {
            if (transfers.size() != 1 || !transfers.getFirst().equalsIgnoreCase("chunked")) {
                throw new IOException("unsupported artifact HTTP transfer encoding");
            }
            chunked(target, maximumBytes);
        } else if (!lengths.isEmpty()) {
            long length = contentLength(lengths);
            requireBudget(length, maximumBytes);
            fixed(target, length);
        } else {
            untilEof(target, maximumBytes);
        }
    }

    private void chunked(OutputStream target, long maximumBytes) throws IOException {
        long total = 0;
        for (int chunks = 0; chunks < 1_000_000; chunks++) {
            String sizeLine = line();
            String hexadecimal = sizeLine.split(";", 2)[0];
            if (!hexadecimal.matches("[0-9A-Fa-f]{1,15}")) {
                throw new IOException("invalid artifact HTTP chunk size");
            }
            long size = Long.parseLong(hexadecimal, 16);
            if (size == 0) {
                Map<String, List<String>> trailers = headers(0);
                if (trailers.keySet().stream()
                        .anyMatch(Set.of("content-length", "transfer-encoding", "content-encoding")::contains)) {
                    throw new IOException("artifact trailer cannot alter body framing");
                }
                return;
            }
            requireBudget(size, maximumBytes - total);
            fixed(target, size);
            total += size;
            if (!line().isEmpty()) {
                throw new IOException("artifact HTTP chunk requires CRLF");
            }
        }
        throw new IOException("artifact HTTP chunk count exceeds limit");
    }

    private void fixed(OutputStream target, long length) throws IOException {
        long remaining = length;
        while (remaining > 0) {
            int count = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (count < 0) {
                throw new EOFException("artifact HTTP body ended early");
            }
            if (count > 0) {
                target.write(buffer, 0, count);
                remaining -= count;
            }
        }
    }

    private void untilEof(OutputStream target, long maximumBytes) throws IOException {
        long consumed = 0;
        int count;
        while ((count = input.read(buffer)) >= 0) {
            requireBudget(count, maximumBytes - consumed);
            target.write(buffer, 0, count);
            consumed += count;
        }
    }

    private Map<String, List<String>> headers(int consumed) throws IOException {
        LinkedHashMap<String, List<String>> headers = new LinkedHashMap<>();
        for (int count = 0; count <= 128; count++) {
            String line = line();
            consumed += line.length() + 2;
            if (consumed > 64 * 1024) {
                throw new IOException("artifact HTTP headers exceed byte limit");
            }
            if (line.isEmpty()) {
                return Map.copyOf(headers);
            }
            int separator = line.indexOf(':');
            if (count == 128
                    || separator < 1
                    || !line.substring(0, separator).matches("[!#$%&'*+.^_`|~0-9A-Za-z-]{1,80}")) {
                throw new IOException("invalid artifact HTTP response header");
            }
            String name = line.substring(0, separator).toLowerCase(Locale.ROOT);
            String value = line.substring(separator + 1).strip();
            if (value.chars().anyMatch(character -> character < 32 && character != 9 || character == 127)) {
                throw new IOException("artifact HTTP header contains control characters");
            }
            headers.computeIfAbsent(name, ignored -> new ArrayList<>()).add(value);
        }
        throw new IOException("artifact HTTP header count exceeds limit");
    }

    private String line() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(128);
        while (bytes.size() <= 8192) {
            int next = input.read();
            if (next < 0) {
                throw new EOFException("artifact HTTP response ended inside a line");
            }
            if (next == '\r') {
                if (input.read() != '\n') {
                    throw new IOException("artifact HTTP line requires CRLF");
                }
                return bytes.toString(StandardCharsets.ISO_8859_1);
            }
            if (next == '\n') {
                throw new IOException("artifact HTTP bare LF is forbidden");
            }
            bytes.write(next);
        }
        throw new IOException("artifact HTTP line exceeds limit");
    }

    private static long contentLength(List<String> lengths) throws IOException {
        if (lengths.size() != 1 || !lengths.getFirst().matches("[0-9]{1,18}")) {
            throw new IOException("invalid or duplicate artifact Content-Length");
        }
        return Long.parseLong(lengths.getFirst());
    }

    private static void requireBudget(long requested, long available) throws IOException {
        if (requested < 0 || requested > available) {
            throw new IOException("artifact download exceeds byte limit");
        }
    }

    record Head(int status, Map<String, List<String>> headers) {}
}
