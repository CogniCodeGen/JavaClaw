package com.javaclaw.server.persistence;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;

import com.javaclaw.agent.runtime.persistence.AttachmentRepository;
import com.javaclaw.core.api.AttachmentMetadata;
import com.javaclaw.core.api.AttachmentReadChunk;
import com.javaclaw.core.api.AttachmentReconciliation;
import com.javaclaw.core.api.AttachmentUpload;

/** Content-addressed blob and resumable-upload adapter. */
public final class H2AttachmentRepository implements AttachmentRepository {
    private final H2PersistenceEngine engine;

    H2AttachmentRepository(H2PersistenceEngine engine) {
        this.engine = engine;
    }

    @Override
    public AttachmentMetadata put(InputStream source, String mediaType) throws IOException {
        return engine.put(source, mediaType);
    }

    @Override
    public Optional<AttachmentMetadata> findAttachment(String hash) {
        return engine.findAttachment(hash);
    }

    @Override
    public InputStream openAttachment(String hash) throws IOException {
        return engine.openAttachment(hash);
    }

    @Override
    public AttachmentMetadata retainAttachment(String hash) {
        return engine.retainAttachment(hash);
    }

    @Override
    public boolean releaseAttachment(String hash) throws IOException {
        return engine.releaseAttachment(hash);
    }

    @Override
    public AttachmentUpload startUpload(String hash, String mediaType, String displayName, long size, String key)
            throws IOException {
        return engine.startUpload(hash, mediaType, displayName, size, key);
    }

    @Override
    public AttachmentUpload appendUploadChunk(String id, long offset, byte[] data) throws IOException {
        return engine.appendUploadChunk(id, offset, data);
    }

    @Override
    public AttachmentMetadata completeUpload(String id) throws IOException {
        return engine.completeUpload(id);
    }

    @Override
    public AttachmentReadChunk readChunk(String hash, long offset, int maximumBytes) throws IOException {
        return engine.readChunk(hash, offset, maximumBytes);
    }

    @Override
    public int collectExpiredUploads() throws IOException {
        return engine.collectExpiredUploads();
    }

    @Override
    public AttachmentReconciliation reconcileAttachments() throws IOException {
        return engine.reconcileAttachments();
    }
}
