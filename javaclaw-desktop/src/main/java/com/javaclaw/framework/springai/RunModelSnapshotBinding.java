package com.javaclaw.framework.springai;

import com.javaclaw.framework.spi.ExternalPluginLock;
import com.javaclaw.inference.api.InferenceProviderSnapshot;
import org.springframework.ai.chat.model.ChatModel;

import java.util.List;

/** 将主模型绑定到已冻结的插件工件与配置，避免运行途中接入新实例。 */
final class RunModelSnapshotBinding {
    private RunModelSnapshotBinding() { }

    static ChatModel bind(ChatModel model, List<ExternalPluginLock> locks) {
        if (!(model instanceof ManagedInferenceChatModel managed)) return model;
        return managed.forSnapshots(locks.stream().map(lock -> new InferenceProviderSnapshot(
                lock.workspaceId(), lock.pluginId(), lock.artifactHash(), lock.configurationRevision(),
                lock.hostConfigurationJson())).toList());
    }
}
