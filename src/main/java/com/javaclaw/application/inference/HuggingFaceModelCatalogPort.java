package com.javaclaw.application.inference;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Anonymous, read-only catalog of Deliverance-compatible models published on Hugging Face. */
public interface HuggingFaceModelCatalogPort {

    SearchPage search(SearchRequest request, Set<String> supportedModelTypes,
                      BooleanSupplier cancelled) throws Exception;

    /**
     * Searches while publishing immutable progress snapshots. Implementations that do not support
     * incremental verification remain source-compatible and publish one final READY snapshot.
     */
    default SearchPage searchIncrementally(
            SearchRequest request,
            Set<String> supportedModelTypes,
            Consumer<SearchProgress> progress,
            BooleanSupplier cancelled) throws Exception {
        SearchPage page = search(request, supportedModelTypes, cancelled);
        if (progress != null) {
            progress.accept(new SearchProgress(SearchState.READY, page.models(),
                    page.models().size(), page.models().size(), Instant.now(), "", true, Map.of()));
        }
        return page;
    }

    ModelDetail detail(String repository, Set<String> supportedModelTypes,
                       BooleanSupplier cancelled) throws Exception;

    record SearchRequest(String query, String cursor, int limit) {
        public SearchRequest {
            query = query == null ? "" : query.strip();
            cursor = cursor == null ? "" : cursor.strip();
            if (query.length() > 256) throw new IllegalArgumentException("搜索词不能超过 256 个字符");
            if (cursor.length() > 4096) throw new IllegalArgumentException("分页游标无效");
            if (limit < 1 || limit > 20) throw new IllegalArgumentException("每页模型数必须在 1 到 20 之间");
        }

        public SearchRequest(String query) { this(query, "", 20); }
    }

    record SearchPage(List<ModelSummary> models, String nextCursor) {
        public SearchPage {
            models = models == null ? List.of() : List.copyOf(models);
            nextCursor = nextCursor == null ? "" : nextCursor.strip();
        }
    }

    enum SearchState { LOADING, PARTIAL, READY, STALE, ERROR }

    /** A point-in-time catalog result suitable for rendering without waiting for the whole scan. */
    record SearchProgress(
            SearchState state,
            List<ModelSummary> models,
            int scannedCount,
            int candidateCount,
            Instant cachedAt,
            String error,
            boolean recoverable,
            Map<String, Integer> rejectionReasons) {
        public SearchProgress {
            state = state == null ? SearchState.LOADING : state;
            models = models == null ? List.of() : List.copyOf(models);
            if (scannedCount < 0 || candidateCount < 0 || scannedCount > candidateCount) {
                throw new IllegalArgumentException("在线目录扫描计数无效");
            }
            cachedAt = cachedAt == null ? Instant.EPOCH : cachedAt;
            error = error == null ? "" : error.strip();
            rejectionReasons = rejectionReasons == null ? Map.of() : Map.copyOf(rejectionReasons);
        }

        public boolean complete() { return state == SearchState.READY || state == SearchState.ERROR; }
    }

    record ModelSummary(
            String repository,
            String commit,
            String modelType,
            String quantizationType,
            long sourceSizeBytes,
            long quantizedSizeBytes,
            Instant lastModified,
            boolean gated) {
        public ModelSummary {
            repository = normalizeRepository(repository);
            commit = commit == null ? "" : commit.strip().toLowerCase(Locale.ROOT);
            modelType = normalized(modelType, "unknown");
            quantizationType = normalized(quantizationType, "unknown").toUpperCase(Locale.ROOT);
            if (sourceSizeBytes < 0 || quantizedSizeBytes < 0) {
                throw new IllegalArgumentException("模型大小不能为负数");
            }
            lastModified = lastModified == null ? Instant.EPOCH : lastModified;
        }

        public String displayName() {
            int slash = repository.lastIndexOf('/');
            return slash >= 0 ? repository.substring(slash + 1) : repository;
        }
    }

    record ModelDetail(
            ModelSummary summary,
            String license,
            List<String> architectures,
            int fileCount,
            int declaredContextLength,
            String url) {
        public ModelDetail {
            if (summary == null) throw new IllegalArgumentException("模型摘要不能为空");
            license = license == null || license.isBlank() ? "未声明" : license.strip();
            architectures = architectures == null ? List.of() : architectures.stream()
                    .filter(value -> value != null && !value.isBlank())
                    .map(String::strip).distinct().toList();
            if (fileCount < 0 || declaredContextLength < 0) {
                throw new IllegalArgumentException("模型详情统计无效");
            }
            url = url == null ? "" : url.strip();
        }

        public boolean downloadable() { return !summary.gated(); }
    }

    HuggingFaceModelCatalogPort UNAVAILABLE = new HuggingFaceModelCatalogPort() {
        @Override
        public SearchPage search(SearchRequest request, Set<String> supportedModelTypes,
                                 BooleanSupplier cancelled) {
            return new SearchPage(List.of(), "");
        }

        @Override
        public ModelDetail detail(String repository, Set<String> supportedModelTypes,
                                  BooleanSupplier cancelled) {
            throw new IllegalStateException("Hugging Face 在线目录未配置");
        }
    };

    private static String normalizeRepository(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")) {
            throw new IllegalArgumentException("Hugging Face 仓库必须使用 owner/model 格式");
        }
        return value;
    }

    private static String normalized(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.strip();
    }
}
