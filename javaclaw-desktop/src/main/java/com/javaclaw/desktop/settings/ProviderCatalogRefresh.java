package com.javaclaw.desktop.settings;

import java.util.List;

import com.javaclaw.api.ProviderEndpoint;

/** Provider 目录重验的草稿快照；精确编辑版本、凭据元数据和冲突标记都沿用原基线。 */
final class ProviderCatalogRefresh {
    private ProviderCatalogRefresh() {}

    static ProviderSettingsState loading(ProviderSettingsState before) {
        return copy(
                before, before.providers(), SettingsLoadState.LOADING, "正在刷新模型服务目录；当前草稿和保存版本保持不变…", before.epoch() + 1);
    }

    static ProviderSettingsState complete(
            ProviderSettingsState before, List<ProviderEndpoint> catalog, Throwable failure) {
        if (failure != null) {
            return copy(
                    before,
                    before.providers(),
                    SettingsLoadState.ERROR,
                    "模型目录读取失败；草稿仍保留：" + SettingsFailures.message(failure),
                    before.epoch());
        }
        return copy(before, catalog, SettingsLoadState.READY, "模型服务已更新；目录已刷新，当前草稿和保存版本保持不变。", before.epoch());
    }

    private static ProviderSettingsState copy(
            ProviderSettingsState before,
            List<ProviderEndpoint> catalog,
            SettingsLoadState phase,
            String message,
            long epoch) {
        return new ProviderSettingsState(
                phase,
                catalog,
                before.selected(),
                before.baseline(),
                before.draft(),
                before.credential(),
                before.providerStatus(),
                message,
                before.revisionConflict(),
                epoch);
    }
}
