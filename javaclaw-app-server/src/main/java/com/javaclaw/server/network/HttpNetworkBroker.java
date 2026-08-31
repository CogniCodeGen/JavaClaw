package com.javaclaw.server.network;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.IDN;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

import com.javaclaw.sandbox.api.BrokerExchange;
import com.javaclaw.sandbox.api.BrokerRequest;
import com.javaclaw.sandbox.api.BrokerResponse;
import com.javaclaw.sandbox.api.BrokerResponseHead;
import com.javaclaw.sandbox.api.NetworkBroker;
import com.javaclaw.sandbox.api.NetworkPolicy;

/** DNS-pinned, proxy-free HTTP/1.1 broker with per-hop allowlist and SSRF checks. */
public final class HttpNetworkBroker implements NetworkBroker {
    private static final int MAX_HEADER_BYTES = 64 * 1024;
    private final PrivateAddressAuthorizer privateAddresses;

    /** 默认只访问公开地址；没有显式工作区授权时拒绝所有私网和本机端点。 */
    public HttpNetworkBroker() {
        this((uri, address) -> false);
    }

    /** 装配服务端生成的精确端点授权核验器；在 DNS 后和连接前核验，不向模型暴露此入口。 */
    public HttpNetworkBroker(PrivateAddressAuthorizer privateAddresses) {
        this.privateAddresses = Objects.requireNonNull(privateAddresses);
    }

    /** 受信任服务端对每个已解析私网 IP 做用途、版本、有效期和工作区核验。 */
    @FunctionalInterface
    public interface PrivateAddressAuthorizer {
        /** 当前连接是否获准；返回 true 不能解除 Broker 对云元数据及控制地址的禁止。 */
        boolean permits(URI uri, InetAddress address);
    }

    @Override
    public BrokerResponse execute(BrokerRequest request, NetworkPolicy grantedPolicy) throws IOException {
        try (BrokerExchange exchange = openExchange(request, grantedPolicy)) {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = exchange.read(buffer, 0, buffer.length)) >= 0) {
                body.write(buffer, 0, count);
            }
            BrokerResponseHead head = exchange.head();
            return new BrokerResponse(
                    head.statusCode(), head.finalUri(), head.headers(), body.toByteArray(), head.redirectCount());
        }
    }

    @Override
    public BrokerExchange openExchange(BrokerRequest request, NetworkPolicy grantedPolicy) throws IOException {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(grantedPolicy, "grantedPolicy");
        if (grantedPolicy.mode() != NetworkPolicy.Mode.ALLOWLIST) {
            throw new IOException("network broker requires an explicit ALLOWLIST policy");
        }
        URI current = request.uri();
        String method = request.method();
        byte[] body = request.body();
        for (int redirect = 0; ; redirect++) {
            // 每一跳都重新检查 host/port 和完整 DNS 结果，重定向不能继承上一跳的网络授权。
            ParsedTarget target = validateTarget(current, grantedPolicy, request.tlsRequired());
            SocketBrokerExchange response = openSingle(request, target, method, body, redirect);
            if (!isRedirect(response.head().statusCode()) || !request.followRedirects()) {
                return response;
            }
            if (redirect >= request.maximumRedirects()) {
                response.close();
                throw new IOException("network broker redirect limit exceeded");
            }
            String location = firstHeader(response.head().headers(), "location");
            if (location == null || location.isBlank()) {
                return response;
            }
            response.close();
            URI redirected = current.resolve(location).normalize();
            if (!sameOrigin(current, redirected) && hasSensitiveHeaders(request.headers())) {
                throw new IOException("network broker refuses a cross-origin credential redirect");
            }
            current = redirected;
            if (response.head().statusCode() == 303
                    || ((response.head().statusCode() == 301 || response.head().statusCode() == 302)
                            && "POST".equals(method))) {
                method = "GET";
                body = new byte[0];
            }
        }
    }

    private SocketBrokerExchange openSingle(
            BrokerRequest request, ParsedTarget target, String method, byte[] body, int redirects) throws IOException {
        int timeout = Math.toIntExact(
                Math.min(Integer.MAX_VALUE, Math.max(1, request.timeout().toMillis())));
        Socket raw = new Socket();
        Socket connected = raw;
        try {
            // 直接连接已验证的 IP，避免 HTTP 库再次解析而发生 DNS rebinding；TLS 仍校验原始主机名。
            raw.connect(new InetSocketAddress(target.address(), target.port()), timeout);
            raw.setSoTimeout(timeout);
            if (target.tls()) {
                SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
                SSLSocket tls = (SSLSocket) factory.createSocket(raw, target.host(), target.port(), true);
                SSLParameters parameters = tls.getSSLParameters();
                parameters.setEndpointIdentificationAlgorithm("HTTPS");
                parameters.setServerNames(List.of(new SNIHostName(target.host())));
                tls.setSSLParameters(parameters);
                tls.startHandshake();
                connected = tls;
            }
            BufferedOutputStream output = new BufferedOutputStream(connected.getOutputStream());
            BufferedInputStream input = new BufferedInputStream(connected.getInputStream());
            writeRequest(output, request, target, method, body);
            ParsedResponse response = readResponseHead(input, target.uri(), method, redirects);
            if (response.declaredLength() > request.maximumResponseBytes()) {
                throw new IOException("network response exceeds limit");
            }
            return new SocketBrokerExchange(connected, input, response, request.maximumResponseBytes());
        } catch (Throwable failure) {
            try {
                connected.close();
            } catch (IOException ignored) {
            }
            if (connected != raw) {
                try {
                    raw.close();
                } catch (IOException ignored) {
                }
            }
            if (failure instanceof IOException io) {
                throw io;
            }
            if (failure instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IOException("network exchange failed", failure);
        }
    }

    private static void writeRequest(
            BufferedOutputStream output, BrokerRequest request, ParsedTarget target, String method, byte[] body)
            throws IOException {
        String path = target.uri().getRawPath();
        if (path == null || path.isEmpty()) {
            path = "/";
        }
        if (target.uri().getRawQuery() != null) {
            path += "?" + target.uri().getRawQuery();
        }
        String hostHeader = target.host() + (target.defaultPort() ? "" : ":" + target.port());
        StringBuilder headers = new StringBuilder();
        headers.append(method)
                .append(' ')
                .append(path)
                .append(" HTTP/1.1\r\n")
                .append("Host: ")
                .append(hostHeader)
                .append("\r\n")
                .append("Connection: close\r\n")
                .append("Accept-Encoding: identity\r\n")
                .append("Content-Length: ")
                .append(body.length)
                .append("\r\n");
        request.headers()
                .forEach((name, value) ->
                        headers.append(name).append(": ").append(value).append("\r\n"));
        headers.append("\r\n");
        output.write(headers.toString().getBytes(StandardCharsets.ISO_8859_1));
        output.write(body);
        output.flush();
    }

    private static ParsedResponse readResponseHead(InputStream input, URI uri, String requestMethod, int redirects)
            throws IOException {
        String status = readLine(input, MAX_HEADER_BYTES);
        if (status == null || !status.matches("HTTP/1\\.[01] [0-9]{3}.*")) {
            throw new IOException("network broker received an invalid HTTP status line");
        }
        int statusCode = Integer.parseInt(status.substring(9, 12));
        LinkedHashMap<String, List<String>> headers = new LinkedHashMap<>();
        int headerBytes = status.length() + 2;
        String line;
        while ((line = readLine(input, MAX_HEADER_BYTES - headerBytes)) != null && !line.isEmpty()) {
            headerBytes += line.length() + 2;
            int separator = line.indexOf(':');
            if (separator <= 0) {
                throw new IOException("invalid HTTP response header");
            }
            String name = line.substring(0, separator).strip().toLowerCase(Locale.ROOT);
            String value = line.substring(separator + 1).strip();
            headers.computeIfAbsent(name, ignored -> new ArrayList<>()).add(value);
        }
        boolean chunked = transferEncoding(headers);
        long declared = parseContentLength(headers);
        LinkedHashMap<String, List<String>> immutable = new LinkedHashMap<>();
        headers.forEach((name, values) -> immutable.put(name, List.copyOf(values)));
        if (chunked && declared >= 0) {
            throw new IOException("ambiguous response framing");
        }
        if ("HEAD".equalsIgnoreCase(requestMethod)
                || statusCode == 204
                || statusCode == 304
                || (statusCode >= 100 && statusCode < 200)) {
            declared = 0;
        }
        return new ParsedResponse(
                new BrokerResponseHead(statusCode, uri, Map.copyOf(immutable), redirects), chunked, declared);
    }

    private static String readLine(InputStream input, int maximum) throws IOException {
        if (maximum < 1) {
            throw new IOException("HTTP headers exceed limit");
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int previous = -1;
        int value;
        while ((value = input.read()) >= 0) {
            if (previous == '\r' && value == '\n') {
                byte[] bytes = output.toByteArray();
                return new String(bytes, 0, Math.max(0, bytes.length - 1), StandardCharsets.ISO_8859_1);
            }
            output.write(value);
            if (output.size() > maximum) {
                throw new IOException("HTTP headers exceed limit");
            }
            previous = value;
        }
        return output.size() == 0 ? null : throwEof();
    }

    private static String throwEof() throws EOFException {
        throw new EOFException("truncated HTTP header");
    }

    private ParsedTarget validateTarget(URI uri, NetworkPolicy policy, boolean tlsRequired) throws IOException {
        if (uri == null || uri.isOpaque() || uri.getFragment() != null || uri.getUserInfo() != null) {
            throw new IOException("network broker URI is invalid");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new IOException("network broker only supports HTTP(S)");
        }
        if (tlsRequired && !scheme.equals("https")) {
            throw new IOException("network broker blocked a TLS downgrade");
        }
        if (uri.getHost() == null) {
            throw new IOException("network broker URI has no host");
        }
        String host;
        try {
            host = IDN.toASCII(uri.getHost(), IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException failure) {
            throw new IOException("invalid network host", failure);
        }
        int port = uri.getPort() >= 0 ? uri.getPort() : scheme.equals("https") ? 443 : 80;
        if (port < 1 || port > 65535 || !allowed(host, port, policy.allowedHosts())) {
            throw new IOException("network target is outside the granted allowlist");
        }
        InetAddress[] addresses = InetAddress.getAllByName(host);
        if (addresses.length == 0) {
            throw new IOException("network host did not resolve");
        }
        for (InetAddress address : addresses) {
            if (address.getHostAddress().equals("168.63.129.16")
                    || host.equals("metadata.google.internal")
                    || host.equals("instance-data.ec2.internal")
                    || (blocked(address)
                            && (!NetworkGrantService.grantable(address) || !privateAddresses.permits(uri, address)))) {
                throw new IOException("network target resolved to a private or local address");
            }
        }
        return new ParsedTarget(uri, host, port, scheme.equals("https"), addresses[0]);
    }

    static boolean hasSensitiveHeaders(Map<String, String> headers) {
        return headers.keySet().stream()
                .map(value -> value.toLowerCase(Locale.ROOT))
                .anyMatch(value ->
                        value.equals("authorization") || value.equals("proxy-authorization") || value.equals("cookie"));
    }

    static boolean sameOrigin(URI left, URI right) {
        if (left == null || right == null) {
            return false;
        }
        String leftScheme = left.getScheme() == null ? "" : left.getScheme().toLowerCase(Locale.ROOT);
        String rightScheme = right.getScheme() == null ? "" : right.getScheme().toLowerCase(Locale.ROOT);
        String leftHost = left.getHost() == null ? "" : left.getHost().toLowerCase(Locale.ROOT);
        String rightHost = right.getHost() == null ? "" : right.getHost().toLowerCase(Locale.ROOT);
        return leftScheme.equals(rightScheme)
                && leftHost.equals(rightHost)
                && effectivePort(left) == effectivePort(right);
    }

    private static int effectivePort(URI value) {
        if (value.getPort() >= 0) {
            return value.getPort();
        }
        return "https".equalsIgnoreCase(value.getScheme()) ? 443 : 80;
    }

    static boolean blocked(InetAddress address) {
        if (address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }
        byte[] value = address.getAddress();
        if (address instanceof Inet4Address) {
            int first = value[0] & 0xff;
            int second = value[1] & 0xff;
            return first == 0 || (first == 100 && second >= 64 && second <= 127);
        }
        return address instanceof Inet6Address && (value[0] & 0xfe) == 0xfc;
    }

    private static boolean allowed(String host, int port, java.util.Set<String> allowlist) {
        for (String entry : allowlist) {
            String value = entry.toLowerCase(Locale.ROOT);
            int separator = value.lastIndexOf(':');
            boolean hasPort = separator > 0 && value.indexOf(':') == separator;
            String allowedHost = hasPort ? value.substring(0, separator) : value;
            Integer allowedPort = hasPort ? parsePort(value.substring(separator + 1)) : null;
            if (hasPort && allowedPort == null) {
                continue;
            }
            if (allowedPort != null && allowedPort != port) {
                continue;
            }
            if (allowedHost.startsWith("*.")) {
                String suffix = allowedHost.substring(1);
                if (host.endsWith(suffix) && host.length() > suffix.length()) {
                    return true;
                }
            } else if (host.equals(allowedHost)) {
                return true;
            }
        }
        return false;
    }

    private static Integer parsePort(String value) {
        try {
            int port = Integer.parseInt(value);
            return port >= 1 && port <= 65535 ? port : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    static long parseContentLength(Map<String, List<String>> headers) throws IOException {
        List<String> values = headers.get("content-length");
        if (values == null || values.isEmpty()) {
            return -1;
        }
        if (values.size() != 1 || values.getFirst().indexOf(',') >= 0) {
            throw new IOException("ambiguous Content-Length");
        }
        String value = values.getFirst();
        try {
            long parsed = Long.parseLong(value);
            if (parsed < 0) {
                throw new NumberFormatException();
            }
            return parsed;
        } catch (NumberFormatException failure) {
            throw new IOException("invalid Content-Length", failure);
        }
    }

    static boolean transferEncoding(Map<String, List<String>> headers) throws IOException {
        List<String> values = headers.get("transfer-encoding");
        if (values == null || values.isEmpty()) {
            return false;
        }
        if (values.size() != 1) {
            throw new IOException("ambiguous Transfer-Encoding");
        }
        List<String> codings = java.util.Arrays.stream(values.getFirst().split(","))
                .map(String::strip)
                .filter(value -> !value.isEmpty())
                .toList();
        if (!codings.equals(List.of("chunked"))) {
            throw new IOException("unsupported Transfer-Encoding");
        }
        return true;
    }

    private static String firstHeader(Map<String, List<String>> headers, String name) {
        List<String> values = headers.get(name.toLowerCase(Locale.ROOT));
        return values == null || values.isEmpty() ? null : values.getFirst();
    }

    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    private record ParsedTarget(URI uri, String host, int port, boolean tls, InetAddress address) {
        boolean defaultPort() {
            return tls ? port == 443 : port == 80;
        }
    }

    private record ParsedResponse(BrokerResponseHead head, boolean chunked, long declaredLength) {}

    private static final class SocketBrokerExchange implements BrokerExchange {
        private final Socket socket;
        private final InputStream input;
        private final ParsedResponse response;
        private final long maximum;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private long total;
        private long remaining;
        private long chunkRemaining;
        private boolean finished;
        private boolean closed;

        private SocketBrokerExchange(Socket socket, InputStream input, ParsedResponse response, long maximum) {
            this.socket = socket;
            this.input = input;
            this.response = response;
            this.maximum = maximum;
            this.remaining = response.declaredLength();
        }

        @Override
        public BrokerResponseHead head() {
            return response.head();
        }

        @Override
        public synchronized int read(byte[] destination, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, destination.length);
            if (cancelled.get()) {
                throw new IOException("network exchange was cancelled");
            }
            if (closed) {
                throw new IOException("network exchange is closed");
            }
            if (length == 0) {
                return 0;
            }
            if (finished) {
                return -1;
            }
            return response.chunked()
                    ? readChunked(destination, offset, length)
                    : readPlain(destination, offset, length);
        }

        private int readPlain(byte[] destination, int offset, int length) throws IOException {
            if (remaining == 0) {
                finished = true;
                return -1;
            }
            if (total == maximum) {
                int extra = input.read();
                if (extra >= 0) {
                    throw new IOException("network response exceeds limit");
                }
                if (remaining > 0) {
                    throw new EOFException("truncated HTTP response body");
                }
                finished = true;
                return -1;
            }
            int allowed = (int) Math.min(length, maximum - total);
            if (remaining >= 0) {
                allowed = (int) Math.min(allowed, remaining);
            }
            int count = input.read(destination, offset, allowed);
            if (count < 0) {
                if (remaining > 0) {
                    throw new EOFException("truncated HTTP response body");
                }
                finished = true;
                return -1;
            }
            total += count;
            if (remaining >= 0) {
                remaining -= count;
            }
            return count;
        }

        private int readChunked(byte[] destination, int offset, int length) throws IOException {
            while (chunkRemaining == 0) {
                String line = readLine(input, 1024);
                if (line == null) {
                    throw new EOFException("truncated chunked response");
                }
                int extension = line.indexOf(';');
                String sizeText = (extension < 0 ? line : line.substring(0, extension)).strip();
                try {
                    chunkRemaining = Long.parseLong(sizeText, 16);
                } catch (NumberFormatException failure) {
                    throw new IOException("invalid chunk size", failure);
                }
                if (chunkRemaining < 0 || chunkRemaining > maximum - total) {
                    throw new IOException("network response exceeds limit");
                }
                if (chunkRemaining == 0) {
                    while ((line = readLine(input, MAX_HEADER_BYTES)) != null && !line.isEmpty()) {}
                    finished = true;
                    return -1;
                }
            }
            int allowed = (int) Math.min(length, chunkRemaining);
            int count = input.read(destination, offset, allowed);
            if (count < 0) {
                throw new EOFException("truncated chunked response");
            }
            total += count;
            chunkRemaining -= count;
            if (chunkRemaining == 0 && (input.read() != '\r' || input.read() != '\n')) {
                throw new IOException("invalid chunk terminator");
            }
            return count;
        }

        @Override
        public void cancel() {
            if (cancelled.compareAndSet(false, true)) {
                try {
                    socket.close();
                } catch (IOException ignored) {
                }
            }
        }

        @Override
        public boolean cancelled() {
            return cancelled.get();
        }

        @Override
        public synchronized void close() throws IOException {
            if (closed) {
                return;
            }
            closed = true;
            socket.close();
        }
    }
}
