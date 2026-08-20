package com.javaclaw.plugins.deliverance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 使用调用方提供的固定哈希小模型验证真实 Deliverance 0.0.12 推理路径。 */
class DeliveranceRealModelIT {

    private final ObjectMapper json = new ObjectMapper().registerModule(new Jdk8Module());

    @Test
    void generatesStreamsMapsToolsAndReasoningWithExactUsage() throws Exception {
        Path model = verifiedModel("generation-model", "generation-sha256");
        Protocol.StartupConfig config = new Protocol.StartupConfig(
                "deliverance-real-it", model.toString(), "GENERATION", "real-generation",
                2048, 0, Map.of("tensorBackend", "auto", "workerThreads", 2),
                Map.of("maxTokens", 16, "temperature", 0.0), false);

        List<Protocol.StreamEvent> events = new ArrayList<>();
        Protocol.Tool weather = new Protocol.Tool("weather", "读取指定城市天气",
                Map.of("type", "object", "properties", Map.of("city",
                        Map.of("type", "string")), "required", List.of("city")));
        Protocol.ChatRequest request = new Protocol.ChatRequest(UUID.randomUUID().toString(),
                List.of(new Protocol.Message("USER",
                        "请简短回答：北京天气需要调用哪个工具？", "", "", List.of())),
                List.of(weather), Map.of("enableThinking", true),
                new Protocol.ToolChoice("AUTO", ""), true, true);

        try (DeliveranceEngine engine = new DeliveranceEngine(config, json)) {
            assertTrue(engine.capabilities().containsAll(SetHolder.GENERATION));
            AtomicBoolean cancelled = new AtomicBoolean();
            Protocol.ChatResponse response = engine.chat(
                    request, cancelled::get, events::add, 0);

            assertEquals(request.requestId(), response.requestId());
            assertNotNull(response.content());
            assertFalse(response.reasoningContent().isBlank(), "真实模型必须返回推理内容");
            assertFalse(response.toolCalls().isEmpty(), "真实模型必须产生工具调用");
            assertTrue(response.usage().promptTokens() > 0);
            assertTrue(response.usage().completionTokens() > 0);
            assertFalse(events.isEmpty(), "真实流式推理必须产生增量事件");
            assertTrue(events.stream().allMatch(event -> event.requestId().equals(request.requestId())));
            assertTrue(events.stream().allMatch(event ->
                    event.type().equals("CONTENT_DELTA") || event.type().equals("REASONING_DELTA")));
            assertTrue(events.stream().anyMatch(event -> event.type().equals("REASONING_DELTA")));
            for (Protocol.ToolCall call : response.toolCalls()) {
                assertTrue(json.readTree(call.argumentsJson()).isObject());
            }
        }
    }

    @Test
    void embedsBatchWithConfiguredDimensionsAndExactUsage() throws Exception {
        Path model = verifiedModel("embedding-model", "embedding-sha256");
        int dimensions = Integer.parseInt(required("embedding-dimensions"));
        Protocol.StartupConfig config = new Protocol.StartupConfig(
                "deliverance-real-it", model.toString(), "EMBEDDING", "real-embedding",
                2048, dimensions, Map.of("tensorBackend", "auto", "workerThreads", 2), Map.of(), false);

        try (DeliveranceEngine engine = new DeliveranceEngine(config, json)) {
            assertTrue(engine.capabilities().containsAll(SetHolder.EMBEDDING));
            Protocol.EmbeddingResponse response = engine.embeddings(
                    new Protocol.EmbeddingRequest(UUID.randomUUID().toString(), List.of("你好", "JavaClaw")),
                    () -> false);

            assertEquals(2, response.embeddings().size());
            assertEquals(dimensions, response.dimensions());
            assertTrue(response.usage().promptTokens() > 0);
            assertEquals(0, response.usage().completionTokens());
            response.embeddings().forEach(vector -> {
                assertEquals(dimensions, vector.length);
                for (float value : vector) assertFalse(Float.isNaN(value) || Float.isInfinite(value));
            });
        }
    }

    private static Path verifiedModel(String pathProperty, String hashProperty) throws Exception {
        Path root = Path.of(required(pathProperty)).toAbsolutePath().normalize();
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(root)) {
            throw new IllegalArgumentException("真实模型目录不可用: " + root);
        }
        String expected = required(hashProperty).toLowerCase(java.util.Locale.ROOT);
        if (!expected.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("模型目录摘要格式无效");
        assertEquals(expected, directoryDigest(root), "真实模型夹具内容与固定摘要不一致");
        return root;
    }

    private static String directoryDigest(Path root) throws Exception {
        MessageDigest tree = MessageDigest.getInstance("SHA-256");
        List<Path> files;
        try (var paths = Files.walk(root)) {
            files = paths.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .sorted(Comparator.comparing(path -> relative(root, path))).toList();
        }
        if (files.isEmpty()) throw new IllegalArgumentException("真实模型目录为空");
        for (Path file : files) {
            if (Files.isSymbolicLink(file)) throw new IllegalArgumentException("模型夹具包含符号链接");
            String relative = relative(root, file);
            long size = Files.size(file);
            tree.update(relative.getBytes(StandardCharsets.UTF_8));
            tree.update((byte) 0);
            tree.update(ByteBuffer.allocate(Long.BYTES).putLong(size).array());
            tree.update(HexFormat.of().parseHex(fileDigest(file)));
        }
        return HexFormat.of().formatHex(tree.digest());
    }

    private static String fileDigest(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[1024 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) if (read > 0) digest.update(buffer, 0, read);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String relative(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }

    private static String required(String suffix) {
        String value = System.getProperty("deliverance.it." + suffix, "").strip();
        if (value.isEmpty()) throw new IllegalArgumentException("缺少 deliverance.it." + suffix);
        return value;
    }

    private static final class SetHolder {
        private static final java.util.Set<String> GENERATION = java.util.Set.of(
                "chat", "streaming", "reasoning", "tools", "exact_usage", "cancellation");
        private static final java.util.Set<String> EMBEDDING = java.util.Set.of(
                "embeddings", "exact_usage", "cancellation");
        private SetHolder() { }
    }
}
