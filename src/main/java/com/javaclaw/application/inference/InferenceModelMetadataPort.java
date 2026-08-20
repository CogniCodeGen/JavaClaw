package com.javaclaw.application.inference;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;

/** 轻量读取 Hugging Face 模型目录元数据；不得扫描或加载权重。 */
public interface InferenceModelMetadataPort {

    ModelMetadata inspectModel(Path modelDirectory, BooleanSupplier cancelled) throws Exception;

    record ModelMetadata(String modelType, List<String> architectures, int declaredContextLength) {
        public ModelMetadata {
            if (modelType == null || modelType.isBlank()) {
                throw new IllegalArgumentException("config.json 缺少 model_type");
            }
            modelType = modelType.strip().toLowerCase(Locale.ROOT);
            if (!modelType.matches("[a-z0-9][a-z0-9_-]{0,127}")) {
                throw new IllegalArgumentException("config.json 的 model_type 格式无效");
            }
            architectures = architectures == null ? List.of() : architectures.stream()
                    .filter(value -> value != null && !value.isBlank())
                    .map(String::strip).distinct().toList();
            if (declaredContextLength < 0) {
                throw new IllegalArgumentException("模型声明的上下文长度不能为负数");
            }
        }

        public ModelMetadata(String modelType, List<String> architectures) {
            this(modelType, architectures, 0);
        }
    }
}
