package com.javaclaw.integration;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.application.inference.InferenceAssetPreparationPort;
import com.javaclaw.application.inference.InferenceCatalogPort;
import com.javaclaw.application.inference.InferenceManagementApplicationService.ProfileDraft;
import com.javaclaw.application.inference.InferenceManagementUseCase;
import com.javaclaw.application.inference.InferenceSecretPort;
import com.javaclaw.application.serviceplugin.ServicePluginManagementApplicationService.ResourceConfiguration;
import com.javaclaw.application.serviceplugin.ServicePluginManagementApplicationService.StartupPolicy;
import com.javaclaw.config.CredentialCipher;
import com.javaclaw.inference.api.InferenceChatRequest;
import com.javaclaw.inference.api.InferenceEmbeddingRequest;
import com.javaclaw.inference.api.InferenceMessage;
import com.javaclaw.inference.api.InferenceModelAsset;
import com.javaclaw.inference.api.InferenceModelProfile;
import com.javaclaw.inference.api.InferenceRequestPriority;
import com.javaclaw.inference.api.InferenceRuntimeManifest;
import com.javaclaw.inference.api.InferenceStreamEvent;
import com.javaclaw.inference.api.InferenceTool;
import com.javaclaw.inference.api.InferenceToolChoice;
import com.javaclaw.infrastructure.inference.DeliveranceRuntimeManager;
import com.javaclaw.infrastructure.inference.JdbcInferenceCatalog;
import com.javaclaw.infrastructure.inference.ManagedInferenceAssetStore;
import com.javaclaw.infrastructure.inference.serviceplugin.DeliveranceServicePluginGateway;
import com.javaclaw.infrastructure.serviceplugin.ServicePluginDefinition;
import com.javaclaw.infrastructure.serviceplugin.ServicePluginProcessManager;
import com.javaclaw.platform.data.DataRoot;
import com.javaclaw.platform.data.SchemaInitializer;
import com.javaclaw.platform.execution.ManagedTaskExecutor;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.InputStream;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Explicit networked gate for the real host/downloader/runner/Deliverance business path. */
class DeliveranceBusinessIT {
    private static final String RUNTIME_ID = DeliveranceRuntimeManager.BUILTIN_RUNTIME_ID;
    private static final UUID GENERATION_PROFILE = UUID.nameUUIDFromBytes(
            "deliverance-business-it:generation".getBytes(StandardCharsets.UTF_8));
    private static final UUID EMBEDDING_PROFILE = UUID.nameUUIDFromBytes(
            "deliverance-business-it:embedding".getBytes(StandardCharsets.UTF_8));
    private static final String GENERATION_ALIAS = "qwen3-business-it";
    private static final String EMBEDDING_ALIAS = "qwen3-embedding-business-it";

    @Test
    void completesRealModelsBindingsStreamingOpenAiLimitsAndRestartRecovery() throws Exception {
        Path cache = requiredAbsoluteDirectory("deliverance.it.cache-dir");
        Path pluginJar = requiredRegularFile("deliverance.it.plugin-jar");
        String generationRepository = required("deliverance.it.generation-repository");
        String generationRevision = required("deliverance.it.generation-revision");
        String embeddingRepository = required("deliverance.it.embedding-repository");
        String embeddingRevision = required("deliverance.it.embedding-revision");

        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        JsonNode descriptor = pluginDescriptor(pluginJar, json);
        String pluginHash = sha256(pluginJar);
        DataRoot dataRoot = new DataRoot(cache.resolve("javaclaw-data")).prepare();
        JdbcInferenceCatalog catalog = catalog(cache, json);
        ManagedInferenceAssetStore assets = new ManagedInferenceAssetStore(
                dataRoot, catalog, HttpClient.newBuilder().followRedirects(
                HttpClient.Redirect.NORMAL).build(), json);

        try (ManagedTaskExecutor tasks = new ManagedTaskExecutor();
             ServicePluginProcessManager processes = new ServicePluginProcessManager(
                     dataRoot, tasks, json)) {
            processes.init();
            processes.register(baseDefinition(pluginJar, pluginHash, descriptor, cache));

            DeliveranceServicePluginGateway gateway = new DeliveranceServicePluginGateway(
                    catalog, assets, processes, json, new PassthroughCipher());
            InferenceCatalogPort.RuntimeInstallation runtime = installRuntime(
                    catalog, pluginJar, pluginHash, descriptor, json);
            gateway.registerRuntime(runtime);
            DeliveranceRuntimeManager runtimes = new DeliveranceRuntimeManager(
                    dataRoot, catalog, gateway, tasks, json);
            InferenceManagementUseCase management = new InferenceManagementUseCase(
                    catalog, assets, assets, runtimes, InferenceSecretPort.PASSTHROUGH, gateway);

            InferenceModelAsset generationAsset = acquire(
                    management, catalog, assets, generationRepository, generationRevision);
            InferenceModelAsset embeddingAsset = acquire(
                    management, catalog, assets, embeddingRepository, embeddingRevision);
            assertEquals("qwen3", generationAsset.modelType());
            assertEquals("qwen3", embeddingAsset.modelType());

            InferenceModelProfile generation = saveAndVerifyProfile(management, processes,
                    new ProfileDraft(
                    GENERATION_PROFILE, "Qwen3 0.6B business IT",
                    InferenceModelProfile.Kind.GENERATION, generationAsset.id(), RUNTIME_ID,
                    loadParameters(false), Map.of("maxTokens", 32, "temperature", 0.0,
                    "seed", 42, "enableThinking", true), 2048));
            InferenceModelProfile embedding = saveAndVerifyProfile(management, processes,
                    new ProfileDraft(
                    EMBEDDING_PROFILE, "Qwen3 Embedding 0.6B business IT",
                    InferenceModelProfile.Kind.EMBEDDING, embeddingAsset.id(), RUNTIME_ID,
                    loadParameters(true), Map.of(), 512));
            assertEquals(1024, embedding.embeddingDimensions());

            management.saveBindings("deliverance-business-it", Map.of(
                    InferenceCatalogPort.ModelTier.HIGH, generation.id(),
                    InferenceCatalogPort.ModelTier.NORMAL, generation.id(),
                    InferenceCatalogPort.ModelTier.LIGHT, generation.id(),
                    InferenceCatalogPort.ModelTier.EMBEDDING, embedding.id()));
            assertEquals(4, catalog.bindings("deliverance-business-it").size());
            management.publish(GENERATION_ALIAS, generation.id());
            management.publish(EMBEDDING_ALIAS, embedding.id());

            verifyInternalStreaming(gateway, generation);
            verifyToolCalling(gateway, generation);
            verifyCancellation(gateway, generation);
            verifyEmbedding(gateway, embedding);

            var fullKey = management.createApiKey("Deliverance business IT", Set.of(
                            InferenceCatalogPort.ApiScope.MODELS_READ,
                            InferenceCatalogPort.ApiScope.CHAT_INVOKE,
                            InferenceCatalogPort.ApiScope.EMBEDDINGS_INVOKE),
                    Set.of(GENERATION_ALIAS, EMBEDDING_ALIAS), 120, 100_000, 4);
            var limitedKey = management.createApiKey("Deliverance RPM gate", Set.of(
                            InferenceCatalogPort.ApiScope.MODELS_READ),
                    Set.of(GENERATION_ALIAS), 1, 1_000, 1);
            int port = freePort();
            management.saveGateway(new InferenceCatalogPort.GatewayConfiguration(
                    true, "127.0.0.1", port, false, false, "", "",
                    8L * 1024 * 1024, 180, Instant.now()));
            String endpoint = management.gatewayEndpoint();
            assertEquals("http://127.0.0.1:" + port + "/v1", endpoint);
            verifyOpenAiEndpoint(json, endpoint, fullKey.secret(), limitedKey.secret());

            management.stopProfile(generation.id());
            management.stopProfile(embedding.id());
            assertTrue(gateway.status(generation.id()).isEmpty());
            assertTrue(gateway.status(embedding.id()).isEmpty());

            processes.stop(DeliveranceServicePluginGateway.PLUGIN_ID);
            processes.start(DeliveranceServicePluginGateway.PLUGIN_ID);
            gateway.restorePublishedProfiles();
            assertEquals(200, authorizedGet(endpoint + "/models", fullKey.secret()).statusCode());
        }

        assertFalse(Files.exists(dataRoot.path().resolve(
                "run/service-plugins/" + DeliveranceServicePluginGateway.PLUGIN_ID + ".json")),
                "关闭后不得残留活动 PID 租约");
    }

    private static void verifyInternalStreaming(
            DeliveranceServicePluginGateway gateway, InferenceModelProfile profile) throws Exception {
        List<InferenceStreamEvent> events = new ArrayList<>();
        String requestId = UUID.randomUUID().toString();
        InferenceChatRequest request = new InferenceChatRequest(requestId, profile.id(),
                List.of(new InferenceMessage(InferenceMessage.Role.USER,
                        "只回复 OK", "", "", List.of())),
                List.of(), Map.of("enableThinking", false, "temperature", 0.0,
                        "maxTokens", 8, "seed", 42), InferenceToolChoice.none(), true,
                Duration.ofMinutes(5), InferenceRequestPriority.INTERNAL);

        var session = gateway.streamChat(request, events::add);
        var response = session.completion().get(5, TimeUnit.MINUTES);
        assertEquals(requestId, response.requestId());
        assertFalse(response.content().isBlank(), () -> "缺少推理正文: " + response);
        assertTrue(response.usage().promptTokens() > 0);
        assertTrue(response.usage().completionTokens() > 0);
        assertTrue(events.stream().anyMatch(event ->
                event.type() == InferenceStreamEvent.Type.CONTENT_DELTA));
        assertTrue(events.stream().anyMatch(event ->
                event.type() == InferenceStreamEvent.Type.COMPLETE));
    }

    private static void verifyToolCalling(
            DeliveranceServicePluginGateway gateway, InferenceModelProfile profile) throws Exception {
        InferenceTool weather = new InferenceTool("weather", "读取指定城市天气",
                Map.of("type", "object", "properties", Map.of("city",
                        Map.of("type", "string")), "required", List.of("city")));
        InferenceChatRequest request = new InferenceChatRequest(UUID.randomUUID().toString(), profile.id(),
                List.of(new InferenceMessage(InferenceMessage.Role.USER,
                        "请调用 weather 工具查询北京天气，不要直接回答。", "", "", List.of())),
                List.of(weather), Map.of("enableThinking", false, "temperature", 0.0,
                        "maxTokens", 64, "seed", 42), InferenceToolChoice.auto(), true,
                Duration.ofMinutes(5), InferenceRequestPriority.INTERNAL);

        var response = gateway.streamChat(request, ignored -> { })
                .completion().get(5, TimeUnit.MINUTES);
        assertFalse(response.toolCalls().isEmpty(), () -> "缺少工具调用: " + response);
        assertEquals("weather", response.toolCalls().getFirst().name());
        assertTrue(response.usage().completionTokens() > 0);
    }

    private static InferenceModelProfile saveAndVerifyProfile(
            InferenceManagementUseCase management,
            ServicePluginProcessManager processes,
            ProfileDraft draft) throws Exception {
        try {
            return management.saveAndVerifyProfile(draft, () -> false);
        } catch (Exception failure) {
            List<String> logs = processes.list().stream()
                    .filter(plugin -> DeliveranceServicePluginGateway.PLUGIN_ID.equals(plugin.id()))
                    .findFirst().map(plugin -> plugin.recentLogs()).orElse(List.of());
            throw new IllegalStateException("Deliverance probe failed; recent Runner logs:\n"
                    + String.join("\n", logs), failure);
        }
    }

    private static void verifyCancellation(
            DeliveranceServicePluginGateway gateway, InferenceModelProfile profile) throws Exception {
        InferenceChatRequest request = new InferenceChatRequest(UUID.randomUUID().toString(),
                profile.id(), List.of(new InferenceMessage(InferenceMessage.Role.USER,
                "从一开始逐项列出尽可能多的素数并解释过程。", "", "", List.of())),
                List.of(), Map.of("maxTokens", 2048, "temperature", 0.0),
                InferenceToolChoice.none(), true, Duration.ofMinutes(5),
                InferenceRequestPriority.INTERNAL);
        var session = gateway.streamChat(request, ignored -> { });
        assertTrue(session.cancel(), "活动推理必须接受取消请求");
        assertThrows(Exception.class, () -> session.completion().get(30, TimeUnit.SECONDS));
    }

    private static void verifyEmbedding(
            DeliveranceServicePluginGateway gateway, InferenceModelProfile profile) throws Exception {
        String query = "Instruct: Given a web search query, retrieve relevant passages that answer the query\n"
                + "Query: What is the capital of China?";
        var response = gateway.embeddings(new InferenceEmbeddingRequest(
                UUID.randomUUID().toString(), profile.id(), List.of(query,
                        "The capital of China is Beijing.",
                        "Photosynthesis converts sunlight into chemical energy."),
                Duration.ofMinutes(3), InferenceRequestPriority.INTERNAL));
        assertEquals(1024, response.dimensions());
        assertEquals(3, response.embeddings().size());
        assertTrue(response.usage().promptTokens() > 0);
        response.embeddings().forEach(vector -> {
            assertEquals(1024, vector.length);
            for (float value : vector) assertTrue(Float.isFinite(value));
            assertEquals(1.0, l2Norm(vector), 0.02, "向量必须经过 L2 归一化");
        });
        assertTrue(cosine(response.embeddings().get(0), response.embeddings().get(1))
                        > cosine(response.embeddings().get(0), response.embeddings().get(2)),
                "Qwen3 向量必须把检索问题与相关文档排在无关文档之前");
    }

    private static double l2Norm(float[] vector) {
        double sum = 0;
        for (float value : vector) sum += value * value;
        return Math.sqrt(sum);
    }

    private static double cosine(float[] left, float[] right) {
        double dot = 0;
        for (int index = 0; index < left.length; index++) dot += left[index] * right[index];
        return dot / (l2Norm(left) * l2Norm(right));
    }

    private static void verifyOpenAiEndpoint(
            ObjectMapper json, String endpoint, String fullKey, String limitedKey) throws Exception {
        assertEquals(401, HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                        URI.create(endpoint + "/models")).GET().build(),
                HttpResponse.BodyHandlers.ofString()).statusCode());
        HttpResponse<String> models = authorizedGet(endpoint + "/models", fullKey);
        assertEquals(200, models.statusCode());
        assertTrue(models.body().contains(GENERATION_ALIAS));
        assertTrue(models.body().contains(EMBEDDING_ALIAS));

        HttpResponse<String> firstLimited = authorizedGet(endpoint + "/models", limitedKey);
        HttpResponse<String> secondLimited = authorizedGet(endpoint + "/models", limitedKey);
        assertEquals(200, firstLimited.statusCode());
        assertEquals(429, secondLimited.statusCode());

        String chatBody = json.writeValueAsString(Map.of(
                "model", GENERATION_ALIAS,
                "messages", List.of(Map.of("role", "user", "content", "只回复 OK")),
                "max_tokens", 8, "temperature", 0, "stream", true));
        HttpResponse<String> chat = authorizedPost(
                endpoint + "/chat/completions", fullKey, chatBody);
        assertEquals(200, chat.statusCode());
        assertTrue(chat.body().contains("data:"));
        assertTrue(chat.body().contains("[DONE]"));

        String embeddingBody = json.writeValueAsString(Map.of(
                "model", EMBEDDING_ALIAS, "input", List.of("hello", "world")));
        HttpResponse<String> embeddings = authorizedPost(
                endpoint + "/embeddings", fullKey, embeddingBody);
        assertEquals(200, embeddings.statusCode());
        JsonNode payload = json.readTree(embeddings.body());
        assertEquals(2, payload.path("data").size());
        assertEquals(1024, payload.path("data").get(0).path("embedding").size());
    }

    private static HttpResponse<String> authorizedGet(String uri, String key) throws Exception {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build().send(
                HttpRequest.newBuilder(URI.create(uri)).timeout(Duration.ofMinutes(3))
                        .header("Authorization", "Bearer " + key).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> authorizedPost(
            String uri, String key, String body) throws Exception {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build().send(
                HttpRequest.newBuilder(URI.create(uri)).timeout(Duration.ofMinutes(5))
                        .header("Authorization", "Bearer " + key)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static InferenceModelAsset acquire(
            InferenceManagementUseCase management,
            InferenceCatalogPort catalog,
            ManagedInferenceAssetStore assets,
            String repository,
            String revision) throws Exception {
        var existing = catalog.assets().stream().filter(asset ->
                        asset.state() == InferenceModelAsset.State.READY
                                && repository.equals(asset.huggingFaceRepository())
                                && revision.equalsIgnoreCase(asset.huggingFaceCommit()))
                .findFirst();
        if (existing.isPresent()) {
            try {
                assets.verifyForColdStart(existing.get(), () -> false);
                return existing.get();
            } catch (Exception corruptedCache) {
                // The production downloader performs a full checksum-verified repair below.
            }
        }
        return management.downloadHuggingFace(
                new InferenceAssetPreparationPort.HuggingFaceRequest(repository, revision, ""),
                ignored -> { }, () -> false);
    }

    private static Map<String, Object> loadParameters(boolean embedding) {
        java.util.LinkedHashMap<String, Object> values = new java.util.LinkedHashMap<>();
        values.put("jvmHeapMiB", 4096);
        values.put("workerThreads", 2);
        values.put("tensorBackend", "auto");
        values.put("workingMemoryType", "F32");
        values.put("workingQuantType", "I8");
        values.put("maxBatchSize", embedding ? 8 : 1);
        values.put("kvCacheMaxEntries", embedding ? 0 : 1000);
        if (embedding) values.put("pooling", "AUTO");
        return Map.copyOf(values);
    }

    private static ServicePluginDefinition baseDefinition(
            Path pluginJar, String hash, JsonNode descriptor, Path cache) {
        JsonNode service = descriptor.path("service");
        Map<String, String> config = Map.of(
                "maxGenerationResident", "1",
                "maxEmbeddingResident", "1",
                "external.enabled", "false",
                "external.tls", "false",
                "external.apiKeyPolicies", "[]",
                "inference.publishedCatalog", "{\"profiles\":[],\"aliases\":{}}");
        return new ServicePluginDefinition(
                DeliveranceServicePluginGateway.PLUGIN_ID,
                descriptor.path("name").asText(), descriptor.path("version").asText(),
                service.path("apiVersion").asText(), service.path("mainClass").asText(),
                "Unsigned local development build", false, hash, pluginJar,
                cache.resolve("plugin-data"), StartupPolicy.MANUAL,
                new ResourceConfiguration(4096, 4096, 4, 64, 256), List.of(), true,
                Set.of(), config, true, descriptor.path("description").asText(),
                descriptor.path("configurationSchema").toString(), null, null,
                Map.of("openai", Set.of("models", "chat", "embeddings", "sse")), true);
    }

    private static InferenceCatalogPort.RuntimeInstallation installRuntime(
            InferenceCatalogPort catalog,
            Path pluginJar,
            String hash,
            JsonNode descriptor,
            ObjectMapper json) throws Exception {
        JsonNode inference = descriptor.path("inference");
        Set<String> capabilities = new LinkedHashSet<>();
        inference.path("capabilities").forEach(value -> capabilities.add(value.asText()));
        Map<String, Object> parameterSchema = json.convertValue(
                inference.path("parameterSchema"), new TypeReference<>() { });
        InferenceRuntimeManifest manifest = new InferenceRuntimeManifest(
                RUNTIME_ID, inference.path("engine").asText(),
                inference.path("engineVersion").asText(), inference.path("adapterVersion").asText(),
                new InferenceRuntimeManifest.ProtocolVersion(
                        inference.path("protocol").path("major").asInt(),
                        inference.path("protocol").path("minor").asInt()),
                platform(), architecture(), 25, Set.copyOf(capabilities), parameterSchema,
                List.of(new InferenceRuntimeManifest.RuntimeFile(
                        "deliverance.jar", hash, Files.size(pluginJar))), true);
        catalog.saveRuntime(new InferenceCatalogPort.RuntimeInstallation(
                manifest, pluginJar.toString(), InferenceCatalogPort.RuntimeState.INSTALLED,
                false, Instant.now()));
        catalog.setActiveRuntime(manifest.engine(), manifest.runtimeId());
        return catalog.runtime(RUNTIME_ID).orElseThrow();
    }

    private static JdbcInferenceCatalog catalog(Path cache, ObjectMapper json) {
        String database = cache.resolve("catalog/javaclaw-deliverance-it")
                .toAbsolutePath().normalize().toString();
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:file:" + database + ";DB_CLOSE_ON_EXIT=FALSE", "sa", "");
        new SchemaInitializer(dataSource).initialize();
        return new JdbcInferenceCatalog(new JdbcTemplate(dataSource),
                new DataSourceTransactionManager(dataSource), json);
    }

    private static JsonNode pluginDescriptor(Path jar, ObjectMapper json) throws Exception {
        try (JarFile plugin = new JarFile(jar.toFile())) {
            var entry = plugin.getJarEntry("plugin.json");
            assertNotNull(entry, "Deliverance JAR must contain plugin.json");
            try (InputStream input = plugin.getInputStream(entry)) {
                return json.readTree(input);
            }
        }
    }

    private static Path requiredAbsoluteDirectory(String property) throws Exception {
        String raw = required(property);
        Path supplied = Path.of(raw);
        if (!supplied.isAbsolute()) throw new IllegalArgumentException(property + " 必须是绝对路径");
        Path path = supplied.normalize();
        Files.createDirectories(path);
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
            throw new IllegalArgumentException(property + " 不是安全的普通目录");
        }
        Files.createDirectories(path.resolve("catalog"));
        return path;
    }

    private static Path requiredRegularFile(String property) {
        Path path = Path.of(required(property)).toAbsolutePath().normalize();
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
            throw new IllegalArgumentException(property + " 不是普通文件: " + path);
        }
        return path;
    }

    private static String required(String property) {
        String value = System.getProperty(property, "").strip();
        if (value.isEmpty()) throw new IllegalArgumentException("缺少 " + property);
        return value;
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        }
    }

    private static String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[1024 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) if (read > 0) digest.update(buffer, 0, read);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String platform() {
        String value = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        if (value.contains("mac")) return "macos";
        if (value.contains("win")) return "windows";
        if (value.contains("linux")) return "linux";
        return value.replaceAll("[^a-z0-9]", "");
    }

    private static String architecture() {
        return switch (System.getProperty("os.arch", "").toLowerCase(java.util.Locale.ROOT)) {
            case "aarch64", "arm64" -> "arm64";
            case "amd64", "x86_64", "x64" -> "x64";
            default -> System.getProperty("os.arch", "").toLowerCase(java.util.Locale.ROOT)
                    .replaceAll("[^a-z0-9]", "");
        };
    }

    private static final class PassthroughCipher implements CredentialCipher {
        @Override public String encrypt(String value) { return value == null ? "" : value; }
        @Override public String decrypt(String value) { return value == null ? "" : value; }
        @Override public boolean isEncrypted(String value) { return false; }
        @Override public void warmUp() { }
    }
}
