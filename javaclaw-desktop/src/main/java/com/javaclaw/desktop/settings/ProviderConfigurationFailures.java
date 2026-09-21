package com.javaclaw.desktop.settings;

import com.javaclaw.client.RemoteRpcException;
import com.javaclaw.protocol.ProtocolErrorCode;

/** 模型配置错误仅展示稳定分类；异常原文可能含密钥、地址或内部实现，不能进入控件及 Tooltip。 */
final class ProviderConfigurationFailures {
    private ProviderConfigurationFailures() {}

    static String save(Throwable failure) {
        Throwable cause = SettingsFailures.unwrap(failure);
        if (cause instanceof RemoteRpcException remote) {
            return switch (remote.code()) {
                case ProtocolErrorCode.REVISION_CONFLICT -> "配置已被更新，草稿已保留。请重新读取服务并核对后保存。";
                case ProtocolErrorCode.PERMISSION_DENIED -> "没有保存此配置的权限，请检查权限或密钥库状态。";
                case ProtocolErrorCode.INVALID_PARAMS -> "配置未通过服务器校验，请检查连接信息和模型属性。";
                case ProtocolErrorCode.CAPABILITY_NOT_NEGOTIATED, ProtocolErrorCode.METHOD_NOT_FOUND ->
                    "服务器不支持统一配置，请升级 App Server。";
                default -> "保存未完成，填写内容已保留。请检查服务状态后重试。";
            };
        }
        return "配置未能提交，填写内容已保留。";
    }

    static String diagnostic(Throwable failure) {
        Throwable cause = SettingsFailures.unwrap(failure);
        return cause instanceof RemoteRpcException remote
                ? "服务器返回错误码：" + remote.code()
                : "客户端未能完成操作；未展示异常原文，以避免暴露连接信息或秘密。";
    }
}
