package com.javaclaw.protocol;

/**
 * AGENTS.md 来源元数据；协议刻意不包含正文。
 *
 * @param scope global 或 project
 * @param path 服务端解析后的绝对路径
 * @param bytes 实际注入字节数
 * @param sha256 实际注入正文摘要
 * @param truncated 是否因项目预算截断
 */
public record WireAgentsInstructionSource(String scope, String path, long bytes, String sha256, boolean truncated) {}
