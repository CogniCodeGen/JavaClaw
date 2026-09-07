package com.javaclaw.desktop.settings;

import java.util.Objects;

import com.javaclaw.desktop.DesktopPresenter;

/** 为一个 Desktop SDK 会话创建设置与管理中心的全部强类型网关。 */
public final class SdkManagementSettingsGateways {
    private SdkManagementSettingsGateways() {}

    /**
     * 创建共享同一连接与后台执行器的网关集合。
     *
     * @param desktop Desktop SDK Presenter
     * @return 完整管理边界
     */
    public static ManagementSettingsGateways create(DesktopPresenter desktop) {
        DesktopPresenter checked = Objects.requireNonNull(desktop, "desktop");
        SdkCoreSettingsGateway core = new SdkCoreSettingsGateway(checked);
        return new ManagementSettingsGateways(
                core,
                new SdkPromptPreviewSettingsGateway(checked),
                new SdkPromptOptimizationSettingsGateway(checked),
                core,
                core,
                core,
                new SdkBuiltinExtensionSettingsGateway(checked),
                new SdkAutomationJobSettingsGateway(checked),
                new SdkCodingSettingsGateway(checked),
                new SdkScheduleCatalogGateway(checked),
                new SdkExtensionSettingsGateway(checked),
                checked::selectedWorkspaceIdSnapshot);
    }
}
