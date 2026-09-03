package com.javaclaw.desktop.settings;

/** Provider 首次配置根据服务端权威状态推导出的下一步。 */
enum ProviderSetupPhase {
    CONNECTION,
    CREDENTIAL,
    MODELS,
    ENABLE,
    COMPLETE
}
