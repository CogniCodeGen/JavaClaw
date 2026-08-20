package com.javaclaw.infrastructure.inference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.application.inference.InferenceModelMetadataPort.ModelMetadata;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/** 只读取小型 Hugging Face config.json，不遍历目录或接触权重内容。 */
final class InferenceModelMetadataReader {
    private static final long MAX_CONFIG_BYTES = 4L * 1024 * 1024;

    private final ObjectMapper json;

    InferenceModelMetadataReader(ObjectMapper json) {
        this.json = Objects.requireNonNull(json, "json");
    }

    ModelMetadata inspect(Path modelDirectory, BooleanSupplier cancelled) throws Exception {
        requireActive(cancelled);
        Path root = requireDirectory(modelDirectory);
        Path config = root.resolve("config.json").toAbsolutePath().normalize();
        if (!config.startsWith(root) || Files.isSymbolicLink(config)
                || !Files.isRegularFile(config, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("模型目录缺少普通文件 config.json");
        }
        long size = Files.size(config);
        if (size <= 0 || size > MAX_CONFIG_BYTES) {
            throw new IOException("config.json 大小无效或超过 4 MiB");
        }
        JsonNode document;
        try (InputStream input = Files.newInputStream(config, LinkOption.NOFOLLOW_LINKS)) {
            document = json.readTree(input);
        }
        requireActive(cancelled);
        if (document == null || !document.isObject()) {
            throw new IOException("config.json 必须是 JSON 对象");
        }
        JsonNode modelType = document.get("model_type");
        if (modelType == null || !modelType.isTextual() || modelType.asText().isBlank()) {
            throw new IOException("config.json 缺少 model_type，无法识别模型类型");
        }
        ArrayList<String> architectures = new ArrayList<>();
        JsonNode values = document.path("architectures");
        if (values.isArray()) values.forEach(value -> {
            if (value.isTextual()) architectures.add(value.asText());
        });
        return new ModelMetadata(modelType.asText(), architectures, contextLength(document));
    }

    private static int contextLength(JsonNode document) {
        int direct = firstPositive(document, "max_position_embeddings", "n_positions",
                "max_sequence_length", "seq_length");
        if (direct > 0) return direct;
        JsonNode text = document.path("text_config");
        return text.isObject() ? firstPositive(text, "max_position_embeddings", "n_positions",
                "max_sequence_length", "seq_length") : 0;
    }

    private static int firstPositive(JsonNode node, String... fields) {
        for (String field : fields) {
            long value = node.path(field).asLong(0);
            if (value > 0) return (int) Math.min(Integer.MAX_VALUE, value);
        }
        return 0;
    }

    private static Path requireDirectory(Path value) throws IOException {
        if (value == null) throw new IllegalArgumentException("模型目录不能为空");
        Path absolute = value.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(absolute)
                || !Files.isDirectory(absolute, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("模型来源不是普通目录: " + absolute);
        }
        return absolute.toRealPath(LinkOption.NOFOLLOW_LINKS);
    }

    private static void requireActive(BooleanSupplier cancelled) throws InterruptedException {
        if (Thread.currentThread().isInterrupted()
                || cancelled != null && cancelled.getAsBoolean()) {
            throw new InterruptedException("模型元数据读取已取消");
        }
    }
}
