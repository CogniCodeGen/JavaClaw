package com.javaclaw.server.extension;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.AttachmentScope;
import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.builtin.contracts.BrowserContracts;
import com.javaclaw.protocol.CanonicalJson;

/** 单次动作持有的上传私有缓冲区；只允许当前 Thread 已拥有附件，关闭时销毁本实例原始字节。 */
final class BrowserUploadInput implements AutoCloseable {
    private final BrowserContracts.Action action;
    private final byte[] content;
    private boolean closed;

    private BrowserUploadInput(BrowserContracts.Action action, byte[] content) {
        this.action = action;
        this.content = content;
    }

    static BrowserUploadInput prepare(
            SiteBrowserHostContext host,
            BrowserThreadAttachments attachments,
            BrowserSessionState session,
            BrowserContracts.Action action,
            Optional<String> digest) {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(digest, "digest");
        if (action.operation() != BrowserContracts.Operation.UPLOAD) {
            if (digest.isPresent() || action.input().file().isPresent()) {
                throw new IllegalArgumentException("只有 UPLOAD 操作允许提供上传附件");
            }
            return new BrowserUploadInput(action, new byte[0]);
        }
        String selected = digest.orElseThrow(() -> new IllegalArgumentException("UPLOAD 需要当前对话附件摘要"));
        var reference = attachments.upload(session.owner.workspaceId(), session.owner.threadId(), selected);
        if (reference.sizeBytes() > BrowserContracts.MAXIMUM_ARTIFACT_BYTES) {
            throw new IllegalArgumentException("浏览器上传附件超过单次大小限制");
        }
        var file = new BrowserContracts.FileSpec(reference.fileName(), reference.mediaType());
        var input = new BrowserContracts.ActionInput("", Optional.empty(), Optional.empty(), Optional.of(file));
        var prepared = new BrowserContracts.Action(BrowserContracts.Operation.UPLOAD, action.target(), input);
        byte[] bytes = host.attachments()
                .read(AttachmentScope.workspace(session.owner.workspaceId()), selected)
                .content();
        if (bytes.length != reference.sizeBytes()) {
            Arrays.fill(bytes, (byte) 0);
            throw new SecurityException("上传附件长度与当前对话引用不一致");
        }
        return new BrowserUploadInput(prepared, bytes);
    }

    static Optional<String> digest(CanonicalJson json, CanonicalPayload payload) {
        Optional<String> digest = json.textField(payload, "attachmentDigest");
        Optional<String> typed = json.objectField(payload, "upload")
                .map(value ->
                        json.decode(value, com.javaclaw.api.AttachmentRef.class).digest());
        if (digest.isPresent() && typed.isPresent() && !digest.equals(typed)) {
            throw new IllegalArgumentException("上传附件摘要不一致");
        }
        return digest.or(() -> typed);
    }

    BrowserContracts.Action action() {
        requireOpen();
        return action;
    }

    byte[] bytes() {
        requireOpen();
        return content;
    }

    /** 同步 Worker 操作结束后销毁本实例缓冲区；重复关闭安全。 */
    @Override
    public void close() {
        closed = true;
        Arrays.fill(content, (byte) 0);
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("浏览器上传输入已经关闭");
        }
    }
}
