package com.javaclaw.application.settings;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/** 模型设置、分级设置、嵌入设置和首启向导共用的稳定提供商目录。 */
public interface ModelProviderCatalog {
    List<Provider> providers();
    Optional<Provider> find(String idOrLegacyName);
    String normalizeId(String idOrLegacyName);

    enum Field { BASE_URL, MODEL_NAME, API_KEY, MANAGED_PROFILE }
    enum Capability { CHAT, EMBEDDING, THINKING, TOOLS, STREAMING }

    record Provider(
            String id,
            String displayName,
            String description,
            Set<String> legacyNames,
            Set<Field> fields,
            Set<Capability> capabilities,
            String defaultBaseUrl,
            String defaultChatModel,
            String defaultEmbeddingModel,
            int defaultEmbeddingDimensions,
            boolean localManaged) {
        public Provider {
            legacyNames = Set.copyOf(legacyNames);
            fields = Set.copyOf(fields);
            capabilities = Set.copyOf(capabilities);
            defaultBaseUrl = defaultBaseUrl == null ? "" : defaultBaseUrl;
            defaultChatModel = defaultChatModel == null ? "" : defaultChatModel;
            defaultEmbeddingModel = defaultEmbeddingModel == null ? "" : defaultEmbeddingModel;
            if (defaultEmbeddingDimensions < 0) throw new IllegalArgumentException("默认嵌入维度不能为负数");
        }
    }
}
