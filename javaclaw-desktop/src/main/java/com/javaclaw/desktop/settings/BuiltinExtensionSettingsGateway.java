package com.javaclaw.desktop.settings;

import java.util.List;
import java.util.concurrent.CompletionStage;

import com.javaclaw.protocol.BuiltinExtensionRpcContracts;

/** 内置 Bundle 与 MCP 平台能力管理页的异步 SDK 边界。 */
public interface BuiltinExtensionSettingsGateway {
    /** @return 按标识排序的实时内置能力状态 */
    CompletionStage<List<BuiltinExtensionRpcContracts.Status>> builtinExtensions();

    /**
     * 启用或停用一个可选能力。
     *
     * @param current 当前权威状态
     * @param enabled 目标状态
     * @return 提交后的权威状态
     */
    CompletionStage<BuiltinExtensionRpcContracts.Status> setBuiltinExtensionEnabled(
            BuiltinExtensionRpcContracts.Status current, boolean enabled);
}
