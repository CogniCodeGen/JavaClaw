package com.javaclaw.plugin.sdk;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** 双向连接分别使用独立 Bearer；任何日志都不输出凭据。 */
public final class BearerTokens {
    private static final Metadata.Key<String> AUTHORIZATION =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);
    private BearerTokens() { }
    public static ServerInterceptor server(String token) {
        require(token);
        byte[] expected = ("Bearer " + token).getBytes(StandardCharsets.UTF_8);
        return new ServerInterceptor() {
            @Override public <Q, R> ServerCall.Listener<Q> interceptCall(
                    ServerCall<Q, R> call, Metadata headers, ServerCallHandler<Q, R> next) {
                String actual = headers.get(AUTHORIZATION);
                if (actual == null || !MessageDigest.isEqual(expected,
                        actual.getBytes(StandardCharsets.UTF_8))) {
                    call.close(Status.UNAUTHENTICATED.withDescription("插件连接凭据无效"), new Metadata());
                    return new ServerCall.Listener<>() { };
                }
                return next.startCall(call, headers);
            }
        };
    }
    public static ClientInterceptor client(String token) {
        require(token);
        return new ClientInterceptor() {
            @Override public <Q, R> ClientCall<Q, R> interceptCall(
                    MethodDescriptor<Q, R> method, CallOptions options, Channel channel) {
                return new ForwardingClientCall.SimpleForwardingClientCall<>(
                        channel.newCall(method, options)) {
                    @Override public void start(Listener<R> listener, Metadata headers) {
                        headers.put(AUTHORIZATION, "Bearer " + token);
                        super.start(listener, headers);
                    }
                };
            }
        };
    }
    private static void require(String token) {
        if (token == null || token.length() < 32 || token.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("插件凭据至少需要 32 个非空白字符");
        }
    }
}
