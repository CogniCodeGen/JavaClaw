package com.javaclaw.plugins.deliverance;

import io.teknek.deliverance.DType;
import io.teknek.deliverance.safetensors.TensorShardSpec;
import io.teknek.deliverance.safetensors.WeightLoader;
import io.teknek.deliverance.tensor.AbstractTensor;
import io.teknek.deliverance.tensor.TensorInfo;

import java.util.LinkedHashMap;
import java.util.Map;

/** Adapts encoder checkpoints that omit the causal-model {@code model.} weight prefix. */
final class ModelPrefixWeightLoader implements WeightLoader {
    private static final String MODEL_PREFIX = "model.";

    private final WeightLoader delegate;
    private final Map<String, TensorInfo> tensorInfo;

    ModelPrefixWeightLoader(WeightLoader delegate) {
        this.delegate = delegate;
        Map<String, TensorInfo> aliases = new LinkedHashMap<>(delegate.tensorInfoMap());
        delegate.tensorInfoMap().forEach((name, info) -> {
            if (!name.startsWith(MODEL_PREFIX)) aliases.putIfAbsent(MODEL_PREFIX + name, info);
        });
        tensorInfo = Map.copyOf(aliases);
    }

    @Override public Map<String, String> metadata() { return delegate.metadata(); }

    @Override public Map<String, TensorInfo> tensorInfoMap() { return tensorInfo; }

    @Override public AbstractTensor load(String name) { return delegate.load(resolve(name)); }

    @Override
    public AbstractTensor loadRows(String name, int rowOffset, int rowCount) {
        return delegate.loadRows(resolve(name), rowOffset, rowCount);
    }

    @Override
    public AbstractTensor load(String name, TensorShardSpec shardSpec) {
        return delegate.load(resolve(name), shardSpec);
    }

    @Override public DType getModelDType() { return delegate.getModelDType(); }

    @Override public void close() throws Exception { delegate.close(); }

    private String resolve(String name) {
        if (delegate.isWeightPresent(name) || !name.startsWith(MODEL_PREFIX)) return name;
        String unprefixed = name.substring(MODEL_PREFIX.length());
        return delegate.isWeightPresent(unprefixed) ? unprefixed : name;
    }
}
