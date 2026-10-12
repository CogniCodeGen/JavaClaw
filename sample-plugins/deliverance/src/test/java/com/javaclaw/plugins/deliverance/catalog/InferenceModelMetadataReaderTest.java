package com.javaclaw.plugins.deliverance.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class InferenceModelMetadataReaderTest {
    @TempDir Path root;
    private final InferenceModelMetadataReader reader = new InferenceModelMetadataReader(new ObjectMapper());

    @Test void readsOnlySmallConfigurationAndNormalizesSupportedMetadata() throws Exception {
        Files.writeString(root.resolve("config.json"),
                "{\"model_type\":\" QWEN3 \",\"architectures\":[\"Qwen3ForCausalLM\"],\"max_position_embeddings\":8192}");
        Files.createDirectory(root.resolve("model.safetensors"));
        var metadata = reader.inspect(root, () -> false);
        assertEquals("qwen3", metadata.modelType());
        assertEquals(8192, metadata.declaredContextLength());
        assertEquals("Qwen3ForCausalLM", metadata.architectures().getFirst());
    }
    @Test void rejectsSymlinkConfigurationAndMissingModelType() throws Exception {
        Path outside = Files.writeString(root.resolve("outside.json"), "{\"model_type\":\"llama\"}");
        Path config = root.resolve("config.json");
        Files.createSymbolicLink(config, outside);
        assertThrows(java.io.IOException.class, () -> reader.inspect(root, () -> false));
        Files.delete(config); Files.writeString(config, "{}");
        assertThrows(java.io.IOException.class, () -> reader.inspect(root, () -> false));
    }
    @Test void cancellationStopsBeforeReadingAnyModelFile() {
        assertThrows(InterruptedException.class, () -> reader.inspect(root, () -> true));
    }
}
