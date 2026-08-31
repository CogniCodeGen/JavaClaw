package com.javaclaw.agent.tools;

import java.net.IDN;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.javaclaw.agent.tool.RegisteredTool;
import com.javaclaw.agent.tool.ToolHandler;
import com.javaclaw.agent.tool.ToolOrigin;
import com.javaclaw.agent.tool.ToolRisk;
import com.javaclaw.core.api.ThreadItem;
import com.javaclaw.core.api.ToolDescriptor;
import com.javaclaw.sandbox.api.BrokerRequest;
import com.javaclaw.sandbox.api.BrokerResponse;
import com.javaclaw.sandbox.api.NetworkBroker;
import com.javaclaw.sandbox.api.NetworkPolicy;
import com.javaclaw.sandbox.api.SandboxPolicy;

/** First-party HTTP fetch capability. Agent-controlled processes never receive a network socket. */
public final class BrokeredWebFetchTool {
    private static final long MAXIMUM_RESPONSE_BYTES = 1024L * 1024L;
    private static final String SCHEMA = """
            {
              "type":"object",
              "additionalProperties":false,
              "required":["url"],
              "properties":{
                "url":{"type":"string","minLength":8,"maxLength":8192}
              }
            }
            """;

    private BrokeredWebFetchTool() {}

    /** 注册通过 NetworkBroker 获取有界网页的第一方工具；ceiling 仅是上限，实际调用还要与 Turn 策略求交。 */
    public static RegisteredTool create(NetworkBroker broker, SandboxPolicy ceiling) {
        Objects.requireNonNull(broker, "broker");
        return scoped(context -> broker, ceiling);
    }

    /** 由服务端按 Workspace 和 WEB 用途提供 Broker；私网授权不传入模型参数，也不能用于其他用途。 */
    public static RegisteredTool scoped(com.javaclaw.agent.tool.ScopedNetworkGateway brokers, SandboxPolicy ceiling) {
        Objects.requireNonNull(brokers, "brokers");
        ToolDescriptor descriptor = new ToolDescriptor(
                "web_fetch", "Fetch one explicitly approved public HTTP(S) URL through the network broker.", SCHEMA);
        return new RegisteredTool(descriptor, ToolOrigin.BUILTIN, ToolRisk.HIGH, true, ceiling, context -> {
                    URI uri = parse(context.arguments().path("url").asText());
                    String allowlistEntry = allowlistEntry(uri);
                    BrokerRequest request = new BrokerRequest(
                            "GET",
                            uri,
                            Map.of(
                                    "Accept",
                                    "text/html, text/plain, application/json;q=0.9, */*;q=0.1",
                                    "User-Agent",
                                    "JavaClaw/4.0"),
                            new byte[0],
                            Duration.ofSeconds(30),
                            MAXIMUM_RESPONSE_BYTES,
                            5);
                    BrokerResponse response = brokers.broker(context.call())
                            .execute(request, new NetworkPolicy(NetworkPolicy.Mode.ALLOWLIST, Set.of(allowlistEntry)));
                    String content = decode(response);
                    ThreadItem.WebSearch item = new ThreadItem.WebSearch(
                            uri.toString(), List.of(response.finalUri().toString()));
                    String modelContent = "HTTP " + response.statusCode() + " " + response.finalUri() + "\n" + content;
                    return new ToolHandler.Result(item, modelContent);
                })
                .readOnly();
    }

    private static URI parse(String value) {
        URI uri;
        try {
            uri = URI.create(value).normalize();
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("url is invalid", failure);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if ((!scheme.equals("http") && !scheme.equals("https"))
                || uri.isOpaque()
                || uri.getHost() == null
                || uri.getUserInfo() != null
                || uri.getFragment() != null) {
            throw new IllegalArgumentException("url must be an HTTP(S) origin without credentials");
        }
        return uri;
    }

    private static String allowlistEntry(URI uri) {
        String host = IDN.toASCII(uri.getHost(), IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
        int port = uri.getPort() >= 0 ? uri.getPort() : "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
        return host + ":" + port;
    }

    private static String decode(BrokerResponse response) {
        String contentType = response.headers().getOrDefault("content-type", List.of()).stream()
                .findFirst()
                .orElse("");
        Charset charset = StandardCharsets.UTF_8;
        for (String part : contentType.split(";")) {
            String value = part.strip();
            if (!value.toLowerCase(Locale.ROOT).startsWith("charset=")) {
                continue;
            }
            try {
                charset = Charset.forName(value.substring("charset=".length()).strip());
            } catch (RuntimeException ignored) {
                charset = StandardCharsets.UTF_8;
            }
        }
        String text = new String(response.body(), charset);
        // Binary responses remain bounded but are not injected into the model as arbitrary bytes.
        long controls = text.chars()
                .filter(value -> value == 0 || (value < 0x20 && value != '\n' && value != '\r' && value != '\t'))
                .count();
        if (!text.isEmpty() && controls * 100 > text.length()) {
            return "[binary response omitted; " + response.body().length + " bytes]";
        }
        return text;
    }
}
