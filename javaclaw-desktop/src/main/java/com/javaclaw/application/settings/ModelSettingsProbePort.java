package com.javaclaw.application.settings;

import com.javaclaw.application.settings.ModelSettingsApplicationService.EmbeddingSettings;
import com.javaclaw.application.settings.ModelSettingsApplicationService.ModelSettings;
import com.javaclaw.application.settings.ModelSettingsApplicationService.ProbeResult;

/** 阻塞式模型探测端口；实现必须响应线程中断，不自动重试非幂等请求。 */
public interface ModelSettingsProbePort {

    ProbeResult probeModel(ModelSettings settings) throws Exception;

    ProbeResult probeEmbedding(EmbeddingSettings form, EmbeddingSettings persisted) throws Exception;
}
