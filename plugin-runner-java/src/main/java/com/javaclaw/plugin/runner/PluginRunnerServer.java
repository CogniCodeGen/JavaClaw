package com.javaclaw.plugin.runner;

import com.google.protobuf.Empty;
import com.javaclaw.plugin.protocol.v2.*;
import com.javaclaw.plugin.sdk.BearerTokens;
import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** 所有能力服务共用身份、鉴权、取消与排空边界。 */
public final class PluginRunnerServer implements AutoCloseable {
    private final PluginRuntime runtime;
    private final ExecutorService calls = Executors.newVirtualThreadPerTaskExecutor();
    private final Server server;
    public PluginRunnerServer(RunnerBootstrap bootstrap) {
        runtime = new PluginRuntime(bootstrap);
        var auth = BearerTokens.server(bootstrap.pluginToken());
        server = NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
                .executor(calls).maxInboundMessageSize(8 * 1024 * 1024)
                .addService(ServerInterceptors.intercept(new Control(), auth))
                .addService(ServerInterceptors.intercept(new ToolServiceGrpc.ToolServiceImplBase() {
                    @Override public void execute(CallRequest request, StreamObserver<RpcEvent> response) {
                        runtime.stream(CapabilityKind.TOOL, request, response);
                    }
                }, auth))
                .addService(ServerInterceptors.intercept(new InferenceServiceGrpc.InferenceServiceImplBase() {
                    @Override public void invoke(CallRequest request, StreamObserver<RpcEvent> response) {
                        runtime.stream(CapabilityKind.MODEL, request, response);
                    }
                }, auth))
                .addService(ServerInterceptors.intercept(new ContextServiceGrpc.ContextServiceImplBase() {
                    @Override public void retrieve(CallRequest request, StreamObserver<CallResult> response) {
                        unary(response, () -> runtime.retrieve(request));
                    }
                }, auth))
                .addService(ServerInterceptors.intercept(new WorkflowServiceGrpc.WorkflowServiceImplBase() {
                    @Override public void execute(CallRequest request, StreamObserver<RpcEvent> response) {
                        runtime.stream(CapabilityKind.WORKFLOW, request, response);
                    }
                }, auth))
                .addService(ServerInterceptors.intercept(new HookServiceGrpc.HookServiceImplBase() {
                    @Override public void invoke(HookRequest request, StreamObserver<HookResponse> response) {
                        unary(response, () -> runtime.hook(request));
                    }
                }, auth)).build();
    }
    public PluginRunnerServer start() throws IOException { server.start(); return this; }
    public int port() { return server.getPort(); }
    public void awaitTermination() throws InterruptedException { server.awaitTermination(); }
    private final class Control extends PluginControlGrpc.PluginControlImplBase {
        @Override public void initialize(InitializeRequest request, StreamObserver<PluginDescriptor> response) {
            unary(response, () -> runtime.initialize(request));
        }
        @Override public void validateConfiguration(ConfigurationRequest request, StreamObserver<ValidationResult> response) {
            unary(response, () -> runtime.validate(request));
        }
        @Override public void health(PluginIdentity request, StreamObserver<HealthResponse> response) {
            unary(response, () -> runtime.health(request));
        }
        @Override public void drain(DrainRequest request, StreamObserver<DrainResponse> response) {
            unary(response, () -> runtime.drain(request));
        }
        @Override public void cancel(CancelRequest request, StreamObserver<Empty> response) {
            unary(response, () -> { runtime.cancel(request); return Empty.getDefaultInstance(); });
        }
        @Override public void shutdown(PluginIdentity request, StreamObserver<Empty> response) {
            try { runtime.identity(request); }
            catch (Throwable failure) { PluginRuntime.fail(response, failure); return; }
            response.onNext(Empty.getDefaultInstance());
            response.onCompleted();
            Thread.ofVirtual().start(PluginRunnerServer.this::close);
        }
    }
    private static <T> void unary(StreamObserver<T> response, CheckedSupplier<T> action) {
        try { response.onNext(action.get()); response.onCompleted(); }
        catch (Throwable failure) { PluginRuntime.fail(response, failure); }
    }
    @FunctionalInterface private interface CheckedSupplier<T> { T get() throws Exception; }
    @Override public void close() {
        runtime.close();
        server.shutdown();
        try {
            if (!server.awaitTermination(2, TimeUnit.SECONDS)) server.shutdownNow();
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); server.shutdownNow(); }
        calls.shutdownNow();
    }
}
