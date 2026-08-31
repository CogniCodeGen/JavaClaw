package com.javaclaw.server.transport;

import java.io.IOException;
import java.util.Base64;
import java.util.Objects;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.javaclaw.agent.runtime.persistence.AttachmentRepository;
import com.javaclaw.protocol.RpcMethods;
import com.javaclaw.protocol.WireAttachmentUpload;

/** JSON-RPC adapter for resumable content-addressed attachments. */
final class AttachmentRpcHandler implements RpcHandler {
    private static final Set<String> METHODS = Set.of(
            RpcMethods.ATTACHMENT_UPLOAD_START,
            RpcMethods.ATTACHMENT_UPLOAD_CHUNK,
            RpcMethods.ATTACHMENT_UPLOAD_COMPLETE,
            RpcMethods.ATTACHMENT_READ,
            RpcMethods.ATTACHMENT_RELEASE);

    private final AttachmentRepository attachments;
    private final ObjectMapper json;
    private final ProtocolMapper wire;

    AttachmentRpcHandler(AttachmentRepository attachments, ObjectMapper json, ProtocolMapper wire) {
        this.attachments = attachments;
        this.json = Objects.requireNonNull(json, "json");
        this.wire = Objects.requireNonNull(wire, "wire");
    }

    @Override
    public Set<String> methods() {
        return METHODS;
    }

    @Override
    public JsonNode handle(String method, JsonNode params) {
        AttachmentRepository repository = requireRepository();
        try {
            return switch (method) {
                case RpcMethods.ATTACHMENT_UPLOAD_START -> {
                    var upload = repository.startUpload(
                            RequestParameters.optionalText(params, "sha256", null),
                            RequestParameters.optionalText(params, "mediaType", "application/octet-stream"),
                            RequestParameters.optionalText(params, "displayName", "attachment"),
                            RequestParameters.optionalLong(params, "sizeBytes", -1),
                            RequestParameters.optionalText(params, "idempotencyKey", null));
                    yield json.valueToTree(upload(upload));
                }
                case RpcMethods.ATTACHMENT_UPLOAD_CHUNK -> {
                    byte[] value;
                    try {
                        value = Base64.getDecoder().decode(RequestParameters.requiredText(params, "data"));
                    } catch (IllegalArgumentException failure) {
                        throw new IllegalArgumentException("data must be canonical base64", failure);
                    }
                    var upload = repository.appendUploadChunk(
                            RequestParameters.requiredText(params, "uploadId"),
                            RequestParameters.optionalLong(params, "offset", -1),
                            value);
                    yield json.valueToTree(upload(upload));
                }
                case RpcMethods.ATTACHMENT_UPLOAD_COMPLETE ->
                    json.valueToTree(wire.attachment(
                            repository.completeUpload(RequestParameters.requiredText(params, "uploadId"))));
                case RpcMethods.ATTACHMENT_READ -> read(repository, params);
                case RpcMethods.ATTACHMENT_RELEASE ->
                    RpcResults.flag(
                            "removed", repository.releaseAttachment(RequestParameters.requiredText(params, "sha256")));
                default -> throw new RpcRouter.MethodNotFound(method);
            };
        } catch (IOException failure) {
            throw new IllegalStateException(
                    failure.getMessage() == null ? "attachment operation failed" : failure.getMessage(), failure);
        }
    }

    private JsonNode read(AttachmentRepository repository, JsonNode params) throws IOException {
        var chunk = repository.readChunk(
                RequestParameters.requiredText(params, "sha256"),
                RequestParameters.optionalLong(params, "offset", 0),
                Math.toIntExact(
                        RequestParameters.optionalLong(params, "maximumBytes", AttachmentRepository.MAX_CHUNK_BYTES)));
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        result.set("attachment", json.valueToTree(wire.attachment(chunk.metadata())));
        result.put("offset", chunk.offset());
        result.put("data", Base64.getEncoder().encodeToString(chunk.data()));
        result.put("eof", chunk.eof());
        return result;
    }

    private static WireAttachmentUpload upload(com.javaclaw.core.api.AttachmentUpload value) {
        return new WireAttachmentUpload(
                value.uploadId(),
                value.expectedSha256(),
                value.mediaType(),
                value.displayName(),
                value.expectedSize(),
                value.receivedBytes(),
                AttachmentRepository.MAX_CHUNK_BYTES,
                value.expiresAt());
    }

    private AttachmentRepository requireRepository() {
        if (attachments == null) {
            throw new IllegalStateException("attachment capability is unavailable");
        }
        return attachments;
    }
}
