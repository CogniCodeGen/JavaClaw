package com.javaclaw.infrastructure.inference;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.platform.data.DataRoot;
import com.javaclaw.platform.data.SchemaInitializer;
import com.javaclaw.application.inference.InferenceAssetPreparationPort;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManagedInferenceAssetStoreTest {

    @TempDir Path temporary;
    private JdbcInferenceCatalog catalog;
    private ManagedInferenceAssetStore store;
    private HttpServer huggingFace;

    @BeforeEach
    void createStore() throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:assets-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        new SchemaInitializer(dataSource).initialize();
        catalog = new JdbcInferenceCatalog(new JdbcTemplate(dataSource),
                new DataSourceTransactionManager(dataSource), new ObjectMapper().findAndRegisterModules());
        DataRoot root = new DataRoot(temporary.resolve("data")).prepare();
        store = new ManagedInferenceAssetStore(root, catalog, HttpClient.newHttpClient(),
                new ObjectMapper().findAndRegisterModules());
    }

    @AfterEach
    void stopFixture() {
        if (huggingFace != null) huggingFace.stop(0);
    }

    @Test
    void importCopiesIntoContentAddressedCacheAndDeduplicatesDeterministically() throws Exception {
        Path source = modelDirectory("model");

        var metadata = store.inspectModel(source, () -> false);
        assertEquals("qwen2", metadata.modelType());
        assertEquals(List.of("Qwen2ForCausalLM"), metadata.architectures());

        var first = store.importLocalDirectory(source, ignored -> { }, () -> false);
        catalog.saveAsset(first);
        Files.writeString(source.resolve("config.json"), "changed after import");

        assertFalse(Files.readString(Path.of(first.location()).resolve("config.json"))
                .contains("changed after import"));

        Files.writeString(source.resolve("config.json"),
                "{\"model_type\":\"qwen2\",\"architectures\":[\"Qwen2ForCausalLM\"]}");
        var duplicate = store.importLocalDirectory(source, ignored -> { }, () -> false);
        assertEquals(first.id(), duplicate.id());
        assertEquals(first.contentSha256(), duplicate.contentSha256());
        assertEquals(first.location(), duplicate.location());
        assertEquals("qwen2", duplicate.modelType());

        Path cached = Path.of(first.location());
        deleteTree(cached);
        var repaired = store.importLocalDirectory(source, ignored -> { }, () -> false);
        assertEquals(first.id(), repaired.id());
        assertTrue(Files.isRegularFile(cached.resolve("model.safetensors")),
                "目录记录存在但缓存丢失时，重复导入应原子修复缓存");
        assertTrue(cached.startsWith(pluginModels().resolve("assets/sha256")));
    }

    @Test
    void metadataRebuildsCatalogAndMissingDirectoriesAreMarkedFailed() throws Exception {
        var asset = store.importLocalDirectory(modelDirectory("recover"), ignored -> { }, () -> false);
        catalog.saveAsset(asset);
        catalog.deleteAsset(asset.id());

        store = newStore();
        store.reconcileManagedAssets();
        var recovered = catalog.asset(asset.id()).orElseThrow();
        assertEquals(asset.id(), recovered.id());
        assertEquals(asset.contentSha256(), recovered.contentSha256());
        assertEquals(Path.of(asset.location()), Path.of(recovered.location()));

        catalog.deleteAsset(asset.id());
        Files.writeString(Path.of(recovered.location()).resolve("config.json"), "{}");
        store = newStore();
        store.reconcileManagedAssets();
        assertEquals(com.javaclaw.inference.api.InferenceModelAsset.State.FAILED,
                catalog.asset(asset.id()).orElseThrow().state(),
                "H2 记录缺失且清单对应文件损坏时仍应恢复为失败资产");

        deleteTree(Path.of(recovered.location()));
        store = newStore();
        store.reconcileManagedAssets();
        assertEquals(com.javaclaw.inference.api.InferenceModelAsset.State.FAILED,
                catalog.asset(asset.id()).orElseThrow().state());
        assertTrue(catalog.asset(asset.id()).orElseThrow().failure().contains("目录缺失"));
    }

    @Test
    void migratesLegacyInferenceAssetsAndPreservesIdentity() throws Exception {
        var asset = store.importLocalDirectory(modelDirectory("legacy"), ignored -> { }, () -> false);
        catalog.saveAsset(asset);
        Path canonical = Path.of(asset.location());
        Path legacy = temporary.resolve("data/inference/assets/sha256")
                .resolve(asset.contentSha256().substring(0, 2)).resolve(asset.contentSha256());
        Files.createDirectories(legacy.getParent());
        Files.move(canonical, legacy);
        var legacyRecord = new com.javaclaw.inference.api.InferenceModelAsset(
                asset.id(), asset.source(), asset.displayName(), asset.modelType(),
                asset.contentSha256(), legacy.toString(), asset.huggingFaceRepository(),
                asset.huggingFaceCommit(), asset.files(), asset.sizeBytes(), asset.state(),
                asset.failure(), asset.createdAt(), asset.artifactMetadata());
        catalog.saveAsset(legacyRecord);

        store = newStore();
        store.reconcileManagedAssets();

        var migrated = catalog.asset(asset.id()).orElseThrow();
        assertEquals(asset.id(), migrated.id());
        assertTrue(Path.of(migrated.location()).startsWith(pluginModels().resolve("assets/sha256")));
        assertTrue(Files.isRegularFile(Path.of(migrated.location()).resolve("model.safetensors")));
        assertFalse(Files.exists(legacy));
    }

    @Test
    void rejectsSymbolicLinksAndIncompleteModels() throws Exception {
        Path unidentified = temporary.resolve("unidentified");
        Files.createDirectories(unidentified);
        Files.writeString(unidentified.resolve("config.json"), "{}");
        Files.writeString(unidentified.resolve("tokenizer.json"), "{}");
        Files.write(unidentified.resolve("model.safetensors"), new byte[]{1});
        Exception unidentifiedFailure = assertThrows(Exception.class,
                () -> store.importLocalDirectory(unidentified, ignored -> { }, () -> false));
        assertTrue(unidentifiedFailure.getMessage().contains("model_type"));

        Path incomplete = temporary.resolve("incomplete");
        Files.createDirectories(incomplete);
        Files.writeString(incomplete.resolve("config.json"), "{\"model_type\":\"qwen2\"}");
        assertThrows(Exception.class,
                () -> store.importLocalDirectory(incomplete, ignored -> { }, () -> false));

        Path source = modelDirectory("linked");
        Path outside = temporary.resolve("outside.txt");
        Files.writeString(outside, "secret");
        try {
            Files.createSymbolicLink(source.resolve("link.txt"), outside);
        } catch (UnsupportedOperationException failure) {
            return;
        }
        Exception failure = assertThrows(Exception.class,
                () -> store.importLocalDirectory(source, ignored -> { }, () -> false));
        assertTrue(failure.getMessage().contains("符号链接") || failure.getMessage().contains("不允许"));
    }

    @Test
    void coldStartIntegrityDetectsChangesMarksFailedAndReimportRepairs() throws Exception {
        Path source = modelDirectory("integrity");
        var asset = store.importLocalDirectory(source, ignored -> { }, () -> false);
        catalog.saveAsset(asset);

        store.verifyForColdStart(asset, () -> false);
        Path cachedConfig = Path.of(asset.location()).resolve("config.json");
        Files.writeString(cachedConfig, "[]");
        Files.setLastModifiedTime(cachedConfig,
                FileTime.fromMillis(System.currentTimeMillis() + 2_000));

        assertThrows(IOException.class, () -> store.verifyForColdStart(asset, () -> false));
        assertEquals(com.javaclaw.inference.api.InferenceModelAsset.State.FAILED,
                catalog.asset(asset.id()).orElseThrow().state());

        var repaired = store.importLocalDirectory(source, ignored -> { }, () -> false);
        assertEquals(com.javaclaw.inference.api.InferenceModelAsset.State.READY, repaired.state());
        assertEquals("{\"model_type\":\"qwen2\",\"architectures\":[\"Qwen2ForCausalLM\"]}",
                Files.readString(Path.of(repaired.location()).resolve("config.json")));
        store.verifyForColdStart(repaired, () -> false);

        Files.writeString(Path.of(repaired.location()).resolve("unexpected.txt"), "extra");
        assertThrows(IOException.class, () -> store.verifyForColdStart(repaired, () -> false));
    }

    @Test
    void coldStartCancellationBeforeAndDuringHashPreservesReadyAssetState() throws Exception {
        Path source = modelDirectory("cancel-integrity");
        Files.write(source.resolve("model.safetensors"), new byte[3 * 1024 * 1024]);
        var asset = store.importLocalDirectory(source, ignored -> { }, () -> false);
        catalog.saveAsset(asset);

        try {
            assertThrows(IOException.class, () -> store.verifyForColdStart(asset, () -> true));
            assertEquals(com.javaclaw.inference.api.InferenceModelAsset.State.READY,
                    catalog.asset(asset.id()).orElseThrow().state());
        } finally {
            Thread.interrupted();
        }

        AtomicInteger checks = new AtomicInteger();
        try {
            assertThrows(IOException.class, () -> store.verifyForColdStart(
                    asset, () -> checks.incrementAndGet() >= 10));
            assertTrue(checks.get() >= 10, "取消必须发生在文件摘要阶段而非扫描前");
            assertEquals(com.javaclaw.inference.api.InferenceModelAsset.State.READY,
                    catalog.asset(asset.id()).orElseThrow().state());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void pinsHuggingFaceRevisionResumesAndVerifiesEveryDownloadedFile() throws Exception {
        byte[] config = "{\"model_type\":\"qwen2\"}".getBytes(StandardCharsets.UTF_8);
        byte[] tokenizer = "{\"tokenizer\":true}".getBytes(StandardCharsets.UTF_8);
        byte[] weights = new byte[]{1, 2, 3, 4, 5, 6};
        AtomicBoolean rangeSeen = new AtomicBoolean();
        AtomicBoolean blobsSeen = new AtomicBoolean();
        startHuggingFaceFixture(config, tokenizer, weights, false, false, rangeSeen, blobsSeen);
        store = localHuggingFaceStore();
        String commit = "a".repeat(40);
        Path partial = pluginModels().resolve("downloads/owner_model-" + commit
                + "/model.safetensors.part");
        Files.createDirectories(partial.getParent());
        Files.write(partial, new byte[]{1, 2});
        var request = new InferenceAssetPreparationPort.HuggingFaceRequest(
                "owner/model", "main", "token-value");

        var preview = store.previewHuggingFace(request, () -> false);
        var asset = store.downloadHuggingFace(request, ignored -> { }, () -> false);

        assertEquals(commit, preview.commit());
        assertEquals("qwen2", preview.modelType());
        assertEquals("apache-2.0", preview.license());
        assertEquals(commit, asset.huggingFaceCommit());
        assertEquals(3, asset.files().size());
        assertTrue(rangeSeen.get(), "已有 .part 文件必须使用 Range 断点续传");
        assertTrue(blobsSeen.get(), "revision 查询必须请求 blob 长度与可用 checksum");
        assertTrue(Files.isRegularFile(Path.of(asset.location()).resolve("model.safetensors")));
        assertFalse(Files.exists(partial.getParent()), "晋升后应清理可恢复下载目录");
    }

    @Test
    void rejectsGatedModelsWithoutTokenAndChecksumMismatchWithoutPromotion() throws Exception {
        byte[] config = "{\"model_type\":\"qwen2\"}".getBytes(StandardCharsets.UTF_8);
        byte[] tokenizer = "{}".getBytes(StandardCharsets.UTF_8);
        byte[] weights = new byte[]{1, 2, 3, 4};
        startHuggingFaceFixture(config, tokenizer, weights, true, false,
                new AtomicBoolean(), new AtomicBoolean());
        store = localHuggingFaceStore();
        var gated = new InferenceAssetPreparationPort.HuggingFaceRequest(
                "owner/model", "main", "");
        assertThrows(IOException.class,
                () -> store.downloadHuggingFace(gated, ignored -> { }, () -> false));

        huggingFace.stop(0);
        huggingFace = null;
        startHuggingFaceFixture(config, tokenizer, weights, false, true,
                new AtomicBoolean(), new AtomicBoolean());
        store = localHuggingFaceStore();
        var publicModel = new InferenceAssetPreparationPort.HuggingFaceRequest(
                "owner/model", "main", "");
        assertThrows(IOException.class,
                () -> store.downloadHuggingFace(publicModel, ignored -> { }, () -> false));
        assertTrue(catalog.assets().isEmpty());
        Path promoted = pluginModels().resolve("assets/sha256");
        if (Files.exists(promoted)) {
            try (var files = Files.walk(promoted)) {
                assertFalse(files.anyMatch(Files::isRegularFile));
            }
        }
    }

    private ManagedInferenceAssetStore localHuggingFaceStore() throws Exception {
        URI base = URI.create("http://127.0.0.1:" + huggingFace.getAddress().getPort() + "/");
        return new ManagedInferenceAssetStore(new DataRoot(temporary.resolve("data")), catalog,
                HttpClient.newHttpClient(), new ObjectMapper().findAndRegisterModules(), base);
    }

    private ManagedInferenceAssetStore newStore() throws Exception {
        return new ManagedInferenceAssetStore(new DataRoot(temporary.resolve("data")), catalog,
                HttpClient.newHttpClient(), new ObjectMapper().findAndRegisterModules());
    }

    private Path pluginModels() {
        return temporary.resolve("plugins/builtin-deliverance/data/models")
                .toAbsolutePath().normalize();
    }

    private void startHuggingFaceFixture(
            byte[] config, byte[] tokenizer, byte[] weights, boolean gated, boolean corruptWeights,
            AtomicBoolean rangeSeen, AtomicBoolean blobsSeen) throws Exception {
        String commit = "a".repeat(40);
        Map<String, byte[]> files = Map.of(
                "config.json", config, "tokenizer.json", tokenizer, "model.safetensors", weights);
        huggingFace = HttpServer.create(
                new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        huggingFace.createContext("/api/models/owner/model/revision/main", exchange -> {
            blobsSeen.set("blobs=true".equals(exchange.getRequestURI().getQuery()));
            String siblings = files.entrySet().stream().sorted(Map.Entry.comparingByKey()).map(entry ->
                    "{\"rfilename\":\"" + entry.getKey() + "\",\"size\":" + entry.getValue().length
                            + ",\"lfs\":{\"size\":" + entry.getValue().length + ",\"sha256\":\""
                            + sha256(entry.getValue()) + "\"}}")
                    .collect(java.util.stream.Collectors.joining(","));
            json(exchange, 200, "{\"sha\":\"" + commit + "\",\"gated\":" + gated
                    + ",\"cardData\":{\"license\":\"apache-2.0\"},\"siblings\":["
                    + siblings + "]}");
        });
        String prefix = "/owner/model/resolve/" + commit + "/";
        huggingFace.createContext(prefix, exchange -> {
            String name = exchange.getRequestURI().getPath().substring(prefix.length());
            byte[] source = files.get(name);
            if (source == null) { json(exchange, 404, "{}"); return; }
            if (corruptWeights && "model.safetensors".equals(name)) source = new byte[]{9, 9, 9, 9};
            String range = exchange.getRequestHeaders().getFirst("Range");
            int offset = 0;
            int status = 200;
            if (range != null && range.matches("bytes=\\d+-")) {
                offset = Integer.parseInt(range.substring(6, range.length() - 1));
                rangeSeen.set(true);
                status = 206;
            }
            byte[] body = java.util.Arrays.copyOfRange(source, Math.min(offset, source.length), source.length);
            exchange.sendResponseHeaders(status, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        huggingFace.start();
    }

    private static void json(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
    }

    private static String sha256(byte[] value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private Path modelDirectory(String name) throws Exception {
        Path source = temporary.resolve(name);
        Files.createDirectories(source);
        Files.writeString(source.resolve("config.json"),
                "{\"model_type\":\"qwen2\",\"architectures\":[\"Qwen2ForCausalLM\"]}");
        Files.writeString(source.resolve("tokenizer.json"), "{}");
        Files.write(source.resolve("model.safetensors"), new byte[]{1, 2, 3, 4});
        return source;
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }
}
