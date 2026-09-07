package com.javaclaw.server.extension;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.AttachmentMetadata;
import com.javaclaw.api.AttachmentRef;
import com.javaclaw.api.AttachmentScope;
import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.ExecutionState;
import com.javaclaw.api.Workspace;
import com.javaclaw.builtin.contracts.BuiltinExtensionIds;
import com.javaclaw.builtin.contracts.KnowledgeContracts;
import com.javaclaw.extension.spi.ExtensionExecutionReceipt;
import com.javaclaw.extension.spi.IsolatedServiceInvocation;
import com.javaclaw.extension.spi.IsolatedServicePort;
import com.javaclaw.protocol.CapabilityAdvertisement;
import com.javaclaw.protocol.ClientInfo;
import com.javaclaw.protocol.CoreRpcContracts;
import com.javaclaw.protocol.ExtensionRpcContracts;
import com.javaclaw.protocol.InitializeParams;
import com.javaclaw.protocol.InputJobRpcContracts;
import com.javaclaw.protocol.JsonRpcRequest;
import com.javaclaw.protocol.JsonRpcResponse;
import com.javaclaw.protocol.ProtocolVersion;
import com.javaclaw.protocol.RpcId;
import com.javaclaw.protocol.WriteCommand;
import com.javaclaw.runtime.ModelCapabilities;
import com.javaclaw.runtime.ModelEventSink;
import com.javaclaw.runtime.ModelGateway;
import com.javaclaw.runtime.ModelInvocation;
import com.javaclaw.runtime.ModelInvocationResult;
import com.javaclaw.server.AppServerBootstrap;
import com.javaclaw.server.rpc.AppServerSession;
import com.javaclaw.server.testkit.AttachmentRpcTestClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnowledgeExtensionIntegrationTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void attachmentImportPersistsJobAndPublishesGenerationThroughRpc() throws Exception {
        ExtractionService service = new ExtractionService();
        try (AppServerBootstrap.Components components = server(service)) {
            AppServerSession session = components.newSession();
            initialize(session, components);
            Workspace workspace = createWorkspace(session, components, "Primary");
            AttachmentRef attachment = createAttachment(session, components, workspace, "first", "architecture guide");
            KnowledgeContracts.ImportRequest request = importRequest(attachment);

            KnowledgeContracts.ImportAccepted accepted =
                    importSource(session, components, workspace, request, "knowledge-import", 0);
            ExtensionExecutionReceipt completed =
                    awaitJob(session, components, accepted.jobId(), ExecutionState.COMPLETED);
            KnowledgeContracts.ImportAccepted replay =
                    importSource(session, components, workspace, request, "knowledge-import", 0);
            KnowledgeContracts.Source source = readSource(session, components, workspace);
            KnowledgeContracts.Generation generation =
                    readGeneration(session, components, workspace, source.activeGenerationId());

            assertEquals(accepted, replay);
            assertEquals(1, service.invocations.get());
            assertEquals(ExecutionState.COMPLETED, completed.state());
            assertEquals(attachment.digest(), generation.attachmentDigest());
            assertEquals(KnowledgeContracts.RetrievalMode.KEYWORD, generation.retrievalMode());
            assertEquals(
                    List.of("guide"),
                    search(session, components, workspace, "architecture").matches().stream()
                            .map(match -> match.source().id())
                            .toList());
        }
    }

    @Test
    void failedReplacementRetainsPreviouslyActiveGeneration() throws Exception {
        ExtractionService service = new ExtractionService();
        try (AppServerBootstrap.Components components = server(service)) {
            AppServerSession session = components.newSession();
            initialize(session, components);
            Workspace workspace = createWorkspace(session, components, "Atomic");
            AttachmentRef first = createAttachment(session, components, workspace, "first", "first architecture");
            KnowledgeContracts.ImportAccepted initial =
                    importSource(session, components, workspace, importRequest(first), "initial", 0);
            awaitJob(session, components, initial.jobId(), ExecutionState.COMPLETED);
            KnowledgeContracts.Source original = readSource(session, components, workspace);

            service.tamper = true;
            AttachmentRef replacement = createAttachment(session, components, workspace, "replacement", "replacement");
            KnowledgeContracts.ImportAccepted failed = importSource(
                    session, components, workspace, importRequest(replacement), "replacement", original.revision());
            ExtensionExecutionReceipt failedJob = awaitJob(session, components, failed.jobId(), ExecutionState.FAILED);

            assertEquals("JOB_UNIT_FAILED", failedJob.errorCode().orElseThrow());
            assertEquals(original, readSource(session, components, workspace));
        }
    }

    private AppServerBootstrap.Components server(IsolatedServicePort service) {
        return AppServerBootstrap.create(
                temporaryDirectory.resolve("data-v6"), Clock.systemUTC(), new UnusedModel(), enabled -> {}, service);
    }

    private KnowledgeContracts.ImportAccepted importSource(
            AppServerSession session,
            AppServerBootstrap.Components components,
            Workspace workspace,
            KnowledgeContracts.ImportRequest input,
            String key,
            long expectedRevision) {
        ExtensionRpcContracts.CallPayload call = new ExtensionRpcContracts.CallPayload(
                BuiltinExtensionIds.KNOWLEDGE,
                workspace.id(),
                Optional.empty(),
                Optional.empty(),
                "source/import",
                components.json().encode(input));
        ExtensionRpcContracts.CallResult result = decode(
                session.handle(request(
                        components,
                        key,
                        "extension/command",
                        new WriteCommand(
                                key, expectedRevision, components.json().encode(call)))),
                components,
                ExtensionRpcContracts.CallResult.class);
        return components.json().decode(result.payload(), KnowledgeContracts.ImportAccepted.class);
    }

    private KnowledgeContracts.Source readSource(
            AppServerSession session, AppServerBootstrap.Components components, Workspace workspace) {
        return query(
                session,
                components,
                workspace,
                "source/read",
                new KnowledgeContracts.Key("guide"),
                KnowledgeContracts.Source.class);
    }

    private KnowledgeContracts.Generation readGeneration(
            AppServerSession session, AppServerBootstrap.Components components, Workspace workspace, String id) {
        return query(
                session,
                components,
                workspace,
                "generation/read",
                new KnowledgeContracts.Key(id),
                KnowledgeContracts.Generation.class);
    }

    private KnowledgeContracts.SearchResult search(
            AppServerSession session, AppServerBootstrap.Components components, Workspace workspace, String query) {
        return query(
                session,
                components,
                workspace,
                "search",
                new KnowledgeContracts.SearchRequest(query, Set.of(), 20),
                KnowledgeContracts.SearchResult.class);
    }

    private <T> T query(
            AppServerSession session,
            AppServerBootstrap.Components components,
            Workspace workspace,
            String operation,
            Object input,
            Class<T> type) {
        ExtensionRpcContracts.CallPayload call = new ExtensionRpcContracts.CallPayload(
                BuiltinExtensionIds.KNOWLEDGE,
                workspace.id(),
                Optional.empty(),
                Optional.empty(),
                operation,
                components.json().encode(input));
        ExtensionRpcContracts.CallResult result = decode(
                session.handle(request(components, "query-" + operation, "extension/query", call)),
                components,
                ExtensionRpcContracts.CallResult.class);
        return components.json().decode(result.payload(), type);
    }

    private ExtensionExecutionReceipt awaitJob(
            AppServerSession session, AppServerBootstrap.Components components, String jobId, ExecutionState expected)
            throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            InputJobRpcContracts.JobReadResult result = decode(
                    session.handle(request(
                            components,
                            "job-read-" + jobId,
                            "extension/job/read",
                            new InputJobRpcContracts.JobReadPayload(jobId))),
                    components,
                    InputJobRpcContracts.JobReadResult.class);
            if (result.job().state() == expected) {
                return result.job();
            }
            Thread.sleep(10);
        }
        throw new AssertionError("Knowledge Job did not reach " + expected);
    }

    private AttachmentRef createAttachment(
            AppServerSession session,
            AppServerBootstrap.Components components,
            Workspace workspace,
            String key,
            String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        AttachmentMetadata metadata = AttachmentRpcTestClient.upload(
                session,
                components.json(),
                "attachment-" + key,
                AttachmentScope.workspace(workspace.id()),
                "text/plain",
                bytes);
        return new AttachmentRef(metadata.digest(), metadata.mediaType(), key + ".txt", metadata.sizeBytes());
    }

    private Workspace createWorkspace(AppServerSession session, AppServerBootstrap.Components components, String name) {
        CoreRpcContracts.WorkspaceCreatePayload payload =
                new CoreRpcContracts.WorkspaceCreatePayload(name, temporaryDirectory.resolve(name));
        return decode(
                session.handle(request(
                        components,
                        "workspace-" + name,
                        "workspace/create",
                        new WriteCommand(
                                "workspace-" + name, 0, components.json().encode(payload)))),
                components,
                Workspace.class);
    }

    private void initialize(AppServerSession session, AppServerBootstrap.Components components) {
        InitializeParams params = new InitializeParams(
                ProtocolVersion.CURRENT,
                new ClientInfo("knowledge-extension-test", "5.0"),
                new CapabilityAdvertisement(Set.of("core.item-envelope"), Set.of()));
        assertTrue(session.handle(request(components, "init", "initialize/session", params))
                .result()
                .isPresent());
    }

    private static KnowledgeContracts.ImportRequest importRequest(AttachmentRef attachment) {
        return new KnowledgeContracts.ImportRequest(
                "guide",
                "JavaClaw Guide",
                attachment,
                10_000,
                500,
                50,
                KnowledgeContracts.RetrievalPreference.EMBEDDING_PREFERRED);
    }

    private static JsonRpcRequest request(
            AppServerBootstrap.Components components, String id, String method, Object params) {
        return new JsonRpcRequest(new RpcId(id), method, components.json().encode(params));
    }

    private static <T> T decode(JsonRpcResponse response, AppServerBootstrap.Components components, Class<T> type) {
        CanonicalPayload payload = response.result()
                .orElseThrow(() -> new AssertionError(response.error().orElseThrow()));
        return components.json().decode(payload, type);
    }

    private static final class ExtractionService implements IsolatedServicePort {
        private final AtomicInteger invocations = new AtomicInteger();
        private final com.javaclaw.protocol.CanonicalJson json = new com.javaclaw.protocol.CanonicalJson();
        private volatile boolean tamper;

        @Override
        public CanonicalPayload invoke(IsolatedServiceInvocation invocation) {
            KnowledgeContracts.ExtractionRequest request =
                    json.decode(invocation.request(), KnowledgeContracts.ExtractionRequest.class);
            invocations.incrementAndGet();
            String digest = tamper ? "0".repeat(64) : request.attachment().digest();
            return json.encode(new KnowledgeContracts.ExtractionResult(
                    digest, "fake-worker-v1", "JavaClaw architecture and extension boundaries"));
        }
    }

    private static final class UnusedModel implements ModelGateway {
        @Override
        public ModelCapabilities capabilities(String modelId) {
            throw new AssertionError("model must not be used");
        }

        @Override
        public ModelInvocationResult invoke(
                com.javaclaw.api.TurnId turnId,
                ModelInvocation invocation,
                ModelEventSink events,
                com.javaclaw.api.CancellationToken cancellation) {
            throw new AssertionError("model must not be used");
        }
    }
}
