package com.javaclaw.application.inference;

import com.javaclaw.inference.api.InferenceModelAsset;

import java.nio.file.Path;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** 安全导入模型资产的基础设施端口。 */
public interface InferenceAssetPreparationPort {

    /** Reconciles durable managed assets without loading a model or starting its runtime. */
    default void reconcileManagedAssets() throws Exception { }

    HuggingFacePreview previewHuggingFace(HuggingFaceRequest request,
                                          BooleanSupplier cancelled) throws Exception;

    InferenceModelAsset importLocalDirectory(Path source, Consumer<Progress> progress,
                                             BooleanSupplier cancelled) throws Exception;

    InferenceModelAsset downloadHuggingFace(HuggingFaceRequest request, Consumer<Progress> progress,
                                            BooleanSupplier cancelled) throws Exception;

    void deleteManagedAsset(InferenceModelAsset asset) throws Exception;

    record HuggingFaceRequest(String repository, String revision, String token) {
        public HuggingFaceRequest {
            if (repository == null || !repository.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")) {
                throw new IllegalArgumentException("Hugging Face 仓库必须使用 owner/model 格式");
            }
            revision = revision == null || revision.isBlank() ? "main" : revision.strip();
            token = token == null ? "" : token;
        }
    }

    record HuggingFacePreview(String repository, String commit, long sizeBytes,
                              String license, boolean gated, int fileCount,
                              String modelType) {
        public HuggingFacePreview {
            repository = repository == null ? "" : repository;
            commit = commit == null ? "" : commit;
            license = license == null || license.isBlank() ? "未声明" : license;
            if (sizeBytes < 0 || fileCount < 0) throw new IllegalArgumentException("HF 预览统计无效");
            modelType = modelType == null || modelType.isBlank()
                    ? "unknown" : modelType.strip().toLowerCase(java.util.Locale.ROOT);
        }

        public HuggingFacePreview(String repository, String commit, long sizeBytes,
                                  String license, boolean gated, int fileCount) {
            this(repository, commit, sizeBytes, license, gated, fileCount, "unknown");
        }
    }

    record Progress(String phase, String currentFile, long completedBytes, long totalBytes) {
        public Progress {
            phase = phase == null ? "" : phase;
            currentFile = currentFile == null ? "" : currentFile;
            if (completedBytes < 0 || totalBytes < 0) throw new IllegalArgumentException("进度不能为负数");
        }
    }
}
