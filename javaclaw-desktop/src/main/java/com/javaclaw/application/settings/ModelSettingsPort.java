package com.javaclaw.application.settings;

import com.javaclaw.application.settings.ModelSettingsApplicationService.EmbeddingSettings;
import com.javaclaw.application.settings.ModelSettingsApplicationService.ModelSettings;
import com.javaclaw.application.settings.ModelSettingsApplicationService.Snapshot;
import com.javaclaw.application.settings.ModelSettingsApplicationService.TierSettings;

/**
 * 工作区模型配置持久化端口。
 *
 * <p>实现应在方法返回前完成持久化；失败必须抛异常且不得把部分配置报告为成功。</p>
 */
public interface ModelSettingsPort {

    Snapshot load();

    void saveModel(ModelSettings settings);

    void resetModel();

    void saveTiers(TierSettings settings);

    void saveEmbedding(EmbeddingSettings settings);
}
