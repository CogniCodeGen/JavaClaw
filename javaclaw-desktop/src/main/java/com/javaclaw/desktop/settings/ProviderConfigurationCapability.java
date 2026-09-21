package com.javaclaw.desktop.settings;

/** 配置能力和连接状态分开投影，只有权威能力缺失才建议升级服务端。 */
enum ProviderConfigurationCapability {
    CHECKING("正在检查服务器配置能力…"),
    AVAILABLE(""),
    UNSUPPORTED("服务器不支持统一配置，请升级 App Server。"),
    DISCONNECTED("App Server 连接已失效，请重新连接。填写内容已保留，密钥需要重新输入。"),
    FAILED("暂时无法检查服务器配置能力，请重试连接。");

    private final String message;

    ProviderConfigurationCapability(String message) {
        this.message = message;
    }

    String message() {
        return message;
    }
}
