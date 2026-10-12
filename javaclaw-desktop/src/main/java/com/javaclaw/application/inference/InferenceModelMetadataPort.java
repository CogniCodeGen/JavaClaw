package com.javaclaw.application.inference;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;

/** 由模型提供者解释模型元数据，宿主只保存标准结果。 */
public interface InferenceModelMetadataPort {

    ModelMetadata inspectModel(Path modelDirectory, BooleanSupplier cancelled) throws Exception;

    record ModelMetadata(String modelType, List<String> architectures, int declaredContextLength) {
        public ModelMetadata {
            if (modelType == null || modelType.isBlank()) {
                throw new IllegalArgumentException("模型提供者未返回模型类型");
            }
            modelType = modelType.strip().toLowerCase(Locale.ROOT);
            if (!modelType.matches("[a-z0-9][a-z0-9_-]{0,127}")) {
                throw new IllegalArgumentException("模型类型标识格式无效");
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
