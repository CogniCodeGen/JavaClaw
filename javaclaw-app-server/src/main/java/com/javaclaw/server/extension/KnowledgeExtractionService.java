package com.javaclaw.server.extension;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.AttachmentContent;
import com.javaclaw.api.AttachmentMetadata;
import com.javaclaw.api.AttachmentRef;
import com.javaclaw.api.AttachmentScope;
import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.builtin.contracts.KnowledgeContracts;
import com.javaclaw.extension.spi.IsolatedServiceInvocation;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.server.persistence.AttachmentService;

/** 解析 Knowledge Attachment 的 App Server 服务；Worker 不接收路径且不可直接访问 Blob 根。 */
final class KnowledgeExtractionService implements AutoCloseable {
    private final AttachmentService attachments;
    private final Optional<KnowledgeWorkerClient> worker;
    private final CanonicalJson json;

    private KnowledgeExtractionService(
            AttachmentService attachments, Optional<KnowledgeWorkerClient> worker, CanonicalJson json) {
        this.attachments = attachments;
        this.worker = Objects.requireNonNull(worker, "worker");
        this.json = Objects.requireNonNull(json, "json");
    }

    static KnowledgeExtractionService available(
            AttachmentService attachments, KnowledgeWorkerClient worker, CanonicalJson json) {
        return new KnowledgeExtractionService(
                Objects.requireNonNull(attachments, "attachments"), Optional.of(worker), json);
    }

    static KnowledgeExtractionService unavailable(CanonicalJson json) {
        return new KnowledgeExtractionService(null, Optional.empty(), json);
    }

    boolean isAvailable() {
        return worker.isPresent();
    }

    CanonicalPayload extract(IsolatedServiceInvocation invocation) {
        KnowledgeWorkerClient client =
                worker.orElseThrow(() -> new IllegalStateException("Knowledge Worker packaged runtime is unavailable"));
        KnowledgeContracts.ExtractionRequest request =
                json.decode(invocation.request(), KnowledgeContracts.ExtractionRequest.class);
        AttachmentContent content = attachments.read(
                AttachmentScope.workspace(invocation.workspaceId()),
                request.attachment().digest());
        requireReference(request.attachment(), content.metadata());
        KnowledgeContracts.ExtractionResult result =
                client.extract(content, request.maxCharacters(), invocation.cancellation());
        requireDigest(content.metadata().digest(), result.digest());
        return json.encode(result);
    }

    @Override
    public void close() {
        worker.ifPresent(KnowledgeWorkerClient::close);
    }

    private static void requireReference(AttachmentRef reference, AttachmentMetadata metadata) {
        if (!reference.mediaType().equals(metadata.mediaType()) || reference.sizeBytes() != metadata.sizeBytes()) {
            throw new IllegalArgumentException("Knowledge Attachment reference differs from Core metadata");
        }
    }

    private static void requireDigest(String expected, String actual) {
        if (!MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.US_ASCII), actual.getBytes(StandardCharsets.US_ASCII))) {
            throw new IllegalStateException("Knowledge Worker digest differs from Core Attachment");
        }
    }
}
