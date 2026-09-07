package com.javaclaw.server.extension;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.ApprovalRequirement;
import com.javaclaw.api.AttachmentMetadata;
import com.javaclaw.api.AttachmentRef;
import com.javaclaw.api.AttachmentScope;
import com.javaclaw.api.CancellationSource;
import com.javaclaw.api.FilePermission;
import com.javaclaw.api.NetworkPermission;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.ProcessPermission;
import com.javaclaw.api.ResourceLimits;
import com.javaclaw.api.ToolPermission;
import com.javaclaw.api.ToolRisk;
import com.javaclaw.api.Workspace;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.builtin.contracts.BuiltinExtensionIds;
import com.javaclaw.builtin.contracts.KnowledgeContracts;
import com.javaclaw.builtin.contracts.KnowledgeWorkerProtocol;
import com.javaclaw.extension.spi.ExtensionId;
import com.javaclaw.extension.spi.IsolatedServiceInvocation;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.LengthPrefixedFraming;
import com.javaclaw.server.persistence.AttachmentService;
import com.javaclaw.server.persistence.CommandIdentity;
import com.javaclaw.server.persistence.CoreCommandService;
import com.javaclaw.server.persistence.H2Database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnowledgeExtractionServiceBoundaryTest {
    private final CanonicalJson json = new CanonicalJson();

    @TempDir
    Path temporaryDirectory;

    @Test
    void 可用服务校验附件元数据和Worker摘要并在关闭时释放Worker() throws Exception {
        Fixture fixture = fixture();
        AtomicReference<String> resultDigest =
                new AtomicReference<>(fixture.metadata().digest());
        KnowledgeWorkerClient worker = worker(resultDigest);
        KnowledgeExtractionService service = KnowledgeExtractionService.available(fixture.attachments(), worker, json);

        assertTrue(service.isAvailable());
        KnowledgeContracts.ExtractionResult result = json.decode(
                service.extract(invocation(fixture.workspaceId(), reference(fixture.metadata()))),
                KnowledgeContracts.ExtractionResult.class);
        assertEquals("提取后的正文", result.text());

        AttachmentRef wrongMedia = new AttachmentRef(
                fixture.metadata().digest(),
                "application/json",
                "knowledge.txt",
                fixture.metadata().sizeBytes());
        assertThrows(
                IllegalArgumentException.class, () -> service.extract(invocation(fixture.workspaceId(), wrongMedia)));
        AttachmentRef wrongSize = new AttachmentRef(
                fixture.metadata().digest(),
                fixture.metadata().mediaType(),
                "knowledge.txt",
                fixture.metadata().sizeBytes() + 1);
        assertThrows(
                IllegalArgumentException.class, () -> service.extract(invocation(fixture.workspaceId(), wrongSize)));

        resultDigest.set("0".repeat(64));
        assertThrows(
                IllegalStateException.class,
                () -> service.extract(invocation(fixture.workspaceId(), reference(fixture.metadata()))));

        service.close();
        assertThrows(
                IllegalStateException.class,
                () -> worker.extract(
                        fixture.attachments()
                                .read(
                                        AttachmentScope.workspace(fixture.workspaceId()),
                                        fixture.metadata().digest()),
                        100,
                        new CancellationSource()));
    }

    @Test
    void 不可用服务明确失败且关闭保持幂等() {
        KnowledgeExtractionService service = KnowledgeExtractionService.unavailable(json);

        assertFalse(service.isAvailable());
        assertThrows(
                IllegalStateException.class,
                () -> service.extract(invocation(
                        WorkspaceId.random(), new AttachmentRef("0".repeat(64), "text/plain", "missing.txt", 1))));
        service.close();
        service.close();
    }

    private Fixture fixture() {
        H2Database database = new H2Database(temporaryDirectory.resolve("data-v6"));
        database.initialize();
        Clock clock = Clock.systemUTC();
        Workspace workspace = new CoreCommandService(database, json, clock)
                .createWorkspace(
                        new CommandIdentity("workspace/create", "knowledge-service-workspace", 0, "0".repeat(64)),
                        "Knowledge",
                        temporaryDirectory.resolve("workspace"));
        AttachmentService attachments = new AttachmentService(database, json, clock);
        byte[] content = "待解析正文".getBytes(StandardCharsets.UTF_8);
        AttachmentMetadata metadata = attachments.store(
                AttachmentScope.workspace(workspace.id()),
                new CommandIdentity("attachment/create", "knowledge-service-attachment", 0, "1".repeat(64)),
                "text/plain",
                content);
        return new Fixture(workspace.id(), attachments, metadata);
    }

    private IsolatedServiceInvocation invocation(WorkspaceId workspaceId, AttachmentRef reference) {
        KnowledgeContracts.ExtractionRequest request = new KnowledgeContracts.ExtractionRequest(reference, 100);
        return new IsolatedServiceInvocation(
                new ExtensionId(BuiltinExtensionIds.KNOWLEDGE),
                workspaceId,
                permission(),
                KnowledgeContracts.EXTRACTION_SERVICE,
                json.encode(request),
                new CancellationSource());
    }

    private KnowledgeWorkerClient worker(AtomicReference<String> digest) {
        return new KnowledgeWorkerClient(
                () -> new ResponseProcess(
                        frame(new KnowledgeContracts.ExtractionResult(digest.get(), "plain-v1", "提取后的正文"))),
                Duration.ofSeconds(2));
    }

    private byte[] frame(KnowledgeContracts.ExtractionResult result) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        LengthPrefixedFraming.write(
                output,
                json.encode(KnowledgeWorkerProtocol.Response.success(result))
                        .json()
                        .getBytes(StandardCharsets.UTF_8),
                KnowledgeWorkerProtocol.MAXIMUM_JSON_BYTES);
        return output.toByteArray();
    }

    private static AttachmentRef reference(AttachmentMetadata metadata) {
        return new AttachmentRef(metadata.digest(), metadata.mediaType(), "knowledge.txt", metadata.sizeBytes());
    }

    private static PermissionProfile permission() {
        return new PermissionProfile(
                "knowledge-test",
                1,
                new FilePermission(List.of(), List.of(), false, false),
                new NetworkPermission(Set.of(), Set.of(), false),
                new ProcessPermission(Set.of(), false, Duration.ofSeconds(30)),
                new ToolPermission(Set.of(), ToolRisk.PROCESS, ApprovalRequirement.EVERY_CALL),
                new ResourceLimits(256L * 1024 * 1024, 64L * 1024, 2, 64));
    }

    private record Fixture(WorkspaceId workspaceId, AttachmentService attachments, AttachmentMetadata metadata) {}

    private static final class ResponseProcess extends Process {
        private final ByteArrayOutputStream request = new ByteArrayOutputStream();
        private final InputStream response;
        private final AtomicBoolean destroyed = new AtomicBoolean();

        private ResponseProcess(byte[] response) {
            this.response = new ByteArrayInputStream(response);
        }

        @Override
        public OutputStream getOutputStream() {
            return request;
        }

        @Override
        public InputStream getInputStream() {
            return response;
        }

        @Override
        public InputStream getErrorStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public int waitFor() {
            return 0;
        }

        @Override
        public int exitValue() {
            return 0;
        }

        @Override
        public void destroy() {
            destroyed.set(true);
        }

        @Override
        public Process destroyForcibly() {
            destroyed.set(true);
            return this;
        }

        @Override
        public boolean isAlive() {
            return !destroyed.get();
        }
    }
}
