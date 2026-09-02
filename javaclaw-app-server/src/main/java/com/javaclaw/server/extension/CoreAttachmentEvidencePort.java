package com.javaclaw.server.extension;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

import com.javaclaw.api.AttachmentContent;
import com.javaclaw.api.AttachmentMetadata;
import com.javaclaw.api.AttachmentRef;
import com.javaclaw.api.AttachmentScope;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.extension.spi.AttachmentEvidencePort;
import com.javaclaw.server.persistence.AttachmentService;
import com.javaclaw.server.persistence.CommandIdentity;

/** 把 Core Attachment claim 的权威核验能力收窄为内置扩展只读端口。 */
public final class CoreAttachmentEvidencePort implements AttachmentEvidencePort {
    private final AttachmentService attachments;
    private final WorkspaceId workspaceId;

    /**
     * 创建端口。
     *
     * @param attachments Core Attachment 服务
     * @param workspaceId 本端口唯一允许核验的 Workspace
     */
    public CoreAttachmentEvidencePort(AttachmentService attachments, WorkspaceId workspaceId) {
        this.attachments = Objects.requireNonNull(attachments, "attachments");
        this.workspaceId = Objects.requireNonNull(workspaceId, "workspaceId");
    }

    @Override
    public AttachmentMetadata requireOwned(AttachmentRef reference) {
        return attachments.requireOwned(workspaceId, reference);
    }

    @Override
    public AttachmentContent readOwned(String digest, long maximumBytes) {
        if (maximumBytes < 1) {
            throw new IllegalArgumentException("maximumBytes must be positive");
        }
        AttachmentContent content = attachments.read(AttachmentScope.workspace(workspaceId), digest);
        if (content.metadata().sizeBytes() > maximumBytes) {
            throw new IllegalArgumentException("Attachment exceeds the requested read limit");
        }
        return content;
    }

    @Override
    public AttachmentRef storeGenerated(
            String operation, String idempotencyKey, String mediaType, String fileName, byte[] content) {
        String checkedOperation = text(operation, "operation");
        String checkedKey = text(idempotencyKey, "idempotencyKey");
        String checkedMediaType = text(mediaType, "mediaType");
        String checkedFileName = text(fileName, "fileName");
        byte[] checkedContent = Objects.requireNonNull(content, "content").clone();
        CommandIdentity identity = new CommandIdentity(
                "extension/attachment/store/" + checkedOperation,
                "extension-attachment:" + checkedOperation + ":" + checkedKey,
                0,
                requestDigest(checkedOperation, checkedMediaType, checkedFileName, checkedContent));
        AttachmentMetadata stored =
                attachments.store(AttachmentScope.workspace(workspaceId), identity, checkedMediaType, checkedContent);
        return new AttachmentRef(stored.digest(), stored.mediaType(), checkedFileName, stored.sizeBytes());
    }

    private static String requestDigest(String operation, String mediaType, String fileName, byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, operation);
            update(digest, mediaType);
            update(digest, fileName);
            digest.update(content);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    private static void update(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }

    private static String text(String value, String name) {
        String checked = Objects.requireNonNull(value, name).strip();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return checked;
    }
}
