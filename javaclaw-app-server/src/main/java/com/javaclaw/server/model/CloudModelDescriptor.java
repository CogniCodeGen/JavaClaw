package com.javaclaw.server.model;

/**
 * 云模型发现描述；配置状态和操作提示与凭据值分离。
 *
 * @param provider 云 Provider 标识
 * @param defaultModel 默认对话模型标识
 * @param configured 是否已配置可用凭据；不包含凭据值
 * @param configurationHint 缺失配置时的用户提示，不包含敏感值
 */
public record CloudModelDescriptor(
        String provider, String defaultModel, boolean configured, String configurationHint) {}
