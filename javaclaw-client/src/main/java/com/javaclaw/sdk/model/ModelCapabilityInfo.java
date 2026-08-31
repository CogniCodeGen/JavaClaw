package com.javaclaw.sdk.model;

/**
 * 云模型发现能力，不包含 API Key。
 *
 * @param provider Provider 标识
 * @param defaultModel 当前配置的默认模型
 * @param configured 是否已配置凭据
 * @param configurationHint 非敏感配置提示
 */
public record ModelCapabilityInfo(String provider, String defaultModel, boolean configured, String configurationHint) {}
