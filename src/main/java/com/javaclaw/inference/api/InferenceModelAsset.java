package com.javaclaw.inference.api;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** 内容寻址模型资产。location 始终指向 JavaClaw 管理目录，不指向导入源。 */
public record InferenceModelAsset(
        UUID id,
        Source source,
        String displayName,
        String modelType,
        String contentSha256,
        String location,
        String huggingFaceRepository,
        String huggingFaceCommit,
        List<AssetFile> files,
        long sizeBytes,
        State state,
        String failure,
        Instant createdAt,
        ArtifactMetadata artifactMetadata) {

    public InferenceModelAsset {
        if (id == null) throw new IllegalArgumentException("资产 ID 不能为空");
        if (source == null) throw new IllegalArgumentException("资产来源不能为空");
        displayName = normalizeDisplayName(displayName, huggingFaceRepository, contentSha256);
        modelType = normalizeModelType(modelType);
        contentSha256 = requireSha256(contentSha256);
        location = requireText(location, "资产位置");
        huggingFaceRepository = normalize(huggingFaceRepository);
        huggingFaceCommit = normalize(huggingFaceCommit);
        files = files == null ? List.of() : List.copyOf(files);
        if (sizeBytes < 0) throw new IllegalArgumentException("资产大小不能为负数");
        if (state == null) throw new IllegalArgumentException("资产状态不能为空");
        failure = normalize(failure);
        createdAt = createdAt == null ? Instant.now() : createdAt;
        artifactMetadata = artifactMetadata == null
                ? ArtifactMetadata.unknown(sizeBytes) : artifactMetadata;
    }

    /** Compatibility constructor for assets persisted before artifact metadata was introduced. */
    public InferenceModelAsset(
            UUID id,
            Source source,
            String displayName,
            String modelType,
            String contentSha256,
            String location,
            String huggingFaceRepository,
            String huggingFaceCommit,
            List<AssetFile> files,
            long sizeBytes,
            State state,
            String failure,
            Instant createdAt) {
        this(id, source, displayName, modelType, contentSha256, location,
                huggingFaceRepository, huggingFaceCommit, files, sizeBytes, state,
                failure, createdAt, ArtifactMetadata.unknown(sizeBytes));
    }

    /** 兼容尚未持久化 model_type 的 3.0 早期调用方。 */
    public InferenceModelAsset(
            UUID id,
            Source source,
            String displayName,
            String contentSha256,
            String location,
            String huggingFaceRepository,
            String huggingFaceCommit,
            List<AssetFile> files,
            long sizeBytes,
            State state,
            String failure,
            Instant createdAt) {
        this(id, source, displayName, "unknown", contentSha256, location,
                huggingFaceRepository, huggingFaceCommit, files, sizeBytes, state, failure, createdAt);
    }

    /** 兼容 3.0 早期资产记录；旧本地导入无法恢复源目录名时使用摘要前缀。 */
    public InferenceModelAsset(
            UUID id,
            Source source,
            String contentSha256,
            String location,
            String huggingFaceRepository,
            String huggingFaceCommit,
            List<AssetFile> files,
            long sizeBytes,
            State state,
            String failure,
            Instant createdAt) {
        this(id, source, "", "unknown", contentSha256, location, huggingFaceRepository, huggingFaceCommit,
                files, sizeBytes, state, failure, createdAt);
    }

    public enum Source { LOCAL_DIRECTORY, HUGGING_FACE }
    public enum State { STAGING, DOWNLOADING, VERIFYING, READY, FAILED }

    public String format() { return artifactMetadata.format(); }
    public String quantizationType() { return artifactMetadata.quantizationType(); }
    public long sourceSizeBytes() { return artifactMetadata.sourceSizeBytes(); }
    public Instant sourceUpdatedAt() { return artifactMetadata.sourceUpdatedAt(); }

    /** Sanitized model artifact metadata; remote/local absolute provenance paths are never retained. */
    public record ArtifactMetadata(
            String format,
            String quantizationType,
            long sourceSizeBytes,
            long quantizedSizeBytes,
            Instant sourceUpdatedAt) {
        public ArtifactMetadata {
            format = normalize(format).toUpperCase(Locale.ROOT);
            if (format.isBlank()) format = "SAFETENSORS";
            quantizationType = normalize(quantizationType).toUpperCase(Locale.ROOT);
            if (quantizationType.isBlank()) quantizationType = "UNKNOWN";
            if (sourceSizeBytes < 0 || quantizedSizeBytes < 0) {
                throw new IllegalArgumentException("模型工件大小不能为负数");
            }
            sourceUpdatedAt = sourceUpdatedAt == null ? Instant.EPOCH : sourceUpdatedAt;
        }

        public static ArtifactMetadata unknown(long quantizedSizeBytes) {
            return new ArtifactMetadata("SAFETENSORS", "UNKNOWN", 0,
                    Math.max(0, quantizedSizeBytes), Instant.EPOCH);
        }
    }

    public record AssetFile(String path, long size, String sha256) {
        public AssetFile {
            path = requireText(path, "资产文件路径");
            if (size < 0) throw new IllegalArgumentException("资产文件长度不能为负数");
            sha256 = requireSha256(sha256);
        }
    }

    private static String normalize(String value) { return value == null ? "" : value.strip(); }
    private static String normalizeModelType(String value) {
        String normalized = normalize(value).toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) return "unknown";
        if (!normalized.matches("[a-z0-9][a-z0-9_-]{0,127}")) {
            throw new IllegalArgumentException("模型类型格式无效");
        }
        return normalized;
    }
    private static String normalizeDisplayName(String value, String repository, String sha256) {
        String supplied = normalize(value);
        if (!supplied.isBlank()) return supplied;
        String repo = normalize(repository);
        if (!repo.isBlank()) {
            int slash = repo.lastIndexOf('/');
            return slash >= 0 && slash + 1 < repo.length() ? repo.substring(slash + 1) : repo;
        }
        String digest = normalize(sha256);
        return digest.substring(0, Math.min(12, digest.length()));
    }
    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(label + "不能为空");
        return value.strip();
    }
    private static String requireSha256(String value) {
        String normalized = requireText(value, "SHA-256").toLowerCase();
        if (!normalized.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("SHA-256 格式无效");
        return normalized;
    }
}
