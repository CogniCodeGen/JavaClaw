package com.javaclaw.infrastructure.inference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.application.inference.HuggingFaceModelCatalogPort;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Direct anonymous Hugging Face catalog client with strict Deliverance quantization filtering. */
public final class HuggingFaceModelCatalogClient implements HuggingFaceModelCatalogPort {
    static final String QUANTIZATION_MANIFEST = "deliverance-quantization.json";
    private static final long MAX_DOCUMENT_BYTES = 8L * 1024 * 1024;
    private static final int MAX_CANDIDATES = 100;
    private static final Set<String> DOWNLOAD_METADATA = Set.of(
            "config.json", "generation_config.json", "tokenizer.json", "tokenizer_config.json",
            "special_tokens_map.json", "added_tokens.json", "preprocessor_config.json",
            "sentence_bert_config.json", "modules.json", QUANTIZATION_MANIFEST);

    private final Transport transport;
    private final ObjectMapper json;
    private final URI base;
    private final int verificationConcurrency;
    private final Map<SearchKey, CachedSearch> cache = new ConcurrentHashMap<>();

    public HuggingFaceModelCatalogClient(HttpClient http, ObjectMapper json) {
        this(http, json, URI.create("https://huggingface.co/"));
    }

    HuggingFaceModelCatalogClient(HttpClient http, ObjectMapper json, URI base) {
        this(Objects.requireNonNull(http, "http")::send, json, base, 4);
    }

    HuggingFaceModelCatalogClient(Transport transport, ObjectMapper json, URI base) {
        this(transport, json, base, 1);
    }

    private HuggingFaceModelCatalogClient(
            Transport transport, ObjectMapper json, URI base, int verificationConcurrency) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.json = Objects.requireNonNull(json, "json");
        URI supplied = Objects.requireNonNull(base, "base");
        String normalized = supplied.toString().endsWith("/")
                ? supplied.toString() : supplied + "/";
        this.base = URI.create(normalized);
        this.verificationConcurrency = Math.max(1, verificationConcurrency);
    }

    @Override
    public SearchPage search(SearchRequest request, Set<String> supportedModelTypes,
                             BooleanSupplier cancelled) throws Exception {
        return searchIncrementally(request, supportedModelTypes, ignored -> { }, cancelled);
    }

    @Override
    public SearchPage searchIncrementally(
            SearchRequest request,
            Set<String> supportedModelTypes,
            Consumer<SearchProgress> progress,
            BooleanSupplier cancelled) throws Exception {
        Objects.requireNonNull(request, "request");
        Set<String> supported = normalizeTypes(supportedModelTypes);
        requireSupportedRuntime(supported);
        requireActive(cancelled);
        Consumer<SearchProgress> listener = progress == null ? ignored -> { } : progress;
        SearchKey key = new SearchKey(request, supported);
        CachedSearch cached = usableCache(cache.get(key));
        if (cached != null) {
            listener.accept(new SearchProgress(SearchState.STALE, cached.page().models(),
                    cached.scannedCount(), cached.candidateCount(), cached.cachedAt(), "", true,
                    cached.rejectionReasons()));
        }

        try {
            SearchOutcome outcome = repositoryQuery(request.query()) && request.cursor().isBlank()
                    ? exactSearch(request.query(), supported, listener, cancelled)
                    : catalogSearch(request, supported, listener, cancelled);
            if (outcome.cacheable()) {
                cache.put(key, new CachedSearch(outcome.page(), Instant.now(),
                        outcome.scannedCount(), outcome.candidateCount(), outcome.rejectionReasons()));
            }
            return outcome.page();
        } catch (InterruptedException cancelledFailure) {
            throw cancelledFailure;
        } catch (Exception failure) {
            List<ModelSummary> fallback = cached == null ? List.of() : cached.page().models();
            int scanned = cached == null ? 0 : cached.scannedCount();
            int candidates = cached == null ? 0 : cached.candidateCount();
            listener.accept(new SearchProgress(SearchState.ERROR, fallback, scanned, candidates,
                    cached == null ? Instant.EPOCH : cached.cachedAt(), failureMessage(failure),
                    cached != null, cached == null ? Map.of() : cached.rejectionReasons()));
            if (cached != null) return cached.page();
            throw failure;
        }
    }

    private SearchOutcome exactSearch(
            String repository, Set<String> supported, Consumer<SearchProgress> progress,
            BooleanSupplier cancelled) throws Exception {
        progress.accept(new SearchProgress(SearchState.LOADING, List.of(), 0, 1,
                Instant.EPOCH, "", true, Map.of()));
        try {
            ModelSummary model = detail(repository, supported, cancelled).summary();
            SearchPage page = new SearchPage(List.of(model), "");
            progress.accept(new SearchProgress(SearchState.READY, page.models(), 1, 1,
                    Instant.now(), "", true, Map.of()));
            return new SearchOutcome(page, 1, 1, Map.of(), true);
        } catch (CatalogMismatchException mismatch) {
            Map<String, Integer> rejected = Map.of(mismatch.getMessage(), 1);
            SearchPage page = new SearchPage(List.of(), "");
            progress.accept(new SearchProgress(SearchState.READY, List.of(), 1, 1,
                    Instant.now(), "", true, rejected));
            return new SearchOutcome(page, 1, 1, rejected, true);
        }
    }

    private SearchOutcome catalogSearch(
            SearchRequest request, Set<String> supported, Consumer<SearchProgress> progress,
            BooleanSupplier cancelled) throws Exception {
        URI uri = request.cursor().isBlank()
                ? searchUri(request.query(), Math.min(MAX_CANDIDATES,
                        Math.max(50, request.limit() * 5)))
                : cursorUri(request.cursor());
        HttpResponse<byte[]> response = send(HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json").GET().build(), cancelled);
        requireStatus(response, "搜索 Hugging Face 模型");
        JsonNode root = document(response.body());
        if (!root.isArray()) throw new IOException("Hugging Face 搜索结果不是数组");

        List<Candidate> candidates = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (JsonNode candidate : root) {
            String repository = candidate.path("id").asText(
                    candidate.path("modelId").asText("")).strip();
            if (!repositoryQuery(repository) || !seen.add(repository)
                    || candidate.path("private").asBoolean(false)) continue;
            candidates.add(new Candidate(repository,
                    candidate.path("config").path("model_type").asText("").strip()
                            .toLowerCase(Locale.ROOT)));
        }
        int total = candidates.size();
        progress.accept(new SearchProgress(SearchState.LOADING, List.of(), 0, total,
                Instant.EPOCH, "", true, Map.of()));
        if (total == 0) {
            SearchPage page = new SearchPage(List.of(), nextCursor(response));
            progress.accept(new SearchProgress(SearchState.READY, List.of(), 0, 0,
                    Instant.now(), "", true, Map.of()));
            return new SearchOutcome(page, 0, 0, Map.of(), true);
        }

        List<ModelSummary> result = new ArrayList<>();
        Map<String, Integer> rejected = new LinkedHashMap<>();
        List<String> failures = new ArrayList<>();
        ExecutorService executor = Executors.newFixedThreadPool(
                Math.min(verificationConcurrency, total),
                Thread.ofVirtual().name("hf-catalog-verify-", 0).factory());
        CompletionService<Verification> completion = new ExecutorCompletionService<>(executor);
        try {
            candidates.forEach(candidate -> completion.submit(
                    () -> verify(candidate, supported, cancelled)));
            for (int scanned = 1; scanned <= total; scanned++) {
                requireActive(cancelled);
                Verification verified;
                try {
                    verified = completion.take().get();
                } catch (ExecutionException failure) {
                    Throwable cause = failure.getCause();
                    if (cause instanceof InterruptedException interrupted) throw interrupted;
                    if (cause instanceof Exception exception) throw exception;
                    throw new IOException("验证 Hugging Face 仓库失败", cause);
                }
                if (verified.model() != null) result.add(verified.model());
                else if (!verified.rejection().isBlank()) rejected.merge(
                        verified.rejection(), 1, Integer::sum);
                else if (!verified.failure().isBlank()) failures.add(verified.failure());
                List<ModelSummary> visible = sorted(result, request.limit());
                SearchState state = scanned == total
                        ? (failures.isEmpty() ? SearchState.READY : SearchState.ERROR)
                        : SearchState.PARTIAL;
                progress.accept(new SearchProgress(state, visible, scanned, total, Instant.now(),
                        failures.isEmpty() ? "" : String.join("；", failures), true, rejected));
            }
        } finally {
            executor.shutdownNow();
        }
        List<ModelSummary> visible = sorted(result, request.limit());
        if (visible.isEmpty() && !failures.isEmpty()) {
            throw new IOException("验证 Hugging Face 仓库失败：" + String.join("；", failures));
        }
        SearchPage page = new SearchPage(visible, nextCursor(response));
        return new SearchOutcome(page, total, total, rejected, failures.isEmpty());
    }

    private Verification verify(
            Candidate candidate, Set<String> supported, BooleanSupplier cancelled) throws Exception {
        if (!candidate.declaredModelType().isBlank()
                && !supported.contains(candidate.declaredModelType())) {
            return Verification.rejected("模型类型不兼容");
        }
        try {
            return Verification.accepted(detail(candidate.repository(), supported, cancelled).summary());
        } catch (CatalogMismatchException mismatch) {
            return Verification.rejected(mismatch.getMessage());
        } catch (InterruptedException cancelledFailure) {
            throw cancelledFailure;
        } catch (Exception failure) {
            return Verification.failed(failureMessage(failure));
        }
    }

    private static List<ModelSummary> sorted(List<ModelSummary> models, int limit) {
        return models.stream().sorted(Comparator.comparing(ModelSummary::lastModified).reversed()
                        .thenComparing(ModelSummary::repository)).limit(limit).toList();
    }

    @Override
    public ModelDetail detail(String repository, Set<String> supportedModelTypes,
                              BooleanSupplier cancelled) throws Exception {
        if (!repositoryQuery(repository)) {
            throw new IllegalArgumentException("Hugging Face 仓库必须使用 owner/model 格式");
        }
        Set<String> supported = normalizeTypes(supportedModelTypes);
        requireSupportedRuntime(supported);
        requireActive(cancelled);

        URI infoUri = base.resolve("api/models/" + encodePath(repository) + "?blobs=true");
        HttpResponse<byte[]> response = send(HttpRequest.newBuilder(infoUri)
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json").GET().build(), cancelled);
        if (response.statusCode() == 404) throw mismatch("Hugging Face 仓库不存在");
        requireStatus(response, "读取 Hugging Face 模型详情");
        JsonNode root = document(response.body());
        if (!root.isObject() || root.path("private").asBoolean(false)) {
            throw mismatch("私有模型不在匿名目录中展示");
        }
        String commit = root.path("sha").asText("").strip().toLowerCase(Locale.ROOT);
        if (!commit.matches("[0-9a-f]{40,64}")) throw mismatch("模型没有可固定的 commit");
        boolean gated = gated(root.path("gated"));

        List<RemoteFile> files = files(root.path("siblings"));
        if (files.stream().noneMatch(file -> file.path().equals(QUANTIZATION_MANIFEST))) {
            throw mismatch("仓库缺少 Deliverance 量化清单");
        }
        validateFileSet(files);

        JsonNode config = root.path("config");
        if (!config.isObject() || config.path("model_type").asText("").isBlank()) {
            config = remoteJson(repository, commit, "config.json", cancelled, false);
        }
        String modelType = config.path("model_type").asText("").strip().toLowerCase(Locale.ROOT);
        if (!supported.contains(modelType)) throw mismatch("当前 Deliverance 不支持该模型类型");

        JsonNode manifest;
        try {
            manifest = remoteJson(repository, commit, QUANTIZATION_MANIFEST, cancelled, gated);
        } catch (CatalogMismatchException inaccessible) {
            manifest = gated ? embeddedGatedManifest(root) : null;
            if (manifest == null || !manifest.isObject()) throw inaccessible;
        }
        if (!manifest.isObject() || manifest.path("schemaVersion").asInt(-1) != 1) {
            throw mismatch("Deliverance 量化清单版本无效");
        }
        String target = manifest.path("targetType").asText("").strip().toUpperCase(Locale.ROOT);
        if (!target.equals("Q4") && !target.equals("I8")) {
            throw mismatch("仓库不是 Deliverance Q4/I8 量化模型");
        }
        long sourceSize = manifest.path("sourceSizeBytes").asLong(-1);
        long outputSize = manifest.path("outputSizeBytes").asLong(-1);
        if (sourceSize <= 0 || outputSize <= 0) throw mismatch("量化清单大小无效");
        long weightSize = files.stream().filter(file -> file.path().endsWith(".safetensors"))
                .mapToLong(RemoteFile::size).reduce(0, Math::addExact);
        if (outputSize < weightSize) throw mismatch("量化清单小于 Safetensors 权重大小");
        long downloadSize = 0;
        for (RemoteFile file : files) {
            if (!shouldDownload(file.path())) continue;
            if (file.size() < 0) throw mismatch("仓库未提供精确文件大小");
            downloadSize = Math.addExact(downloadSize, file.size());
        }
        if (downloadSize <= 0) throw mismatch("仓库下载大小无效");

        Instant lastModified = instant(root.path("lastModified").asText(""));
        String license = root.path("cardData").path("license").asText(
                root.path("license").asText("未声明"));
        List<String> architectures = texts(config.path("architectures"));
        int contextLength = contextLength(config);
        ModelSummary summary = new ModelSummary(repository, commit, modelType, target,
                sourceSize, downloadSize, lastModified, gated);
        return new ModelDetail(summary, license, architectures, files.size(), contextLength,
                base.resolve(encodePath(repository)).toString());
    }

    private URI searchUri(String query, int candidates) {
        String value = catalogQuery(query);
        String params = "search=" + encode(value)
                + "&sort=lastModified&direction=-1&limit=" + candidates
                + "&full=true&config=true";
        return base.resolve("api/models?" + params);
    }

    private static String catalogQuery(String query) {
        String value = query == null ? "" : query.strip();
        if (value.isBlank()) return "JQ4";
        return value.toLowerCase(Locale.ROOT).contains("jq4") ? value : value + " JQ4";
    }

    private URI cursorUri(String cursor) throws IOException {
        try {
            URI value = URI.create(new String(Base64.getUrlDecoder().decode(cursor),
                    StandardCharsets.UTF_8));
            if (!sameOrigin(value) || !value.getPath().startsWith(base.resolve("api/models").getPath())) {
                throw new IOException("Hugging Face 分页游标越界");
            }
            return value;
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Hugging Face 分页游标无效", invalid);
        }
    }

    private String nextCursor(HttpResponse<?> response) {
        for (String header : response.headers().allValues("Link")) {
            for (String part : header.split(",")) {
                if (!part.contains("rel=\"next\"") && !part.contains("rel=next")) continue;
                int start = part.indexOf('<');
                int end = part.indexOf('>', start + 1);
                if (start < 0 || end <= start) continue;
                try {
                    URI value = URI.create(part.substring(start + 1, end));
                    if (!value.isAbsolute()) value = base.resolve(value);
                    if (!sameOrigin(value)) continue;
                    return Base64.getUrlEncoder().withoutPadding().encodeToString(
                            value.toString().getBytes(StandardCharsets.UTF_8));
                } catch (IllegalArgumentException ignored) {
                    continue;
                }
            }
        }
        return "";
    }

    private boolean sameOrigin(URI value) {
        return value != null
                && Objects.equals(base.getScheme(), value.getScheme())
                && Objects.equals(base.getHost(), value.getHost())
                && effectivePort(base) == effectivePort(value);
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() >= 0) return uri.getPort();
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private JsonNode remoteJson(String repository, String commit, String path,
                                BooleanSupplier cancelled, boolean gated) throws Exception {
        URI uri = base.resolve(encodePath(repository) + "/resolve/" + commit + "/"
                + encodePath(path) + "?download=true");
        HttpResponse<byte[]> response = send(HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(30)).header("Accept", "application/json")
                .GET().build(), cancelled);
        if (response.statusCode() == 401 || response.statusCode() == 403) {
            throw mismatch(gated ? "门控仓库无法匿名验证量化清单" : "模型文件拒绝匿名访问");
        }
        if (response.statusCode() == 404) throw mismatch("模型缺少 " + path);
        requireStatus(response, "读取 Hugging Face " + path);
        return document(response.body());
    }

    private HttpResponse<byte[]> send(HttpRequest request, BooleanSupplier cancelled) throws Exception {
        HttpResponse<byte[]> response = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            requireActive(cancelled);
            response = transport.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.body().length > MAX_DOCUMENT_BYTES) {
                throw new IOException("Hugging Face 响应超过 8 MiB");
            }
            if (response.statusCode() != 429 && response.statusCode() < 500) return response;
            if (attempt < 2) waitRetry(response, attempt, cancelled);
        }
        return response;
    }

    private static void waitRetry(HttpResponse<?> response, int attempt,
                                  BooleanSupplier cancelled) throws InterruptedException {
        long fallback = 250L << attempt;
        long requested = response.headers().firstValue("Retry-After").flatMap(value -> {
            try { return java.util.Optional.of(Long.parseLong(value.strip()) * 1000L); }
            catch (RuntimeException invalid) { return java.util.Optional.empty(); }
        }).orElse(fallback);
        long remaining = Math.min(2_000L, Math.max(50L, requested));
        while (remaining > 0) {
            requireActive(cancelled);
            long slice = Math.min(100L, remaining);
            Thread.sleep(slice);
            remaining -= slice;
        }
    }

    private JsonNode document(byte[] body) throws IOException {
        JsonNode value = json.readTree(body);
        if (value == null) throw new IOException("Hugging Face 返回空 JSON");
        return value;
    }

    private static void requireStatus(HttpResponse<?> response, String operation) throws IOException {
        if (response.statusCode() == 200) return;
        if (response.statusCode() == 401 || response.statusCode() == 403) {
            throw new IOException(operation + "失败：匿名访问被拒绝");
        }
        if (response.statusCode() == 429) throw new IOException(operation + "失败：请求过于频繁");
        throw new IOException(operation + "失败，HTTP " + response.statusCode());
    }

    private static List<RemoteFile> files(JsonNode siblings) throws CatalogMismatchException {
        if (!siblings.isArray()) throw mismatch("仓库没有文件列表");
        ArrayList<RemoteFile> files = new ArrayList<>();
        for (JsonNode sibling : siblings) {
            String path = sibling.path("rfilename").asText("").strip();
            if (path.isBlank()) continue;
            validateRelative(path);
            long size = sibling.path("size").asLong(
                    sibling.path("lfs").path("size").asLong(-1));
            files.add(new RemoteFile(path, size));
        }
        return List.copyOf(files);
    }

    private static void validateFileSet(List<RemoteFile> files) throws CatalogMismatchException {
        boolean config = files.stream().anyMatch(file -> file.path().equals("config.json"));
        boolean weights = files.stream().anyMatch(file -> file.path().endsWith(".safetensors"));
        boolean tokenizer = files.stream().anyMatch(file -> {
            String name = file.path().substring(file.path().lastIndexOf('/') + 1);
            return name.startsWith("tokenizer") || name.endsWith(".model");
        });
        if (!config || !weights || !tokenizer) {
            throw mismatch("仓库缺少 config、tokenizer 或 Safetensors 权重");
        }
    }

    private static boolean shouldDownload(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        return name.endsWith(".safetensors") || name.endsWith(".json") || name.endsWith(".model")
                || name.endsWith(".tiktoken") || name.endsWith(".txt")
                || DOWNLOAD_METADATA.contains(name) || name.startsWith("tokenizer");
    }

    private static List<String> texts(JsonNode values) {
        if (!values.isArray()) return List.of();
        ArrayList<String> result = new ArrayList<>();
        values.forEach(value -> { if (value.isTextual() && !value.asText().isBlank()) {
            result.add(value.asText().strip());
        }});
        return List.copyOf(result);
    }

    private static JsonNode embeddedGatedManifest(JsonNode root) {
        JsonNode card = root.path("cardData");
        JsonNode value = card.path("deliverance-quantization");
        if (!value.isObject()) value = card.path("deliverance_quantization");
        return value.isObject() ? value : null;
    }

    private static int contextLength(JsonNode config) {
        int direct = firstPositive(config, "max_position_embeddings", "n_positions",
                "max_sequence_length", "seq_length");
        if (direct > 0) return direct;
        return config.path("text_config").isObject()
                ? firstPositive(config.path("text_config"), "max_position_embeddings", "n_positions",
                "max_sequence_length", "seq_length") : 0;
    }

    private static int firstPositive(JsonNode node, String... fields) {
        for (String field : fields) {
            long value = node.path(field).asLong(0);
            if (value > 0) return (int) Math.min(Integer.MAX_VALUE, value);
        }
        return 0;
    }

    private static Instant instant(String value) {
        try { return value == null || value.isBlank() ? Instant.EPOCH : Instant.parse(value); }
        catch (DateTimeParseException invalid) { return Instant.EPOCH; }
    }

    private static boolean gated(JsonNode value) {
        return value.isTextual() ? !"false".equalsIgnoreCase(value.asText())
                : value.asBoolean(false);
    }

    private static Set<String> normalizeTypes(Set<String> values) {
        if (values == null) return Set.of();
        return values.stream().filter(Objects::nonNull).map(String::strip)
                .filter(value -> !value.isBlank()).map(value -> value.toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static void requireSupportedRuntime(Set<String> supported) {
        if (supported.isEmpty()) throw new IllegalStateException("没有可用的 Deliverance 模型运行时");
    }

    private static boolean repositoryQuery(String value) {
        return value != null && value.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+");
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String encodePath(String value) {
        return String.join("/", java.util.Arrays.stream(value.split("/"))
                .map(HuggingFaceModelCatalogClient::encode).toList());
    }

    private static void validateRelative(String value) throws CatalogMismatchException {
        try {
            java.nio.file.Path path = java.nio.file.Path.of(value).normalize();
            if (path.isAbsolute() || path.startsWith("..")
                    || !path.toString().replace('\\', '/').equals(value)) {
                throw mismatch("仓库文件路径越界");
            }
        } catch (RuntimeException invalid) {
            throw mismatch("仓库文件路径无效");
        }
    }

    private static void requireActive(BooleanSupplier cancelled) throws InterruptedException {
        if (Thread.currentThread().isInterrupted()
                || cancelled != null && cancelled.getAsBoolean()) {
            throw new InterruptedException("Hugging Face 模型目录操作已取消");
        }
    }

    private static CachedSearch usableCache(CachedSearch value) {
        if (value == null) return null;
        Duration age = Duration.between(value.cachedAt(), Instant.now());
        return age.isNegative() || age.compareTo(Duration.ofHours(24)) <= 0 ? value : null;
    }

    private static String failureMessage(Throwable failure) {
        String message = failure == null ? "" : failure.getMessage();
        return message == null || message.isBlank()
                ? (failure == null ? "未知错误" : failure.getClass().getSimpleName())
                : message.strip();
    }

    private static CatalogMismatchException mismatch(String message) {
        return new CatalogMismatchException(message);
    }

    private record SearchKey(SearchRequest request, Set<String> supportedModelTypes) {
        private SearchKey {
            supportedModelTypes = Set.copyOf(supportedModelTypes);
        }
    }

    private record CachedSearch(
            SearchPage page, Instant cachedAt, int scannedCount, int candidateCount,
            Map<String, Integer> rejectionReasons) {
        private CachedSearch {
            rejectionReasons = Map.copyOf(rejectionReasons);
        }
    }

    private record SearchOutcome(
            SearchPage page, int scannedCount, int candidateCount,
            Map<String, Integer> rejectionReasons, boolean cacheable) {
        private SearchOutcome {
            rejectionReasons = Map.copyOf(rejectionReasons);
        }
    }

    private record Candidate(String repository, String declaredModelType) { }

    private record Verification(ModelSummary model, String rejection, String failure) {
        private static Verification accepted(ModelSummary model) {
            return new Verification(model, "", "");
        }

        private static Verification rejected(String reason) {
            return new Verification(null, reason == null ? "不兼容" : reason, "");
        }

        private static Verification failed(String reason) {
            return new Verification(null, "", reason == null ? "验证失败" : reason);
        }
    }

    private record RemoteFile(String path, long size) { }

    private static final class CatalogMismatchException extends IOException {
        private CatalogMismatchException(String message) { super(message); }
    }

    @FunctionalInterface
    interface Transport {
        HttpResponse<byte[]> send(HttpRequest request,
                                  HttpResponse.BodyHandler<byte[]> handler) throws Exception;
    }
}
