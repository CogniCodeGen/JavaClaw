package com.javaclaw.desktop.settings;

import java.util.Optional;

import com.javaclaw.api.ExecutionOverrides;
import com.javaclaw.api.ExecutionPreview;

/**
 * 聊天区配置的完整展示快照。
 *
 * @param selection 本地覆盖，非空 @param snapshot 已完整加载的目录与来源，可缺省
 * @param preview 服务端只读结果，可缺省 @param pending 是否正在读取或写入
 * @param dirty 是否存在未保存选择 @param message 可为空的状态说明
 */
record ChatConfigurationState(
        ExecutionOverrides selection,
        Optional<ExecutionSelectionLoader.Snapshot> snapshot,
        Optional<ExecutionPreview> preview,
        boolean pending,
        boolean dirty,
        String message) {
    boolean ready() {
        return !pending && !dirty && preview.filter(ExecutionPreview::ready).isPresent();
    }
}
