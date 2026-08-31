package com.javaclaw.sdk;

import com.javaclaw.sdk.model.JsonDocument;

/**
 * 未识别通知的保留表示，客户端可忽略而不中断连接。
 *
 * @param name 展示名称或资源名称；有效服务端响应中非空
 * @param payload 未识别通知的完整 JSON 文档
 */
public record UnknownNotification(String name, JsonDocument payload) implements ClientNotification {}
