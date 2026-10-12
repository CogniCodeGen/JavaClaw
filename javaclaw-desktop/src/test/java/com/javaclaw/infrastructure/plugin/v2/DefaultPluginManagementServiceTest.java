package com.javaclaw.infrastructure.plugin.v2;

import com.javaclaw.application.plugin.v2.PluginManagementService.*;
import com.javaclaw.platform.execution.ManagedTaskExecutor;
import com.javaclaw.plugin.protocol.v2.CallContext;
import com.javaclaw.plugin.protocol.v2.CallRequest;
import com.javaclaw.plugin.protocol.v2.HostServicesGrpc;
import com.javaclaw.plugin.protocol.v2.PluginIdentity;
import com.javaclaw.plugin.protocol.v2.BackgroundLease;
import com.javaclaw.plugin.protocol.v2.BackgroundLeaseRequest;
import com.javaclaw.plugin.protocol.v2.BackgroundLeaseCloseRequest;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.MetadataUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class DefaultPluginManagementServiceTest {
    @TempDir Path temporary;
    private Path root;
    private Path runner;
    private byte[] jar;

    @BeforeEach void prepare() throws Exception {
        root = temporary.toRealPath();
        Path project = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        if (!Files.isDirectory(project.resolve("plugin-runner-java"))) project = project.getParent();
        List<Path> runners = DefaultPluginManagementService.discoverRunnerClasspath(project);
        assumeTrue(!runners.isEmpty(), "真实进程测试需要先 package plugin-runner-java");
        runner = runners.getFirst(); jar = PluginFixture.executableJar(root, runner);
    }

    @Test void isolatesProcessAndPinsOldConfigurationsWhileCandidateBecomesReady() throws Exception {
        try (var tasks = new ManagedTaskExecutor(); var service = service(tasks)) {
            install(service, "1.0"); service.activateWorkspace("first");
            configure(service, "first", "old", Map.of("apiKey", SecretChange.set("hidden-secret")), Set.of("storage.read")); service.setEnabled("first", "fixture", true);
            service.start("first", "fixture");
            try (RunLease old = service.acquireRun("first", "fixture")) {
                assertTrue(call(service, old, "isolation").contains("isolated"));
                assertTrue(call(service, old, "echo").contains("old"));
                long revision = old.configurationRevision();
                configure(service, "first", "new", Map.of(), Set.of("storage.read"));
                try (RunLease latest = service.acquireRun("first", "fixture")) {
                    assertNotEquals(old.instanceId(), latest.instanceId()); assertEquals(revision + 1, latest.configurationRevision());
                    assertTrue(call(service, old, "echo").contains("old")); assertTrue(call(service, latest, "echo").contains("new"));
                    assertTrue(call(service, latest, "echo").contains("hidden-secret"));
                    assertEquals("fixture", PluginFixture.JSON.readTree(latest.contributions().getFirst().metadataJson()).path("runtime").asText());
                }
                try (RunLease recovery = service.acquireRun("first", "fixture", old.artifactHash(), revision)) {
                    assertEquals(old.instanceId(), recovery.instanceId()); assertTrue(call(service, recovery, "echo").contains("old"));
                }
                long retained = service.configuration("first", "fixture").revision();
                assertThrows(PluginException.class, () -> configure(service, "first", "reject", Map.of(), Set.of("storage.read")));
                assertEquals(retained, service.configuration("first", "fixture").revision());
                assertTrue(call(service, old, "echo").contains("old"));
            }
        }
    }

    @Test void authenticatesCallbacksAndImmediatelyRevokesOldLeases() throws Exception {
        try (var tasks = new ManagedTaskExecutor(); var service = service(tasks)) {
            install(service, "1.0"); service.activateWorkspace("first");
            configure(service, "first", "callback", Map.of(), Set.of("storage.read")); service.setEnabled("first", "fixture", true);
            AtomicReference<PluginHostServiceRegistry.HostRequest> captured = new AtomicReference<>();
            service.hostServices().register("storage", Set.of("read"), "storage.read", (request, cancelled, events) -> {
                captured.set(request); return "{\"workspace\":\"" + request.workspaceId() + "\"}";
            });
            try (RunLease old = service.acquireRun("first", "fixture")) {
                assertTrue(call(service, old, "host").contains("first"));
                assertEquals("first", captured.get().workspaceId()); assertEquals(old.runId(), captured.get().runId()); assertFalse(captured.get().background());
                var credentials = PluginFixture.JSON.readTree(call(service, old, "credentials"));
                ManagedChannel channel = NettyChannelBuilder.forAddress("127.0.0.1", credentials.path("port").asInt()).usePlaintext().build();
                Metadata headers = new Metadata(); headers.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), "Bearer " + credentials.path("token").asText());
                var client = HostServicesGrpc.newBlockingStub(channel).withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers)).withDeadlineAfter(5, TimeUnit.SECONDS);
                PluginIdentity identity = PluginIdentity.newBuilder().setPluginId(old.pluginId()).setWorkspaceId(old.workspaceId()).setArtifactHash(old.artifactHash()).setConfigurationRevision(old.configurationRevision()).setInstanceId(old.instanceId()).build();
                CallContext context = CallContext.newBuilder().setIdentity(identity).setRequestId("forged").setRunId(old.runId()).setDeadlineEpochMillis(System.currentTimeMillis() + 5000).build();
                CallRequest forged = CallRequest.newBuilder().setContext(context.toBuilder().setIdentity(identity.toBuilder().setWorkspaceId("second"))).setContributionId("storage").setOperation("read").setPayloadJson("{}").build();
                assertEquals(Status.Code.PERMISSION_DENIED, assertThrows(StatusRuntimeException.class, () -> client.invoke(forged).hasNext()).getStatus().getCode());
                service.revoke("first", "fixture");
                CallRequest stale = forged.toBuilder().setContext(context).build();
                assertEquals(Status.Code.UNAUTHENTICATED, assertThrows(StatusRuntimeException.class, () -> client.invoke(stale).hasNext()).getStatus().getCode());
                channel.shutdownNow();
                assertThrows(PluginException.class, () -> service.invoke(old, "TOOL", "echo", "host", "{}", Duration.ofSeconds(10), event -> { }));
                assertEquals("RECOVERY_BLOCKED", assertThrows(PluginException.class, () -> service.acquireRun("first", "fixture", old.artifactHash(), old.configurationRevision())).code());
            }
        }
    }

    @Test void authenticatesBackgroundReceiptsAndPreservesExistingOnDrain() throws Exception {
        try (var tasks = new ManagedTaskExecutor(); var service = service(tasks)) {
            install(service, "1.0"); service.activateWorkspace("first");
            configure(service, "first", "old", Map.of(), Set.of("storage.read")); service.setEnabled("first", "fixture", true);
            AtomicReference<PluginHostServiceRegistry.HostRequest> captured = new AtomicReference<>();
            AutoCloseable registration = service.hostServices().register("storage", Set.of("read"), "storage.read", (request, cancelled, events) -> "{}");
            try (RunLease old = service.acquireRun("first", "fixture"); HostProbe probe = probe(service, old)) {
                assertThrows(ExecutionException.class, () -> call(service, old, "background"));
                BackgroundLeaseRequest firstRequest = BackgroundLeaseRequest.newBuilder().setContext(probe.context("")).setTtlMillis(30_000).build();
                var oversized = firstRequest.toBuilder().setContext(firstRequest.getContext().toBuilder().setRequestId("x".repeat(129))).build();
                assertEquals(Status.Code.PERMISSION_DENIED, assertThrows(StatusRuntimeException.class, () -> probe.client.openBackground(oversized)).getStatus().getCode());
                BackgroundLease receipt = probe.client.withDeadlineAfter(3, TimeUnit.SECONDS).openBackground(firstRequest);
                assertEquals(Status.Code.PERMISSION_DENIED, assertThrows(StatusRuntimeException.class, () -> probe.client.closeBackground(BackgroundLeaseCloseRequest.newBuilder().setIdentity(probe.identity).setLeaseId("not-a-uuid").build())).getStatus().getCode());
                assertEquals(Status.Code.ALREADY_EXISTS, assertThrows(StatusRuntimeException.class, () -> probe.client.openBackground(firstRequest)).getStatus().getCode());
                assertThrows(StatusRuntimeException.class, () -> probe.invoke(receipt.getRunId()));
                registration.close();
                service.hostServices().register("storage", Set.of("read"), "storage.read", true, (request, cancelled, events) -> { captured.set(request); return "{\"background\":true}"; });
                probe.invoke(receipt.getRunId()); assertTrue(captured.get().background());
                BackgroundLease expired = probe.open(50); Thread.sleep(80);
                assertThrows(StatusRuntimeException.class, () -> probe.invoke(expired.getRunId()));
                var wrongIdentity = firstRequest.toBuilder().setContext(probe.context("").toBuilder().setIdentity(probe.identity.toBuilder().setWorkspaceId("second"))).build();
                assertEquals(Status.Code.PERMISSION_DENIED, assertThrows(StatusRuntimeException.class, () -> probe.client.openBackground(wrongIdentity)).getStatus().getCode());
                configure(service, "first", "new", Map.of(), Set.of("storage.read"));
                assertTrue(call(service, old, "host").contains("background")); assertFalse(captured.get().background());
                assertEquals(Status.Code.FAILED_PRECONDITION, assertThrows(StatusRuntimeException.class, () -> probe.open(1000)).getStatus().getCode());
                old.close(); probe.invoke(receipt.getRunId()); assertTrue(captured.get().background());
                assertEquals("1", service.diagnostics("first", "fixture").health().get("backgroundLeases"));
                probe.client.closeBackground(BackgroundLeaseCloseRequest.newBuilder().setIdentity(probe.identity).setLeaseId(receipt.getLeaseId()).build());
                await(() -> service.diagnostics("first", "fixture").health().get("drainingInstances").equals("0"));
                assertThrows(StatusRuntimeException.class, () -> probe.invoke(receipt.getRunId()));
            }
        }
    }

    @Test void globallyUninstallsAfterAllLeasesDrainAndRetainsOrDeletesOnlyPrivateData() throws Exception {
        try (var tasks = new ManagedTaskExecutor(); var service = service(tasks)) {
            List<PluginSecurityEvent> security = new ArrayList<>(); service.observeSecurityEvents(security::add);
            install(service, "1.0"); service.activateWorkspace("first"); service.activateWorkspace("second");
            for (String workspace : List.of("first", "second")) {
                configure(service, workspace, workspace, Map.of(), Set.of("storage.read")); service.setEnabled(workspace, "fixture", true);
            }
            Path external = root.resolve("external-model.bin"); Files.writeString(external, "external");
            Path firstData = root.resolve("workspaces/first/plugins/fixture/data/state.json");
            Path secondData = root.resolve("workspaces/second/plugins/fixture/data/state.json");
            RunLease first = service.acquireRun("first", "fixture"); RunLease second = service.acquireRun("second", "fixture");
            Files.writeString(firstData, "first"); Files.writeString(secondData, "second");
            assertEquals(2, service.previewUninstall("first", "fixture").workspaceReferences().size());
            service.uninstall("first", "fixture", false);
            assertEquals(Set.of("first", "second"), security.stream().filter(event -> event.reason() == SecurityReason.UNINSTALL).map(PluginSecurityEvent::workspaceId).collect(java.util.stream.Collectors.toSet()));
            assertEquals(State.UNINSTALLING, service.details("second", "fixture").summary().state());
            assertThrows(PluginException.class, () -> service.acquireRun("second", "fixture"));
            first.close(); assertTrue(Files.exists(root.resolve("artifacts/fixture")));
            second.close(); await(() -> !Files.exists(root.resolve("artifacts/fixture")));
            assertTrue(Files.exists(firstData)); assertTrue(Files.exists(secondData));
            install(service, "2.0");
            assertEquals(State.FAILED, service.details("second", "fixture").summary().state());
            assertEquals(1, service.list("second").size());
            var replacement = service.versions("second", "fixture").getFirst();
            service.selectVersion("second", "fixture", replacement.artifactHash(), service.configuration("second", "fixture").revision());
            assertEquals("2.0", service.details("second", "fixture").summary().version());
            service.uninstall("first", "fixture", true);
            assertEquals(Set.of("first", "second"), security.stream().filter(event -> event.reason() == SecurityReason.UNINSTALL_DELETE_DATA).map(PluginSecurityEvent::workspaceId).collect(java.util.stream.Collectors.toSet()));
            assertFalse(Files.exists(root.resolve("workspaces/first/plugins/fixture"))); assertFalse(Files.exists(root.resolve("workspaces/second/plugins/fixture")));
            assertEquals("external", Files.readString(external));
        }
    }

    @Test void restoresExactArtifactAndConfigHistoryAcrossHostRestart() throws Exception {
        String hash; long revision;
        try (var tasks = new ManagedTaskExecutor(); var service = service(tasks)) {
            install(service, "1.0"); service.activateWorkspace("first");
            configure(service, "first", "retained", Map.of("apiKey", SecretChange.set("old-key")), Set.of("storage.read")); service.setEnabled("first", "fixture", true);
            try (RunLease lease = service.acquireRun("first", "fixture")) { hash = lease.artifactHash(); revision = lease.configurationRevision(); }
            configure(service, "first", "current", Map.of("apiKey", SecretChange.set("new-key")), Set.of("storage.read"));
            install(service, "2.0");
        }
        try (var tasks = new ManagedTaskExecutor(); var service = service(tasks)) {
            service.activateWorkspace("first");
            try (RunLease recovered = service.acquireRun("first", "fixture", hash, revision)) {
                String value = call(service, recovered, "echo"); assertTrue(value.contains("retained")); assertTrue(value.contains("old-key"));
            }
            assertEquals("RECOVERY_BLOCKED", assertThrows(PluginException.class, () -> service.acquireRun("first", "fixture", "0".repeat(64), revision)).code());
        }
    }

    @Test void grantReductionInvalidatesExistingEpochWithoutSilentlyKeepingOldPermissions() throws Exception {
        try (var tasks = new ManagedTaskExecutor(); var service = service(tasks)) {
            install(service, "1.0"); service.activateWorkspace("first");
            configure(service, "first", "old", Map.of(), Set.of("storage.read")); service.setEnabled("first", "fixture", true);
            try (RunLease lease = service.acquireRun("first", "fixture"); HostProbe probe = probe(service, lease)) {
                BackgroundLease receipt = probe.open(30_000);
                PluginInvocation waiting = service.invoke(lease, "TOOL", "echo", "wait", "{}", Duration.ofSeconds(20), event -> { });
                configure(service, "first", "restricted", Map.of(), Set.of());
                assertTrue(waiting.completion().isDone());
                assertEquals(lease.configurationRevision() + 1, service.configuration("first", "fixture").revision());
                assertTrue(service.configuration("first", "fixture").grantedPermissions().isEmpty());
                assertEquals(Status.Code.UNAUTHENTICATED, assertThrows(StatusRuntimeException.class, () -> probe.invoke(receipt.getRunId())).getStatus().getCode());
                assertThrows(PluginException.class, () -> call(service, lease, "echo"));
                assertEquals("RECOVERY_BLOCKED", assertThrows(PluginException.class, () -> service.acquireRun("first", "fixture", lease.artifactHash(), lease.configurationRevision())).code());
            }
        }
    }

    @Test void exclusiveConfigurationWaitsForOldLeaseAndRejectsConcurrentAdmission() throws Exception {
        try (var tasks = new ManagedTaskExecutor(); var service = service(tasks)) {
            installExclusive(service); service.activateWorkspace("first");
            configure(service, "first", "old", Map.of(), Set.of("storage.read")); service.setEnabled("first", "fixture", true);
            RunLease old = service.acquireRun("first", "fixture");
            CompletableFuture<Void> change = CompletableFuture.runAsync(() -> configure(service, "first", "candidate", Map.of(), Set.of("storage.read")));
            assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
                while (service.details("first", "fixture").summary().state() != State.DRAINING) Thread.sleep(10);
            });
            assertFalse(change.isDone()); assertTrue(call(service, old, "echo").contains("old"));
            assertEquals("RESOURCE_BUSY", assertThrows(PluginException.class, () -> service.acquireRun("first", "fixture")).code());
            old.close(); change.get(30, TimeUnit.SECONDS);
            try (RunLease candidate = service.acquireRun("first", "fixture")) { assertTrue(call(service, candidate, "echo").contains("candidate")); }
        }
    }

    @Test void stoppingWhileExclusiveChangeWaitsNeverPublishesItsCandidate() throws Exception {
        cancelsPendingExclusiveChange(service -> service.stop("first", "fixture"), service -> {
            assertEquals(State.STOPPED, service.details("first", "fixture").summary().state());
            assertEquals("PLUGIN_STOPPED", assertThrows(PluginException.class, () -> service.acquireRun("first", "fixture")).code());
            service.start("first", "fixture");
            try (RunLease resumed = service.acquireRun("first", "fixture")) { assertTrue(call(service, resumed, "echo").contains("old")); }
        });
    }

    @Test void suspendingWhileExclusiveChangeWaitsKeepsWorkspaceInactive() throws Exception {
        cancelsPendingExclusiveChange(service -> service.suspendWorkspace("first"), service -> {
            assertEquals("WORKSPACE_INACTIVE", assertThrows(PluginException.class, () -> service.acquireRun("first", "fixture")).code());
            service.activateWorkspace("first");
            assertEquals(0, service.diagnostics("first", "fixture").pid());
            assertEquals(State.STOPPED, service.details("first", "fixture").summary().state());
        });
    }

    @Test void uninstallingWhileExclusiveChangeWaitsCannotRecreateDeletedArtifacts() throws Exception {
        cancelsPendingExclusiveChange(service -> service.uninstall("first", "fixture", false), service -> {
            await(() -> !Files.exists(root.resolve("artifacts/fixture")));
            assertTrue(service.list("first").isEmpty());
            assertEquals("PLUGIN_NOT_FOUND", assertThrows(PluginException.class, () -> service.acquireRun("first", "fixture")).code());
        });
    }

    @Test void revokingWhileExclusiveChangeWaitsNeverRestoresOldAuthorization() throws Exception {
        cancelsPendingExclusiveChange(service -> service.revoke("first", "fixture"), service -> {
            assertEquals(State.REVOKED, service.details("first", "fixture").summary().state());
            assertTrue(service.configuration("first", "fixture").grantedPermissions().isEmpty());
            assertEquals("PLUGIN_DISABLED", assertThrows(PluginException.class, () -> service.acquireRun("first", "fixture")).code());
        });
    }

    @Test void rechecksSignedArtifactBeforeStartingAnInstalledPlugin() throws Exception {
        try (var tasks = new ManagedTaskExecutor(); var service = service(tasks)) {
            install(service, "1.0"); service.activateWorkspace("first");
            configure(service, "first", "ready", Map.of(), Set.of("storage.read")); service.setEnabled("first", "fixture", true);
            service.stop("first", "fixture"); await(() -> service.details("first", "fixture").summary().state() == State.STOPPED);
            String hash = service.details("first", "fixture").summary().artifactHash();
            Files.write(root.resolve("artifacts/fixture/versions").resolve(hash).resolve("plugin.jar"), new byte[] {1, 2, 3});
            assertEquals("ARTIFACT_TAMPERED", assertThrows(PluginException.class, () -> service.start("first", "fixture")).code());
        }
    }

    @Test void systemModulesRequireApprovalBeforeStartingARealRunner() throws Exception {
        try (var tasks = new ManagedTaskExecutor(); var service = service(tasks)) {
            installWithModule(service, "java.sql"); service.activateWorkspace("first");
            assertTrue(service.details("first", "fixture").requiredPermissions().contains("runtime.module:java.sql"));
            configure(service, "first", "unapproved-module", Map.of(), Set.of("storage.read"));
            assertEquals("PERMISSION_REQUIRED", assertThrows(PluginException.class, () -> service.setEnabled("first", "fixture", true)).code());
            assertEquals(0, service.diagnostics("first", "fixture").pid());
            configure(service, "first", "approved-module", Map.of(), Set.of("storage.read", "runtime.module:java.sql"));
            service.start("first", "fixture");
            try (RunLease lease = service.acquireRun("first", "fixture")) {
                assertEquals(State.READY, service.details("first", "fixture").summary().state());
                assertTrue(service.diagnostics("first", "fixture").pid() > 0);
                assertTrue(call(service, lease, "echo").contains("approved-module"));
            }
        }
    }

    @Test void missingDeclaredSystemModuleFailsBeforeLaunchingAnyProcess() throws Exception {
        try (var tasks = new ManagedTaskExecutor(); var service = service(tasks)) {
            String module = "jdk.javaclaw.missing";
            assertTrue(java.lang.module.ModuleFinder.ofSystem().find(module).isEmpty());
            installWithModule(service, module); service.activateWorkspace("first");
            configure(service, "first", "missing-module", Map.of(), Set.of("storage.read", "runtime.module:" + module));
            assertEquals("RUNTIME_MODULE_UNAVAILABLE", assertThrows(PluginException.class, () -> service.setEnabled("first", "fixture", true)).code());
            assertEquals(State.FAILED, service.details("first", "fixture").summary().state());
            assertTrue(service.details("first", "fixture").summary().lastError().contains(module));
            assertEquals(0, service.diagnostics("first", "fixture").pid());
        }
    }

    @Test void importsDistributionPackagesStaticallyDisabledAndPreservesWorkspaceChoices() throws Exception {
        Path packages = Files.createDirectories(root.resolve("runtime/plugin-packages"));
        Path zip = PluginFixture.archive(packages, "1.0", jar);
        Path distribution = root.resolve("runtime/plugin-distribution.json");
        Files.writeString(distribution, PluginFixture.JSON.writeValueAsString(Map.of("packages", List.of(Map.of("path", root.relativize(zip).toString(), "sha256", PluginPaths.sha256(zip))))));
        try (var tasks = new ManagedTaskExecutor(); var service = service(tasks)) {
            service.activateWorkspace("first");
            var details = service.details("first", "fixture");
            assertFalse(details.summary().enabled()); assertEquals(StartupPolicy.MANUAL, details.summary().startupPolicy());
            assertTrue(details.configuration().grantedPermissions().isEmpty()); assertEquals(0, service.diagnostics("first", "fixture").pid());
            configure(service, "first", "user-choice", Map.of(), Set.of("storage.read")); service.setEnabled("first", "fixture", true);
            service.activateWorkspace("first");
            assertTrue(service.details("first", "fixture").summary().enabled()); assertTrue(service.configuration("first", "fixture").configurationJson().contains("user-choice"));
            try (var staging = Files.list(root.resolve("staging"))) { assertEquals(0, staging.count()); }
            service.uninstall("first", "fixture", true); await(() -> !Files.exists(root.resolve("artifacts/fixture"))); service.activateWorkspace("second");
            assertTrue(service.list("second").isEmpty());
            Files.writeString(distribution, "{\"packages\":[{\"path\":\"../escape.zip\",\"sha256\":\"" + "0".repeat(64) + "\"}]}");
            assertEquals("FAILED", new BundledPluginInstaller(service, PluginFixture.JSON).importPackages(root, "second").getFirst().status());
        }
    }

    @Test void replaysUninstallCleanupAfterRestartBeforeDeletingPrivateFiles() throws Exception {
        try (var tasks = new ManagedTaskExecutor(); var service = service(tasks)) {
            install(service, "1.0"); service.details("second", "fixture");
            service.activateWorkspace("first"); configure(service, "first", "cleanup", Map.of(), Set.of("storage.read"));
            service.setEnabled("first", "fixture", true);
            RunLease lease = service.acquireRun("first", "fixture");
            service.observeSecurityEvents(event -> {
                if (event.workspaceId().equals("first")) lease.close();
                else throw new IllegalStateException("模拟后续工作区宿主清理中断");
            });
            assertEquals("HOST_CLEANUP_FAILED", assertThrows(PluginException.class, () -> service.uninstall("first", "fixture", true)).code());
            assertTrue(Files.exists(root.resolve("artifacts/fixture/uninstall.json")));
            assertTrue(Files.exists(root.resolve("workspaces/first/plugins/fixture")));
            assertTrue(Files.exists(root.resolve("workspaces/second/plugins/fixture")));
        }
        try (var tasks = new ManagedTaskExecutor(); var service = service(tasks)) {
            List<String> cleaned = new ArrayList<>();
            assertTrue(Files.exists(root.resolve("workspaces/first/plugins/fixture")));
            service.observeSecurityEvents(event -> {
                assertTrue(Set.of(SecurityReason.UNINSTALL, SecurityReason.UNINSTALL_DELETE_DATA).contains(event.reason()));
                assertTrue(Files.exists(root.resolve("workspaces/" + event.workspaceId() + "/plugins/fixture")));
                if (event.reason() == SecurityReason.UNINSTALL_DELETE_DATA) cleaned.add(event.workspaceId());
            });
            service.activateWorkspace("first");
            assertEquals(Set.of("first", "second"), Set.copyOf(cleaned));
            assertFalse(Files.exists(root.resolve("artifacts/fixture")));
            assertFalse(Files.exists(root.resolve("workspaces/first/plugins/fixture")));
            assertFalse(Files.exists(root.resolve("workspaces/second/plugins/fixture")));
        }
    }

    @Test void stopClosesAdmissionAndKeepsDrainingDiagnosticsUntilExplicitStart() throws Exception {
        try (var tasks = new ManagedTaskExecutor(); var service = service(tasks)) {
            install(service, "1.0"); service.activateWorkspace("first");
            configure(service, "first", "draining", Map.of(), Set.of("storage.read")); service.setEnabled("first", "fixture", true);
            try (RunLease lease = service.acquireRun("first", "fixture")) {
                service.stop("first", "fixture");
                assertEquals(State.DRAINING, service.details("first", "fixture").summary().state());
                assertEquals("1", service.diagnostics("first", "fixture").health().get("activeRuns"));
                assertEquals(lease.instanceId(), service.diagnostics("first", "fixture").instanceId());
                assertEquals("PLUGIN_STOPPED", assertThrows(PluginException.class, () -> service.acquireRun("first", "fixture")).code());
                assertEquals("RECOVERY_BLOCKED", assertThrows(PluginException.class, () -> service.acquireRun("first", "fixture", lease.artifactHash(), lease.configurationRevision())).code());
                assertTrue(call(service, lease, "echo").contains("draining"));
            }
            await(() -> service.details("first", "fixture").summary().state() == State.STOPPED);
            service.start("first", "fixture");
            try (RunLease restarted = service.acquireRun("first", "fixture")) { assertTrue(call(service, restarted, "echo").contains("draining")); }
        }
    }

    @Test void forceStopCancelsCallsAndIdentityWithoutRevokingSavedAuthorization() throws Exception {
        try (var tasks = new ManagedTaskExecutor(); var service = service(tasks)) {
            install(service, "1.0"); service.activateWorkspace("first");
            configure(service, "first", "force", Map.of(), Set.of("storage.read")); service.setEnabled("first", "fixture", true);
            try (RunLease lease = service.acquireRun("first", "fixture")) {
                var call = service.invoke(lease, "TOOL", "echo", "wait", "{}", Duration.ofSeconds(20), event -> { });
                service.forceStop("first", "fixture");
                assertTrue(call.completion().isDone()); assertEquals(State.STOPPED, service.details("first", "fixture").summary().state());
                assertEquals(Set.of("storage.read"), service.configuration("first", "fixture").grantedPermissions()); assertTrue(service.details("first", "fixture").summary().enabled());
                assertThrows(PluginException.class, () -> service.acquireRun("first", "fixture"));
                assertThrows(PluginException.class, () -> call(service, lease, "echo"));
                service.start("first", "fixture");
                try (RunLease next = service.acquireRun("first", "fixture")) { assertNotEquals(lease.instanceId(), next.instanceId()); }
            }
        }
    }

    @Test void explicitEnablePublishesManualInstanceAndStopWaitsForBackgroundTasks() throws Exception {
        try (var tasks = new ManagedTaskExecutor(); var service = service(tasks)) {
            install(service, "1.0"); service.activateWorkspace("first");
            configure(service, "first", "background-drain", Map.of(), Set.of("storage.read"));
            service.setEnabled("first", "fixture", true);
            assertEquals(StartupPolicy.MANUAL, service.details("first", "fixture").summary().startupPolicy());
            assertEquals(State.READY, service.details("first", "fixture").summary().state());
            long pid = service.diagnostics("first", "fixture").pid();
            service.stop("first", "fixture");
            assertEquals(State.DRAINING, service.details("first", "fixture").summary().state());
            Thread.sleep(150);
            assertTrue(ProcessHandle.of(pid).orElseThrow().isAlive());
            await(() -> service.details("first", "fixture").summary().state() == State.STOPPED);
        }
    }

    @Test void hookNarrowsWildcardRunPermissionsAndRejectsEscalation() throws Exception {
        String manifest = PluginFixture.manifest("1.0").replace("\"TOOL\"", "\"HOOK\"");
        Path zip = PluginFixture.zip(root.resolve("hook.zip"), PluginFixture.signed(Map.of("plugin.json", manifest.getBytes(java.nio.charset.StandardCharsets.UTF_8), "plugin.jar", jar)));
        try (var tasks = new ManagedTaskExecutor(); var service = service(tasks)) {
            service.install("first", service.inspectPackage(zip).previewId()); service.activateWorkspace("first");
            configure(service, "first", "hook-mode", Map.of(), Set.of("storage.read")); service.setEnabled("first", "fixture", true);
            try (RunLease lease = service.acquireRun("first", "fixture")) {
                var narrowed = service.invoke(lease, "HOOK", "echo", "constraint", "{\"input\":{},\"allowedPermissions\":[\"*\"],\"remainderBudget\":5}", Duration.ofSeconds(5), event -> { });
                var permissions = PluginFixture.JSON.readTree(narrowed.completion().get(10, TimeUnit.SECONDS).payloadJson()).path("allowedPermissions");
                assertEquals(1, permissions.size()); assertEquals("tool.execute", permissions.get(0).asText());
                var escalated = service.invoke(lease, "HOOK", "echo", "constraint", "{\"input\":{\"escalate\":true},\"allowedPermissions\":[\"tool.execute\"],\"remainderBudget\":5}", Duration.ofSeconds(5), event -> { });
                assertThrows(ExecutionException.class, () -> escalated.completion().get(10, TimeUnit.SECONDS));
            }
        }
    }

    @Test void deletesHostPrivateDataOnlyAfterExistingBackgroundReceiptsFinish() throws Exception {
        try (var tasks = new ManagedTaskExecutor(); var service = service(tasks)) {
            install(service, "1.0"); service.activateWorkspace("first");
            configure(service, "first", "private-data", Map.of(), Set.of("storage.read")); service.setEnabled("first", "fixture", true);
            Map<String, String> hostStorage = new java.util.concurrent.ConcurrentHashMap<>();
            service.hostServices().register("storage", Set.of("read"), "storage.read", true, (request, cancelled, events) -> {
                hostStorage.put(request.workspaceId(), "late-write"); return "{}";
            });
            service.observeSecurityEvents(event -> {
                if (event.reason() == SecurityReason.UNINSTALL_DELETE_DATA) {
                    assertEquals("late-write", hostStorage.get(event.workspaceId())); hostStorage.remove(event.workspaceId());
                }
            });
            try (RunLease lease = service.acquireRun("first", "fixture"); HostProbe probe = probe(service, lease)) {
                BackgroundLease receipt = probe.open(30_000); lease.close();
                service.uninstall("first", "fixture", true);
                assertEquals(State.UNINSTALLING, service.details("first", "fixture").summary().state());
                probe.invoke(receipt.getRunId()); assertEquals("late-write", hostStorage.get("first"));
                probe.client.closeBackground(BackgroundLeaseCloseRequest.newBuilder().setIdentity(probe.identity).setLeaseId(receipt.getLeaseId()).build());
                await(() -> !Files.exists(root.resolve("artifacts/fixture")));
                assertTrue(hostStorage.isEmpty()); assertFalse(Files.exists(root.resolve("workspaces/first/plugins/fixture")));
            }
        }
    }

    @Test void reportsAsynchronousUninstallCleanupFailureAndRetriesWithoutLosingItsMarker() throws Exception {
        Path marker = root.resolve("artifacts/fixture/uninstall.json");
        Path privateData = root.resolve("workspaces/first/plugins/fixture/data/private.txt");
        AtomicBoolean cleanupUnavailable = new AtomicBoolean(true);
        var events = new java.util.concurrent.ConcurrentLinkedQueue<PluginStatusEvent>();
        Consumer<PluginSecurityEvent> cleanup = event -> {
            if (event.reason() == SecurityReason.UNINSTALL_DELETE_DATA && cleanupUnavailable.get())
                throw new IllegalStateException("模拟最终宿主存储清理失败");
        };
        try (var tasks = new ManagedTaskExecutor(); var service = service(tasks)) {
            install(service, "1.0"); service.activateWorkspace("first");
            configure(service, "first", "retained-until-cleanup", Map.of(), Set.of("storage.read")); service.setEnabled("first", "fixture", true);
            String healthyManifest = PluginFixture.manifest("1.0").replace("\"id\":\"fixture\"", "\"id\":\"healthy\"");
            Path healthy = PluginFixture.zip(root.resolve("healthy.zip"), PluginFixture.signed(Map.of("plugin.json", healthyManifest.getBytes(java.nio.charset.StandardCharsets.UTF_8), "plugin.jar", jar)));
            service.install("first", service.inspectPackage(healthy).previewId());
            service.updateConfiguration("first", "healthy", new ConfigurationUpdate(service.configuration("first", "healthy").revision(), "{\"label\":\"healthy\"}", Map.of(), Set.of("storage.read")));
            service.setEnabled("first", "healthy", true); service.setStartupPolicy("first", "healthy", StartupPolicy.AUTO_START);
            Files.writeString(privateData, "等待宿主私有存储清理");
            service.observeStatus(events::add);
            service.observeSecurityEvents(cleanup);
            try (RunLease lease = service.acquireRun("first", "fixture")) {
                service.uninstall("first", "fixture", true);
                assertEquals(State.UNINSTALLING, service.details("first", "fixture").summary().state());
                lease.close();
                await(() -> service.details("first", "fixture").summary().state() == State.FAILED);
                assertTrue(service.details("first", "fixture").summary().lastError().contains("重试卸载"));
                assertTrue(events.stream().anyMatch(event -> event.state() == State.FAILED && event.pluginId().equals("fixture") && event.message().contains("重试卸载")));
                assertTrue(Files.exists(marker)); assertTrue(Files.exists(privateData));
                assertEquals("UNINSTALL_IN_PROGRESS", assertThrows(PluginException.class, () -> service.acquireRun("first", "fixture")).code());
            }
        }
        try (var tasks = new ManagedTaskExecutor(); var service = service(tasks)) {
            events.clear(); service.observeStatus(events::add); service.observeSecurityEvents(cleanup);
            assertDoesNotThrow(() -> service.activateWorkspace("first"));
            assertEquals(State.READY, service.details("first", "healthy").summary().state());
            assertTrue(service.list("first").stream().anyMatch(plugin -> plugin.id().equals("fixture") && plugin.state() == State.FAILED));
            assertTrue(service.details("first", "fixture").summary().lastError().contains("重试卸载"));
            assertTrue(events.stream().anyMatch(event -> event.pluginId().equals("fixture") && event.state() == State.FAILED));
            assertTrue(Files.exists(marker)); assertTrue(Files.exists(privateData));
            assertEquals("UNINSTALL_IN_PROGRESS", assertThrows(PluginException.class, () -> service.acquireRun("first", "fixture")).code());
            cleanupUnavailable.set(false);
            service.uninstall("first", "fixture", true);
            assertFalse(Files.exists(marker)); assertFalse(Files.exists(privateData));
            assertEquals(List.of("healthy"), service.list("first").stream().map(PluginSummary::id).toList());
        }
    }

    @Test void corruptUninstallMarkerRemainsVisibleWithoutBlockingWorkspaceActivation() throws Exception {
        Path marker = root.resolve("artifacts/fixture/uninstall.json");
        try (var tasks = new ManagedTaskExecutor(); var service = service(tasks)) {
            install(service, "1.0");
            Files.writeString(marker, "损坏的卸载标记");
        }
        try (var tasks = new ManagedTaskExecutor(); var service = service(tasks)) {
            assertDoesNotThrow(() -> service.activateWorkspace("first"));
            assertEquals(State.FAILED, service.details("first", "fixture").summary().state());
            assertTrue(service.list("first").stream().anyMatch(plugin -> plugin.id().equals("fixture")));
            assertEquals(0, service.diagnostics("first", "fixture").pid());
            assertTrue(Files.exists(marker));
            assertEquals("UNINSTALL_IN_PROGRESS", assertThrows(PluginException.class, () -> service.acquireRun("first", "fixture")).code());
            service.uninstall("first", "fixture", true);
            assertFalse(Files.exists(marker)); assertTrue(service.list("first").isEmpty());
        }
    }

    @Test void reportsUnknownOutcomeWhenProcessCrashesAfterOneSideEffectWithoutReplay() throws Exception {
        try (var tasks = new ManagedTaskExecutor(); var service = service(tasks)) {
            install(service, "1.0"); service.activateWorkspace("first");
            configure(service, "first", "crash", Map.of(), Set.of("storage.read")); service.setEnabled("first", "fixture", true);
            try (RunLease lease = service.acquireRun("first", "fixture")) {
                var invocation = service.invoke(lease, "TOOL", "echo", "crash", "{}", Duration.ofSeconds(10), event -> { });
                ExecutionException failure = assertThrows(ExecutionException.class, () -> invocation.completion().get(15, TimeUnit.SECONDS));
                assertInstanceOf(PluginException.class, failure.getCause());
                assertEquals("UNKNOWN_OUTCOME", ((PluginException) failure.getCause()).code());
                assertEquals("once", Files.readString(root.resolve("workspaces/first/plugins/fixture/data/side-effect.txt")));
            }
        }
    }

    @Test void upgradeChangesOnlySelectedWorkspaceAndVersionSelectionUsesOptimisticRevision() throws Exception {
        try (var tasks = new ManagedTaskExecutor(); var service = service(tasks)) {
            install(service, "1.0"); service.activateWorkspace("first"); service.activateWorkspace("second");
            for (String workspace : List.of("first", "second")) { configure(service, workspace, workspace, Map.of(), Set.of("storage.read")); service.setEnabled(workspace, "fixture", true); }
            String original = service.details("first", "fixture").summary().artifactHash();
            try (RunLease second = service.acquireRun("second", "fixture"); RunLease first = service.acquireRun("first", "fixture")) {
                install(service, "2.0");
                assertEquals("2.0", service.details("first", "fixture").summary().version()); assertEquals("1.0", service.details("second", "fixture").summary().version());
                assertEquals(original, service.details("second", "fixture").summary().artifactHash());
                assertTrue(call(service, second, "echo").contains("second")); assertTrue(call(service, first, "echo").contains("first"));
                assertEquals(2, service.versions("first", "fixture").size());
                long revision = service.configuration("first", "fixture").revision();
                assertEquals("REVISION_CONFLICT", assertThrows(PluginException.class, () -> service.selectVersion("first", "fixture", original, revision - 1)).code());
                service.selectVersion("first", "fixture", original, revision); assertEquals("1.0", service.details("first", "fixture").summary().version());
            }
        }
    }

    @Test void incompatibleUpgradeKeepsWorkspaceBindingAndRunningInstance() throws Exception {
        try (var tasks = new ManagedTaskExecutor(); var service = service(tasks)) {
            install(service, "1.0"); service.activateWorkspace("first");
            configure(service, "first", "old", Map.of(), Set.of("storage.read")); service.setEnabled("first", "fixture", true);
            try (RunLease old = service.acquireRun("first", "fixture")) {
                String manifest = PluginFixture.manifest("2.0").replace("\"label\":{\"type\":\"string\",\"default\":\"initial\"}", "\"label\":{\"type\":\"integer\"}");
                Path zip = PluginFixture.zip(root.resolve("incompatible.zip"), PluginFixture.signed(Map.of("plugin.json", manifest.getBytes(java.nio.charset.StandardCharsets.UTF_8), "plugin.jar", jar)));
                assertEquals("INVALID_CONFIGURATION", assertThrows(PluginException.class, () -> service.install("first", service.inspectPackage(zip).previewId())).code());
                assertEquals("1.0", service.details("first", "fixture").summary().version()); assertEquals(old.configurationRevision(), service.configuration("first", "fixture").revision());
                assertTrue(call(service, old, "echo").contains("old")); assertEquals(old.instanceId(), service.diagnostics("first", "fixture").instanceId());
                String candidate = service.versions("first", "fixture").stream().filter(value -> value.version().equals("2.0")).findFirst().orElseThrow().artifactHash();
                service.selectVersion("first", "fixture", candidate, new ConfigurationUpdate(old.configurationRevision(), "{\"label\":7}", Map.of(), Set.of("storage.read")));
                assertEquals("2.0", service.details("first", "fixture").summary().version());
            }
        }
    }

    private DefaultPluginManagementService service(ManagedTaskExecutor tasks) {
        return new DefaultPluginManagementService(root.resolve("artifacts"), root.resolve("workspaces"), root.resolve("staging"), List.of(runner), tasks,
                PluginFixture.JSON, PluginFixture.CIPHER, HttpClient.newHttpClient());
    }
    private void install(DefaultPluginManagementService service, String version) throws Exception {
        var preview = service.inspectPackage(PluginFixture.archive(root, version, jar)); service.install("first", preview.previewId());
    }
    private void installExclusive(DefaultPluginManagementService service) throws Exception {
        String manifest = PluginFixture.manifest("1.0").replace("\"capabilities\":[", "\"resources\":{\"exclusive\":true},\"capabilities\":[");
        Path zip = PluginFixture.zip(root.resolve("exclusive.zip"), PluginFixture.signed(Map.of("plugin.json", manifest.getBytes(java.nio.charset.StandardCharsets.UTF_8), "plugin.jar", jar)));
        service.install("first", service.inspectPackage(zip).previewId());
    }

    private void installWithModule(DefaultPluginManagementService service, String module) throws Exception {
        var manifest = (com.fasterxml.jackson.databind.node.ObjectNode) PluginFixture.JSON.readTree(PluginFixture.manifest("1.0"));
        manifest.putObject("runtime").putArray("javaModules").add(module);
        Path zip = PluginFixture.zip(root.resolve("module.zip"), PluginFixture.signed(Map.of("plugin.json", manifest.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8), "plugin.jar", jar)));
        service.install("first", service.inspectPackage(zip).previewId());
    }

    private void cancelsPendingExclusiveChange(Consumer<DefaultPluginManagementService> cancel,
                                              CheckedServiceAssertion verify) throws Exception {
        try (var tasks = new ManagedTaskExecutor(); var service = service(tasks)) {
            installExclusive(service); service.activateWorkspace("first");
            configure(service, "first", "old", Map.of(), Set.of("storage.read")); service.setEnabled("first", "fixture", true);
            long pid = service.diagnostics("first", "fixture").pid();
            AtomicInteger candidateStarts = new AtomicInteger();
            try (var subscription = service.observeStatus(event -> { if (event.state() == State.STARTING) candidateStarts.incrementAndGet(); });
                 RunLease old = service.acquireRun("first", "fixture")) {
                CompletableFuture<Void> change = CompletableFuture.runAsync(() -> configure(service, "first", "candidate", Map.of(), Set.of("storage.read")));
                await(() -> service.details("first", "fixture").summary().state() == State.DRAINING);
                assertFalse(change.isDone());
                // 持有真实旧租约时取消接入，随后释放资源，覆盖等待结束与取消操作的竞争。
                cancel.accept(service); old.close();
                var failure = assertThrows(ExecutionException.class, () -> change.get(10, TimeUnit.SECONDS));
                assertInstanceOf(PluginException.class, failure.getCause());
                assertEquals("CANCELLED", ((PluginException) failure.getCause()).code());
                await(() -> ProcessHandle.of(pid).map(process -> !process.isAlive()).orElse(true));
                assertEquals(0, candidateStarts.get());
                String current = Files.readString(root.resolve("workspaces/first/plugins/fixture/current.json"));
                assertTrue(current.contains("old")); assertFalse(current.contains("candidate"));
                verify.accept(service);
            }
        }
    }

    @FunctionalInterface private interface CheckedServiceAssertion {
        void accept(DefaultPluginManagementService service) throws Exception;
    }
    private void configure(DefaultPluginManagementService service, String workspace, String label, Map<String, SecretChange> secrets, Set<String> grants) {
        long revision = service.configuration(workspace, "fixture").revision();
        service.updateConfiguration(workspace, "fixture", new ConfigurationUpdate(revision, "{\"label\":\"" + label + "\"}", secrets, grants));
    }

    private static void await(BooleanSupplier condition) {
        assertTimeoutPreemptively(Duration.ofSeconds(12), () -> { while (!condition.getAsBoolean()) Thread.sleep(10); });
    }
    private String call(DefaultPluginManagementService service, RunLease lease, String operation) throws Exception {
        AtomicReference<String> data = new AtomicReference<>();
        service.invoke(lease, "TOOL", "echo", operation, "{}", Duration.ofSeconds(10), event -> { if (event.type().equals("DATA")) data.set(event.payloadJson()); })
                .completion().get(15, TimeUnit.SECONDS);
        return data.get();
    }

    private HostProbe probe(DefaultPluginManagementService service, RunLease lease) throws Exception {
        var credentials = PluginFixture.JSON.readTree(call(service, lease, "credentials"));
        ManagedChannel channel = NettyChannelBuilder.forAddress("127.0.0.1", credentials.path("port").asInt()).disableRetry().usePlaintext().build();
        Metadata headers = new Metadata(); headers.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), "Bearer " + credentials.path("token").asText());
        var client = HostServicesGrpc.newBlockingStub(channel).withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));
        PluginIdentity identity = PluginIdentity.newBuilder().setPluginId(lease.pluginId()).setWorkspaceId(lease.workspaceId()).setArtifactHash(lease.artifactHash()).setConfigurationRevision(lease.configurationRevision()).setInstanceId(lease.instanceId()).build();
        return new HostProbe(channel, client, identity);
    }

    private record HostProbe(ManagedChannel channel, HostServicesGrpc.HostServicesBlockingStub client, PluginIdentity identity) implements AutoCloseable {
        CallContext context(String runId) { return CallContext.newBuilder().setIdentity(identity).setRunId(runId).setRequestId(UUID.randomUUID().toString()).setDeadlineEpochMillis(System.currentTimeMillis() + 3000).build(); }
        BackgroundLease open(long ttl) { return client.withDeadlineAfter(3, TimeUnit.SECONDS).openBackground(BackgroundLeaseRequest.newBuilder().setContext(context("")).setTtlMillis(ttl).build()); }
        void invoke(String runId) {
            var events = client.withDeadlineAfter(3, TimeUnit.SECONDS).invoke(CallRequest.newBuilder().setContext(context(runId)).setContributionId("storage").setOperation("read").setPayloadJson("{}").build());
            while (events.hasNext()) events.next();
        }
        public void close() { channel.shutdownNow(); }
    }
}
