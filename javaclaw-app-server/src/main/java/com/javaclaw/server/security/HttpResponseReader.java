package com.javaclaw.server.security;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** 只解析有界 HTTP/1.0 与 HTTP/1.1 响应，拒绝歧义 framing 和 header folding。 */
final class HttpResponseReader {
    private static final int MAXIMUM_LINE_BYTES = 8 * 1024;
    private static final int MAXIMUM_HEADER_BYTES = 64 * 1024;
    private static final int MAXIMUM_HEADERS = 128;
    private static final Pattern HEADER_NAME = Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]{1,80}");

    private final InputStream input;
    private int headerBytes;

    HttpResponseReader(InputStream input) {
        this.input = java.util.Objects.requireNonNull(input, "input");
    }

    HttpWireExchange.WireResponse read(String requestMethod, int maximumBodyBytes) throws IOException {
        ResponseHead head;
        int interim = 0;
        do {
            head = readHead();
            interim++;
        } while (head.statusCode() >= 100 && head.statusCode() < 200 && head.statusCode() != 101 && interim <= 4);
        if (head.statusCode() == 101 || interim > 4) {
            throw new IOException("HTTP protocol switching or excessive interim responses are forbidden");
        }
        Body body = readBody(requestMethod, head, maximumBodyBytes);
        return new HttpWireExchange.WireResponse(head.statusCode(), head.headers(), body.bytes(), body.truncated());
    }

    private ResponseHead readHead() throws IOException {
        headerBytes = 0;
        String statusLine = line();
        if (!statusLine.matches("HTTP/1\\.[01] [0-9]{3}( .*)?")) {
            throw new IOException("invalid HTTP status line");
        }
        int status = Integer.parseInt(statusLine.substring(9, 12));
        LinkedHashMap<String, List<String>> headers = new LinkedHashMap<>();
        while (true) {
            String line = line();
            if (line.isEmpty()) {
                return new ResponseHead(status, immutable(headers));
            }
            if (line.charAt(0) == ' ' || line.charAt(0) == '\t') {
                throw new IOException("folded HTTP headers are forbidden");
            }
            int separator = line.indexOf(':');
            if (separator < 1) {
                throw new IOException("invalid HTTP response header");
            }
            String name = line.substring(0, separator).toLowerCase(Locale.ROOT);
            if (!HEADER_NAME.matcher(name).matches() || headers.size() >= MAXIMUM_HEADERS) {
                throw new IOException("HTTP response header count or name is invalid");
            }
            String value = line.substring(separator + 1).strip();
            headers.computeIfAbsent(name, ignored -> new ArrayList<>()).add(value);
        }
    }

    private Body readBody(String method, ResponseHead head, int maximumBytes) throws IOException {
        if ("HEAD".equals(method) || head.statusCode() == 204 || head.statusCode() == 304) {
            return Body.empty();
        }
        List<String> transferEncoding = commaValues(head.headers().getOrDefault("transfer-encoding", List.of()));
        List<String> contentLength = commaValues(head.headers().getOrDefault("content-length", List.of()));
        if (!transferEncoding.isEmpty() && !contentLength.isEmpty()) {
            throw new IOException("HTTP response contains ambiguous body framing");
        }
        if (!transferEncoding.isEmpty()) {
            if (transferEncoding.size() != 1 || !"chunked".equalsIgnoreCase(transferEncoding.getFirst())) {
                throw new IOException("unsupported HTTP transfer encoding");
            }
            return chunked(maximumBytes);
        }
        if (!contentLength.isEmpty()) {
            return fixed(contentLength(contentLength), maximumBytes);
        }
        return untilEof(maximumBytes);
    }

    private Body chunked(int maximumBytes) throws IOException {
        BodyAccumulator body = new BodyAccumulator(maximumBytes);
        while (true) {
            String sizeLine = line();
            String hexadecimal = sizeLine.split(";", 2)[0].strip();
            if (hexadecimal.isEmpty() || hexadecimal.length() > 16 || !hexadecimal.matches("[0-9A-Fa-f]+")) {
                throw new IOException("invalid HTTP chunk size");
            }
            long size;
            try {
                size = Long.parseUnsignedLong(hexadecimal, 16);
            } catch (NumberFormatException failure) {
                throw new IOException("HTTP chunk size exceeds supported range", failure);
            }
            if (size < 0) {
                throw new IOException("HTTP chunk size exceeds supported range");
            }
            if (size == 0) {
                readTrailers();
                return body.finish(false);
            }
            if (!body.read(input, size)) {
                return body.finish(true);
            }
            if (!line().isEmpty()) {
                throw new IOException("HTTP chunk is not followed by CRLF");
            }
        }
    }

    private Body fixed(long length, int maximumBytes) throws IOException {
        BodyAccumulator body = new BodyAccumulator(maximumBytes);
        boolean complete = body.read(input, length);
        return body.finish(!complete || length > maximumBytes);
    }

    private Body untilEof(int maximumBytes) throws IOException {
        BodyAccumulator body = new BodyAccumulator(maximumBytes);
        byte[] buffer = new byte[8 * 1024];
        while (true) {
            int count = input.read(buffer);
            if (count < 0) {
                return body.finish(false);
            }
            if (!body.append(buffer, count)) {
                return body.finish(true);
            }
        }
    }

    private void readTrailers() throws IOException {
        while (!line().isEmpty()) {
            // Trailer 内容不暴露给调用方，但仍受统一 header byte 上限约束。
        }
    }

    private String line() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(128);
        boolean carriageReturn = false;
        while (bytes.size() <= MAXIMUM_LINE_BYTES) {
            int value = input.read();
            if (value < 0) {
                throw new EOFException("HTTP response ended inside a header line");
            }
            headerBytes++;
            if (headerBytes > MAXIMUM_HEADER_BYTES) {
                throw new IOException("HTTP response headers exceed the byte limit");
            }
            if (carriageReturn) {
                if (value != '\n') {
                    throw new IOException("HTTP response line must use CRLF");
                }
                return bytes.toString(StandardCharsets.ISO_8859_1);
            }
            if (value == '\r') {
                carriageReturn = true;
            } else {
                bytes.write(value);
            }
        }
        throw new IOException("HTTP response line exceeds the byte limit");
    }

    private static long contentLength(List<String> values) throws IOException {
        long expected = -1;
        for (String value : values) {
            long parsed;
            try {
                parsed = Long.parseLong(value);
            } catch (NumberFormatException failure) {
                throw new IOException("invalid HTTP Content-Length", failure);
            }
            if (parsed < 0 || expected >= 0 && expected != parsed) {
                throw new IOException("conflicting HTTP Content-Length values");
            }
            expected = parsed;
        }
        return expected;
    }

    private static List<String> commaValues(List<String> source) {
        return source.stream()
                .flatMap(value -> java.util.Arrays.stream(value.split(",")))
                .map(String::strip)
                .filter(value -> !value.isEmpty())
                .toList();
    }

    private static Map<String, List<String>> immutable(Map<String, List<String>> source) {
        return source.entrySet().stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        Map.Entry::getKey, entry -> List.copyOf(entry.getValue())));
    }

    private record ResponseHead(int statusCode, Map<String, List<String>> headers) {}

    private record Body(byte[] bytes, boolean truncated) {
        static Body empty() {
            return new Body(new byte[0], false);
        }
    }

    private static final class BodyAccumulator {
        private final ByteArrayOutputStream output;
        private int remaining;

        BodyAccumulator(int maximumBytes) {
            if (maximumBytes < 0) {
                throw new IllegalArgumentException("maximumBytes must not be negative");
            }
            remaining = maximumBytes;
            output = new ByteArrayOutputStream(Math.min(maximumBytes, 8 * 1024));
        }

        boolean read(InputStream input, long bytes) throws IOException {
            byte[] buffer = new byte[8 * 1024];
            long remainingSource = bytes;
            while (remainingSource > 0 && remaining > 0) {
                int requested = (int) Math.min(Math.min(remainingSource, remaining), buffer.length);
                int count = input.read(buffer, 0, requested);
                if (count < 0) {
                    throw new EOFException("HTTP response body ended early");
                }
                output.write(buffer, 0, count);
                remaining -= count;
                remainingSource -= count;
            }
            return remainingSource == 0;
        }

        boolean append(byte[] source, int count) {
            int allowed = Math.min(remaining, count);
            output.write(source, 0, allowed);
            remaining -= allowed;
            return allowed == count;
        }

        Body finish(boolean truncated) {
            return new Body(output.toByteArray(), truncated);
        }
    }
}
