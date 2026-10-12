package com.javaclaw.plugin.runner;

import com.javaclaw.plugin.protocol.v2.*;
import com.javaclaw.plugin.sdk.BearerTokens;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import static org.junit.jupiter.api.Assertions.*;

class PluginRunnerServerTest {
    @TempDir Path temp;
    private static final String TOKEN = "control-token-012345678901234567890123456789";
    private static final String HOST_TOKEN = "callback-token-012345678901234567890123456789";
    private static final String MANIFEST = """
            {"id":"fixture","entrypoint":"com.javaclaw.plugin.runner.FixturePlugin",
             "protocol":{"major":2,"minor":0},"contributions":[
              {"id":"echo","capability":"TOOL"},{"id":"context","capability":"CONTEXT"},
              {"id":"skill","capability":"SKILL"},{"id":"workflow","capability":"WORKFLOW"},
              {"id":"model","capability":"MODEL"},
              {"id":"policy","capability":"HOOK"}],"configurationSchema":{"type":"object"}}
            """;

    @Test void startsARealProcessAndKeepsPluginDependenciesPrivate() throws Exception {
        Path dependencySource = temp.resolve("dependency/com/google/gson/Gson.java");
        Files.createDirectories(dependencySource.getParent());
        Files.writeString(dependencySource, "package com.google.gson; public class Gson { public String isolated() { return \"private\"; } }");
        Path dependencyClasses = temp.resolve("dependency-classes");
        assertEquals(0, javax.tools.ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "-d", dependencyClasses.toString(), dependencySource.toString()));
        RunnerBootstrap bootstrap = bootstrap(dependencyClasses.resolve("com/google/gson/Gson.class"));
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        ProcessBuilder builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", classpath, PluginRunnerMain.class.getName());
        var env = builder.environment();
        env.put("JAVACLAW_PLUGIN_HOST_PORT", "65534");
        env.put("JAVACLAW_PLUGIN_TOKEN", TOKEN);
        env.put("JAVACLAW_HOST_TOKEN", HOST_TOKEN);
        env.put("JAVACLAW_PLUGIN_ID", bootstrap.identity().getPluginId());
        env.put("JAVACLAW_PLUGIN_HASH", bootstrap.identity().getArtifactHash());
        env.put("JAVACLAW_PLUGIN_REVISION", "1");
        env.put("JAVACLAW_WORKSPACE_ID", bootstrap.identity().getWorkspaceId());
        env.put("JAVACLAW_PLUGIN_INSTANCE_ID", bootstrap.identity().getInstanceId());
        env.put("JAVACLAW_PLUGIN_ARTIFACT", bootstrap.artifact().toString());
        env.put("JAVACLAW_PLUGIN_ARTIFACT_FILE_HASH", bootstrap.artifactFileHash());
        env.put("JAVACLAW_PLUGIN_MAIN", bootstrap.mainClass());
        env.put("JAVACLAW_PLUGIN_DATA_DIRECTORY", bootstrap.privateDataDirectory().toString());
        env.put("JAVACLAW_PLUGIN_PARENT_PID", Long.toString(ProcessHandle.current().pid()));
        builder.redirectError(temp.resolve("runner-stderr.log").toFile());
        Process process = builder.start();
        try (var reader = process.inputReader()) {
            var readerTask = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
            String line;
            try { line = readerTask.submit(reader::readLine).get(15, TimeUnit.SECONDS); }
            finally { readerTask.shutdownNow(); }
            assertNotNull(line);
            assertTrue(line.startsWith("JAVACLAW_PLUGIN_V2_READY "));
            int port = com.google.gson.JsonParser.parseString(line.substring(line.indexOf('{')))
                    .getAsJsonObject().get("port").getAsInt();
            ManagedChannel channel = channel(port);
            try {
                var control = PluginControlGrpc.newBlockingStub(channel).withInterceptors(BearerTokens.client(TOKEN));
                assertEquals("STARTING", control.health(bootstrap.identity()).getState());
                control.initialize(initialize(bootstrap));
                var stream = ToolServiceGrpc.newBlockingStub(channel).withInterceptors(BearerTokens.client(TOKEN))
                        .execute(request(bootstrap, "private-1", "echo").toBuilder().setOperation("private-dependency").build());
                assertEquals("{\"dependency\":\"private\"}", stream.next().getPayloadJson());
                assertEquals(EventType.COMPLETE, stream.next().getEventType());
                assertFalse(stream.hasNext());
                control.shutdown(bootstrap.identity());
                assertTrue(process.waitFor(10, TimeUnit.SECONDS));
                assertEquals(0, process.exitValue());
            } finally { channel.shutdownNow(); }
        } finally { if (process.isAlive()) process.destroyForcibly(); }
    }

    @Test void authenticatesBeforeLoadingAndRejectsIdentityOrProtocolChanges() throws Exception {
        System.clearProperty("javaclaw.fixture.loaded");
        RunnerBootstrap bootstrap = bootstrap();
        try (PluginRunnerServer server = new PluginRunnerServer(bootstrap).start()) {
            ManagedChannel channel = channel(server.port());
            try {
                assertNull(System.getProperty("javaclaw.fixture.loaded"));
                var anonymous = PluginControlGrpc.newBlockingStub(channel);
                assertStatus(Status.Code.UNAUTHENTICATED, () -> anonymous.health(bootstrap.identity()));
                var control = anonymous.withInterceptors(BearerTokens.client(TOKEN));
                assertStatus(Status.Code.PERMISSION_DENIED, () -> control.health(
                        bootstrap.identity().toBuilder().setWorkspaceId("other").build()));
                assertStatus(Status.Code.FAILED_PRECONDITION, () -> control.initialize(
                        initialize(bootstrap).toBuilder().setProtocolMajor(1).build()));
                assertNull(System.getProperty("javaclaw.fixture.loaded"));
                assertEquals(6, control.initialize(initialize(bootstrap)).getContributionsCount());
                assertEquals("true", System.getProperty("javaclaw.fixture.loaded"));
                assertStatus(Status.Code.PERMISSION_DENIED, () -> control.shutdown(
                        bootstrap.identity().toBuilder().setConfigurationRevision(2).build()));
                assertEquals("READY", control.health(bootstrap.identity()).getState());
            } finally { channel.shutdownNow(); }
        }
    }

    @Test void drainingBeforeInitializePreventsAnyPluginCodeFromLoading() throws Exception {
        System.clearProperty("javaclaw.fixture.loaded");
        RunnerBootstrap bootstrap = bootstrap();
        try (PluginRunnerServer server = new PluginRunnerServer(bootstrap).start()) {
            ManagedChannel channel = channel(server.port());
            try {
                var control = PluginControlGrpc.newBlockingStub(channel).withInterceptors(BearerTokens.client(TOKEN));
                assertTrue(control.drain(DrainRequest.newBuilder().setIdentity(bootstrap.identity()).build()).getDrained());
                assertStatus(Status.Code.FAILED_PRECONDITION, () -> control.initialize(initialize(bootstrap)));
                assertNull(System.getProperty("javaclaw.fixture.loaded"));
            } finally { channel.shutdownNow(); }
        }
    }

    @Test void executesRealGrpcAndPreventsReplayAndPolicyExpansion() throws Exception {
        RunnerBootstrap bootstrap = bootstrap();
        try (PluginRunnerServer server = new PluginRunnerServer(bootstrap).start()) {
            ManagedChannel channel = channel(server.port());
            try {
                var control = PluginControlGrpc.newBlockingStub(channel).withInterceptors(BearerTokens.client(TOKEN));
                control.initialize(initialize(bootstrap));
                var tools = ToolServiceGrpc.newBlockingStub(channel).withInterceptors(BearerTokens.client(TOKEN));
                CallRequest call = request(bootstrap, "request-1", "echo");
                var stream = tools.execute(call);
                assertEquals("{\"text\":\"你好\"}", stream.next().getPayloadJson());
                assertEquals(EventType.COMPLETE, stream.next().getEventType());
                assertFalse(stream.hasNext());
                assertStatus(Status.Code.ALREADY_EXISTS, () -> tools.execute(call).hasNext());
                var contexts = ContextServiceGrpc.newBlockingStub(channel).withInterceptors(BearerTokens.client(TOKEN));
                assertEquals("{\"text\":\"中文回复规范\"}", contexts.retrieve(request(bootstrap, "skill-1", "skill")).getPayloadJson());
                assertStatus(Status.Code.NOT_FOUND, () -> tools.execute(request(bootstrap, "skill-wrong-service", "skill")).hasNext());
                var workflow = WorkflowServiceGrpc.newBlockingStub(channel).withInterceptors(BearerTokens.client(TOKEN))
                        .execute(request(bootstrap, "workflow-1", "workflow"));
                assertTrue(workflow.next().getPayloadJson().contains("setValues"));
                assertEquals(EventType.COMPLETE, workflow.next().getEventType());
                var models = InferenceServiceGrpc.newBlockingStub(channel).withInterceptors(BearerTokens.client(TOKEN))
                        .invoke(request(bootstrap, "model-1", "model"));
                assertEquals(call.getPayloadJson(), models.next().getPayloadJson());
                assertEquals(EventType.COMPLETE, models.next().getEventType());
                assertEquals("{\"context\":true}", contexts.retrieve(request(bootstrap, "context-1", "context")).getPayloadJson());
                var hooks = HookServiceGrpc.newBlockingStub(channel).withInterceptors(BearerTokens.client(TOKEN));
                HookRequest hook = HookRequest.newBuilder().setContext(context(bootstrap, "hook-1"))
                        .setContributionId("policy").setPoint(HookPoint.EVALUATE_POLICY).setInputJson("{}")
                        .addAllAllowedPermissions(List.of("read", "write")).setRemainderBudget(10).build();
                assertEquals(1, hooks.invoke(hook).getRemainderBudget());
                assertStatus(Status.Code.PERMISSION_DENIED, () -> hooks.invoke(hook.toBuilder()
                        .setContext(context(bootstrap, "hook-2")).setInputJson("{\"escalate\":true}").build()));
                assertTrue(control.drain(DrainRequest.newBuilder().setIdentity(bootstrap.identity()).setTimeoutMillis(5000).build()).getDrained());
                // 旧租约由宿主验证，排空期间仍允许其继续调用已固定实例。
                assertEquals(EventType.DATA, tools.execute(request(bootstrap, "late", "echo")).next().getEventType());
            } finally { channel.shutdownNow(); }
        }
    }

    @Test void drainCountsSdkBackgroundWorkAndNeverBlocksTheImmediateProbe() throws Exception {
        RunnerBootstrap bootstrap = bootstrap();
        try (PluginRunnerServer server = new PluginRunnerServer(bootstrap).start()) {
            ManagedChannel channel = channel(server.port());
            try {
                var control = PluginControlGrpc.newBlockingStub(channel).withInterceptors(BearerTokens.client(TOKEN));
                control.initialize(initialize(bootstrap));
                var tools = ToolServiceGrpc.newBlockingStub(channel).withInterceptors(BearerTokens.client(TOKEN));
                var background = tools.execute(request(bootstrap, "background", "echo").toBuilder().setOperation("background").build());
                assertEquals(EventType.DATA, background.next().getEventType());
                long start = System.nanoTime();
                var first = control.drain(DrainRequest.newBuilder().setIdentity(bootstrap.identity()).build());
                assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(1));
                assertFalse(first.getDrained());
                assertTrue(first.getActiveRequests() >= 2, "RPC 与其尚未结束的 SDK 子任务分别计数");
                tools.execute(request(bootstrap, "old-lease", "echo")).forEachRemaining(ignored -> { });
                Files.writeString(bootstrap.privateDataDirectory().resolve("release"), "released");
                background.forEachRemaining(ignored -> { });
                var finalState = control.drain(DrainRequest.newBuilder().setIdentity(bootstrap.identity())
                        .setTimeoutMillis(5000).build());
                assertTrue(finalState.getDrained());
                assertEquals(0, finalState.getActiveRequests());
                assertEquals("drain\n", Files.readString(bootstrap.privateDataDirectory().resolve("drain-count")));
            } finally { channel.shutdownNow(); }
        }
    }

    @Test void hooksCanNarrowWildcardGrantsButCannotExpandSpecificGrantsOrBudgets() throws Exception {
        RunnerBootstrap bootstrap = bootstrap();
        try (PluginRunnerServer server = new PluginRunnerServer(bootstrap).start()) {
            ManagedChannel channel = channel(server.port());
            try {
                PluginControlGrpc.newBlockingStub(channel).withInterceptors(BearerTokens.client(TOKEN))
                        .initialize(initialize(bootstrap));
                var hooks = HookServiceGrpc.newBlockingStub(channel).withInterceptors(BearerTokens.client(TOKEN));
                HookRequest wildcard = HookRequest.newBuilder().setContext(context(bootstrap, "wildcard-narrow"))
                        .setContributionId("policy").setPoint(HookPoint.EVALUATE_POLICY).setInputJson("{}")
                        .addAllowedPermissions("*").setRemainderBudget(10).build();
                assertEquals(List.of("read"), hooks.invoke(wildcard).getAllowedPermissionsList());
                assertEquals(List.of("*"), hooks.invoke(wildcard.toBuilder()
                        .setContext(context(bootstrap, "wildcard-preserved")).setInputJson("{\"wildcard\":true}").build())
                        .getAllowedPermissionsList());
                HookRequest specific = wildcard.toBuilder().clearAllowedPermissions().addAllowedPermissions("read").build();
                assertStatus(Status.Code.PERMISSION_DENIED, () -> hooks.invoke(specific.toBuilder()
                        .setContext(context(bootstrap, "specific-to-wildcard")).setInputJson("{\"wildcard\":true}").build()));
                assertStatus(Status.Code.PERMISSION_DENIED, () -> hooks.invoke(specific.toBuilder()
                        .setContext(context(bootstrap, "specific-to-extra")).setInputJson("{\"extra\":true}").build()));
                assertStatus(Status.Code.PERMISSION_DENIED, () -> hooks.invoke(specific.toBuilder()
                        .setContext(context(bootstrap, "empty-to-specific")).clearAllowedPermissions().build()));
                assertStatus(Status.Code.PERMISSION_DENIED, () -> hooks.invoke(wildcard.toBuilder()
                        .setContext(context(bootstrap, "wildcard-budget-expansion")).setInputJson("{\"escalate\":true}").build()));
            } finally { channel.shutdownNow(); }
        }
    }

    @Test void cancellationInterruptsPluginAndDrainsActiveCall() throws Exception {
        RunnerBootstrap bootstrap = bootstrap();
        try (PluginRunnerServer server = new PluginRunnerServer(bootstrap).start()) {
            ManagedChannel channel = channel(server.port());
            try {
                var control = PluginControlGrpc.newBlockingStub(channel).withInterceptors(BearerTokens.client(TOKEN));
                control.initialize(initialize(bootstrap));
                CountDownLatch started = new CountDownLatch(1), terminated = new CountDownLatch(1);
                AtomicReference<Throwable> failure = new AtomicReference<>();
                ToolServiceGrpc.newStub(channel).withInterceptors(BearerTokens.client(TOKEN)).execute(
                        request(bootstrap, "waiting", "echo").toBuilder().setOperation("wait").build(),
                        new StreamObserver<>() {
                            @Override public void onNext(RpcEvent value) { started.countDown(); }
                            @Override public void onError(Throwable value) { failure.set(value); terminated.countDown(); }
                            @Override public void onCompleted() { terminated.countDown(); }
                        });
                assertTrue(started.await(5, TimeUnit.SECONDS));
                control.cancel(CancelRequest.newBuilder().setIdentity(bootstrap.identity()).setRequestId("waiting").build());
                assertTrue(terminated.await(5, TimeUnit.SECONDS));
                assertEquals(Status.Code.CANCELLED, Status.fromThrowable(failure.get()).getCode());
                assertTrue(control.drain(DrainRequest.newBuilder().setIdentity(bootstrap.identity()).setTimeoutMillis(5000).build()).getDrained());
            } finally { channel.shutdownNow(); }
        }
    }

    @Test void refusesTamperedJarBeforeExecutingItsConstructor() throws Exception {
        RunnerBootstrap valid = bootstrap();
        RunnerBootstrap tampered = new RunnerBootstrap(valid.identity(), valid.artifact(), "b".repeat(64),
                valid.mainClass(), valid.privateDataDirectory(), valid.hostPort(), TOKEN, HOST_TOKEN);
        System.clearProperty("javaclaw.fixture.loaded");
        try (PluginRunnerServer server = new PluginRunnerServer(tampered).start()) {
            ManagedChannel channel = channel(server.port());
            try {
                var control = PluginControlGrpc.newBlockingStub(channel).withInterceptors(BearerTokens.client(TOKEN));
                assertStatus(Status.Code.INTERNAL, () -> control.initialize(initialize(tampered)));
                assertNull(System.getProperty("javaclaw.fixture.loaded"));
            } finally { channel.shutdownNow(); }
        }
    }

    private RunnerBootstrap bootstrap() throws Exception {
        return bootstrap(null);
    }
    private RunnerBootstrap bootstrap(Path privateDependency) throws Exception {
        Path jar = temp.resolve("fixture.jar");
        String entry = "com/javaclaw/plugin/runner/FixturePlugin.class";
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar));
             var input = getClass().getClassLoader().getResourceAsStream(entry)) {
            output.putNextEntry(new JarEntry(entry));
            output.write(java.util.Objects.requireNonNull(input).readAllBytes());
            output.closeEntry();
            if (privateDependency != null) {
                output.putNextEntry(new JarEntry("com/google/gson/Gson.class"));
                output.write(Files.readAllBytes(privateDependency));
                output.closeEntry();
            }
        }
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar)));
        PluginIdentity identity = PluginIdentity.newBuilder().setPluginId("fixture").setWorkspaceId("workspace-a")
                .setArtifactHash("a".repeat(64)).setConfigurationRevision(1).setInstanceId("instance-1").build();
        return new RunnerBootstrap(identity, jar, hash, "com.javaclaw.plugin.runner.FixturePlugin",
                temp.resolve("private"), 65534, TOKEN, HOST_TOKEN);
    }
    private static InitializeRequest initialize(RunnerBootstrap bootstrap) {
        return InitializeRequest.newBuilder().setIdentity(bootstrap.identity()).setProtocolMajor(2)
                .setManifestJson(MANIFEST).setConfigurationJson("{}").build();
    }
    private static CallContext context(RunnerBootstrap bootstrap, String requestId) {
        return CallContext.newBuilder().setIdentity(bootstrap.identity()).setRequestId(requestId)
                .setRunId("run-1").setDeadlineEpochMillis(System.currentTimeMillis() + 10_000).build();
    }
    private static CallRequest request(RunnerBootstrap bootstrap, String id, String contribution) {
        return CallRequest.newBuilder().setContext(context(bootstrap, id)).setContributionId(contribution)
                .setPayloadJson("{\"text\":\"你好\"}").build();
    }
    private static ManagedChannel channel(int port) {
        return NettyChannelBuilder.forAddress("127.0.0.1", port).usePlaintext().disableRetry().build();
    }
    private static void assertStatus(Status.Code code, org.junit.jupiter.api.function.Executable action) {
        assertEquals(code, assertThrows(StatusRuntimeException.class, action).getStatus().getCode());
    }
}
