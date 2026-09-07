package com.javaclaw.server.security;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;

/** 严格读取单条有界 CONNECT 请求，拒绝歧义 framing 与非 authority 目标。 */
final class ConnectRequestReader {
    private ConnectRequestReader() {}

    static String read(InputStream input) throws IOException {
        String authority = authority(line(input));
        int bytes = 0;
        HashSet<String> names = new HashSet<>();
        for (int count = 0; count < 64; count++) {
            String header = line(input);
            bytes += header.length() + 2;
            if (bytes > 16 * 1024) {
                throw new IOException("CONNECT 请求头超过限制");
            }
            if (header.isEmpty()) {
                if (!names.contains("host")) {
                    throw new IOException("CONNECT 必须包含唯一 Host");
                }
                return authority;
            }
            validateHeader(header, authority, names);
        }
        throw new IOException("CONNECT 请求头数量超过限制");
    }

    private static String authority(String requestLine) throws IOException {
        String[] request = requestLine.split(" ", -1);
        if (request.length != 3
                || !"CONNECT".equals(request[0])
                || !"HTTP/1.1".equals(request[2])
                || !request[1].matches("[a-zA-Z0-9.-]+:[0-9]{1,5}")) {
            throw new IOException("代理仅接受标准 CONNECT authority");
        }
        String authority = request[1].toLowerCase(Locale.ROOT);
        String host = authority.substring(0, authority.lastIndexOf(':'));
        int port = Integer.parseInt(authority.substring(authority.lastIndexOf(':') + 1));
        if (!validHost(host) || port < 1 || port > 65535 || !authority.endsWith(":" + port)) {
            throw new IOException("CONNECT 目标无效");
        }
        return authority;
    }

    private static boolean validHost(String host) {
        if (host.length() > 253 || host.matches("[0-9.]+")) {
            return false;
        }
        for (String label : host.split("\\.", -1)) {
            if (!label.matches("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")) {
                return false;
            }
        }
        return true;
    }

    private static void validateHeader(String line, String authority, HashSet<String> names) throws IOException {
        int separator = line.indexOf(':');
        if (separator < 1 || !line.substring(0, separator).matches("[A-Za-z0-9-]+")) {
            throw new IOException("CONNECT 请求头无效");
        }
        String name = line.substring(0, separator).toLowerCase(Locale.ROOT);
        String value = line.substring(separator + 1).strip();
        if (!names.add(name)
                || "transfer-encoding".equals(name)
                || "upgrade".equals(name)
                || "proxy-authorization".equals(name)
                || "authorization".equals(name)
                || "content-length".equals(name) && !"0".equals(value)
                || "host".equals(name) && !matchesHost(authority, value)) {
            throw new IOException("CONNECT framing 或 Host 不明确");
        }
    }

    private static boolean matchesHost(String authority, String value) {
        // Maven 原生 HTTP transport 的 CONNECT Host 会省略 443；仍以请求行的精确 host:port 授权和建连。
        return authority.equalsIgnoreCase(value)
                || authority.endsWith(":443")
                        && authority.substring(0, authority.length() - 4).equalsIgnoreCase(value);
    }

    private static String line(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        while (bytes.size() < 8192) {
            int value = input.read();
            if (value < 0) {
                throw new EOFException("CONNECT 请求提前结束");
            }
            if (value == '\r') {
                if (input.read() != '\n') {
                    throw new IOException("CONNECT 行必须使用 CRLF");
                }
                return bytes.toString(StandardCharsets.US_ASCII);
            }
            if (value < 32 && value != '\t' || value > 126) {
                throw new IOException("CONNECT 请求包含无效控制字节");
            }
            bytes.write(value);
        }
        throw new IOException("CONNECT 行超过限制");
    }
}
