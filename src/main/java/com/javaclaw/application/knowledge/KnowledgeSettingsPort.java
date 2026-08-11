package com.javaclaw.application.knowledge;

import com.javaclaw.application.knowledge.KnowledgeApplicationService.Settings;

/** 知识库索引参数的配置端口。 */
public interface KnowledgeSettingsPort {
    Settings load();
    Settings saveChunkSettings(int chunkSize, int chunkOverlap);
}
