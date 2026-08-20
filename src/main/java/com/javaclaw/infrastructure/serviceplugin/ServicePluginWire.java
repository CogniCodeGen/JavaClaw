package com.javaclaw.infrastructure.serviceplugin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Host-side DTOs generated in shape from classpath protocol/service-plugin.yaml. */
final class ServicePluginWire {
    static final String SOURCE_SHA256 = "89b2bf2131ce00621c5bd34706ca526aa631d2222b444b71f52d4c142bc47f4b";
    static final int PROTOCOL_MAJOR = 1;
    static final int PROTOCOL_MINOR = 0;
    static final int MAX_FRAME_BYTES = 8 * 1024 * 1024;
    static final int MAX_REQUEST_BYTES = 32 * 1024 * 1024;

    private ServicePluginWire() { }

    enum Type {
        HELLO, HELLO_ACK, CONFIGURE, HOT_CONFIGURE, HOT_CONFIGURE_ACK,
        SERVICE_CATALOG, REQUEST, EVENT, RESPONSE,
        ERROR, CANCEL, HEALTH, PING, PONG, DRAIN, SHUTDOWN
    }

    record Frame(
            int protocolMajor,
            int protocolMinor,
            Type type,
            String requestId,
            long desktopGeneration,
            String pluginId,
            String pluginVersion,
            String serviceId,
            String operation,
            long sequence,
            long deadlineEpochMilli,
            String contentType,
            JsonNode payload,
            boolean terminal,
            String errorCode,
            String errorMessage) {
        Frame {
            requestId = text(requestId);
            pluginId = text(pluginId);
            pluginVersion = text(pluginVersion);
            serviceId = text(serviceId);
            operation = text(operation);
            contentType = text(contentType);
            errorCode = text(errorCode);
            errorMessage = text(errorMessage);
        }

        static Frame control(Type type, long generation, String pluginId, String version,
                             JsonNode payload) {
            return new Frame(PROTOCOL_MAJOR, PROTOCOL_MINOR, type, "", generation,
                    pluginId, version, "", "", 0, 0, "application/json",
                    payload, false, "", "");
        }

        private static String text(String value) { return value == null ? "" : value; }
    }

    record Hello(String token, long pid, long processStartEpochMilli,
                 String artifactSha256, String apiVersion) { }

    record Configure(String mainClass, Set<String> permissions, Map<String, String> config,
                     Resources resources, List<Endpoint> endpoints, String dataDirectory) { }
    record Resources(int heapMiB, int nativeMemoryMiB, int computeThreads, int ioConcurrency) { }

    record HotConfigure(Map<String, String> config, List<Endpoint> endpoints) {
        HotConfigure {
            config = config == null ? Map.of() : Map.copyOf(config);
            endpoints = endpoints == null ? List.of() : List.copyOf(endpoints);
        }
    }

    record HotConfigureResult(boolean success, String message) {
        HotConfigureResult { message = message == null ? "" : message; }
    }
    record Endpoint(String id, String protocol, String bindAddress, int port, boolean tlsEnabled,
                    boolean allowInsecureLan, String keyStorePath, String keyStorePassword,
                    String apiKey, int requestsPerMinute, long tokensPerMinute,
                    int maxConcurrent, int maxConnections, long maxRequestBytes,
                    int requestTimeoutSeconds) {
        Endpoint(String id, String protocol, String bindAddress, int port, boolean tlsEnabled,
                 boolean allowInsecureLan, String keyStorePath, String keyStorePassword,
                 String apiKey, int requestsPerMinute, long tokensPerMinute,
                 int maxConcurrent, int maxConnections, long maxRequestBytes) {
            this(id, protocol, bindAddress, port, tlsEnabled, allowInsecureLan,
                    keyStorePath, keyStorePassword, apiKey, requestsPerMinute,
                    tokensPerMinute, maxConcurrent, maxConnections, maxRequestBytes, 120);
        }

        Endpoint {
            requestTimeoutSeconds = requestTimeoutSeconds <= 0 ? 120
                    : Math.min(3_600, requestTimeoutSeconds);
        }
    }

    static final class Codec implements AutoCloseable {
        private final ObjectMapper json;
        private final DataInputStream input;
        private final DataOutputStream output;

        Codec(ObjectMapper json, DataInputStream input, DataOutputStream output) {
            this.json = json;
            this.input = input;
            this.output = output;
        }

        Frame read() throws IOException {
            int length;
            try { length = input.readInt(); }
            catch (EOFException eof) { return null; }
            if (length <= 0 || length > MAX_FRAME_BYTES) {
                throw new IOException("服务插件帧长度无效: " + length);
            }
            byte[] bytes = input.readNBytes(length);
            if (bytes.length != length) throw new EOFException("服务插件帧不完整");
            Frame frame = json.readValue(bytes, Frame.class);
            if (frame.protocolMajor() != PROTOCOL_MAJOR) {
                throw new IOException("服务插件协议主版本不兼容");
            }
            return frame;
        }

        synchronized void write(Frame frame) throws IOException {
            byte[] bytes = json.writeValueAsBytes(frame);
            if (bytes.length == 0 || bytes.length > MAX_FRAME_BYTES) {
                throw new IOException("服务插件帧超过 8 MiB 限制");
            }
            output.writeInt(bytes.length);
            output.write(bytes);
            output.flush();
        }

        @Override public void close() throws IOException {
            try { input.close(); } finally { output.close(); }
        }
    }
}
