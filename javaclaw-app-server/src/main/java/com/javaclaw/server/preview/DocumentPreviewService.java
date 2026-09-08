package com.javaclaw.server.preview;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import com.javaclaw.api.CancellationSource;
import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.DocumentChunk;
import com.javaclaw.api.DocumentPreview;
import com.javaclaw.api.DocumentReference;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.DocumentPreviewRpcContracts;
import com.javaclaw.server.persistence.AttachmentService;
import com.javaclaw.server.persistence.CoreItemReader;
import com.javaclaw.server.turn.PreviewReadAuthority;

/**
 * App Server 独占的预览资源用例；连接断开失效句柄，重连不会继续使用旧文件版本。
 *
 * <p>缓存只保存不可变版本，每次使用仍检查来源与实时权限；绝不让缓存命中成为权限授予。
 */
public final class DocumentPreviewService implements AutoCloseable {
    private final PreviewSnapshotCache cache;
    private final PreviewSources sources;
    private final CanonicalJson json;
    private final PreviewOperationGate operations = new PreviewOperationGate();
    private final Map<String, Consumer<DocumentPreviewRpcContracts.Invalidated>> listeners = new ConcurrentHashMap<>();

    /**
     * 创建共享预算和来源用例。
     *
     * @param dataRoot 已验证的数据根
     * @param core Core 查询
     * @param attachments 附件所有权及流读取
     * @param authority 历史来源权限
     * @param json 规范 codec
     * @param clock 平台时钟
     * @throws IOException 缓存目录不满足安全边界
     */
    public DocumentPreviewService(
            Path dataRoot,
            CoreItemReader core,
            AttachmentService attachments,
            PreviewReadAuthority authority,
            CanonicalJson json,
            Clock clock)
            throws IOException {
        this.cache = new PreviewSnapshotCache(dataRoot, clock, this::invalidated);
        this.sources = new PreviewSources(core, attachments, authority, json);
        this.json = json;
    }

    /**
     * 创建连接独占的控制状态，由 AppServerSession 关闭。
     *
     * @return 空句柄集合及有界幂等状态
     */
    public DocumentPreviewSession openSession() {
        try (var operation = operations.enter()) {
            return new DocumentPreviewSession(this, json, UUID.randomUUID().toString());
        }
    }

    DocumentPreview resolve(String session, DocumentReference reference, CancellationToken cancellation)
            throws Exception {
        try (var operation = operations.enter()) {
            return create(session, () -> sources.prepare(reference, cancellation), cancellation);
        }
    }

    DocumentPreview resource(String session, String parentId, String href, CancellationToken cancellation)
            throws Exception {
        try (var operation = operations.enter()) {
            return createResource(session, parentId, href, cancellation);
        }
    }

    private DocumentPreview createResource(String session, String parentId, String href, CancellationToken cancellation)
            throws Exception {
        var parent = authorized(session, parentId);
        if (parent.result.sizeBytes() > 2 * 1024 * 1024) {
            throw new IllegalArgumentException("PREVIEW_TOO_LARGE: 相对资源解析超过 Markdown 预算");
        }
        var bytes = new ByteArrayOutputStream();
        long offset = 0;
        while (offset < parent.result.sizeBytes()) {
            var chunk = read(session, parentId, offset, 256 * 1024);
            bytes.write(chunk.content());
            offset = chunk.nextOffsetBytes();
        }
        String markdown = PreviewText.decode(bytes.toByteArray());
        return create(session, () -> sources.resource(parent.source, markdown, href, cancellation), cancellation);
    }

    private DocumentPreview create(String session, SourceFactory factory, CancellationToken cancellation)
            throws Exception {
        for (int attempt = 0; attempt < 2; attempt++) {
            cancellation.throwIfCancelled();
            var source = factory.prepare();
            var entry = cache.reserve(session, source);
            boolean committed = false;
            try {
                CancellationToken token = combined(cancellation, entry.cancellation);
                var result = sources.copy(source, entry.content(), token);
                token.throwIfCancelled();
                sources.revalidate(source);
                var preview = cache.complete(entry, result);
                committed = true;
                return preview;
            } catch (IOException failure) {
                if (attempt != 0
                        || failure.getMessage() == null
                        || !failure.getMessage().startsWith("PREVIEW_SOURCE_CHANGED")) {
                    throw failure;
                }
            } finally {
                if (!committed) {
                    cache.release(entry);
                }
            }
        }
        throw new IOException("PREVIEW_SOURCE_CHANGED: 文件持续更新，请稍后重试");
    }

    DocumentChunk read(String session, String id, long offset, int maximum) throws IOException {
        try (var operation = operations.enter()) {
            return readAuthorized(session, id, offset, maximum);
        }
    }

    private DocumentChunk readAuthorized(String session, String id, long offset, int maximum) throws IOException {
        var entry = authorized(session, id);
        try {
            DocumentChunk result = cache.read(entry, offset, maximum);
            cache.renew(entry);
            return result;
        } catch (IOException failure) {
            cache.invalidate(session, id, "CORRUPT");
            throw failure;
        }
    }

    DocumentPreview renew(String session, String id) {
        try (var operation = operations.enter()) {
            return cache.renew(authorized(session, id));
        }
    }

    private PreviewSnapshotCache.Entry authorized(String session, String id) {
        var entry = cache.require(session, id);
        try {
            sources.revalidate(entry.source);
            return entry;
        } catch (RuntimeException failure) {
            entry.cancellation.cancel("预览权限或来源已失效");
            try {
                cache.invalidate(session, id, "REVOKED");
            } catch (IOException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    void closeHandle(String session, String id) throws IOException {
        cache.closeHandle(session, id);
    }

    void closeSession(String session) throws IOException {
        listeners.remove(session);
        cache.closeSession(session);
    }

    void notifications(String session, Consumer<DocumentPreviewRpcContracts.Invalidated> listener) {
        try (var operation = operations.enter()) {
            listeners.put(session, listener);
        }
    }

    private void invalidated(PreviewSnapshotCache.Invalidation invalidation) {
        var listener = listeners.get(invalidation.session());
        if (listener != null) {
            listener.accept(
                    new DocumentPreviewRpcContracts.Invalidated(invalidation.handleId(), invalidation.reasonCode()));
        }
    }

    /**
     * 封住所有来源读取入口，取消快照创建并等待已入场的操作退出；重复关闭串行等待同一清理。
     *
     * @throws IOException 某个缓存无法清理；其他句柄仍会被取消，操作屏障仍会完成
     */
    @Override
    public synchronized void close() throws IOException {
        operations.stopAccepting();
        try {
            cache.close();
        } finally {
            operations.awaitIdle();
            listeners.clear();
        }
    }

    private static CancellationToken combined(CancellationToken session, CancellationSource entry) {
        return new CancellationToken() {
            @Override
            public boolean isCancelled() {
                return session.isCancelled() || entry.isCancelled();
            }

            @Override
            public Optional<String> reason() {
                return session.reason().or(entry::reason);
            }
        };
    }

    @FunctionalInterface
    private interface SourceFactory {
        PreviewSources.Prepared prepare() throws Exception;
    }
}
