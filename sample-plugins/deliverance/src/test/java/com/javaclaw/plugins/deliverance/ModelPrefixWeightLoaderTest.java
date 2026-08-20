package com.javaclaw.plugins.deliverance;

import io.teknek.deliverance.DType;
import io.teknek.deliverance.safetensors.WeightLoader;
import io.teknek.deliverance.tensor.AbstractTensor;
import io.teknek.deliverance.tensor.TensorInfo;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelPrefixWeightLoaderTest {

    @Test
    void exposesAndResolvesMissingModelPrefixWithoutChangingExactNames() {
        RecordingWeightLoader source = new RecordingWeightLoader(Map.of(
                "embed_tokens.weight", tensor(),
                "model.norm.weight", tensor()));
        ModelPrefixWeightLoader adapter = new ModelPrefixWeightLoader(source);

        assertTrue(adapter.isWeightPresent("model.embed_tokens.weight"));
        adapter.load("model.embed_tokens.weight");
        assertEquals("embed_tokens.weight", source.loaded);

        adapter.load("model.norm.weight");
        assertEquals("model.norm.weight", source.loaded);
    }

    private static TensorInfo tensor() {
        return new TensorInfo(DType.F32, new long[]{1}, new long[]{0, 4});
    }

    private static final class RecordingWeightLoader implements WeightLoader {
        private final Map<String, TensorInfo> tensors;
        private String loaded;

        private RecordingWeightLoader(Map<String, TensorInfo> tensors) { this.tensors = tensors; }
        @Override public Map<String, String> metadata() { return Map.of(); }
        @Override public Map<String, TensorInfo> tensorInfoMap() { return tensors; }
        @Override public AbstractTensor load(String name) { loaded = name; return null; }
        @Override public DType getModelDType() { return DType.F32; }
        @Override public void close() { }
    }
}
