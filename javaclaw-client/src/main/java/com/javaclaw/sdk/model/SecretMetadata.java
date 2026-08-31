package com.javaclaw.sdk.model;

import java.time.Instant;

/**
 * 凭据是否配置、修订号及更新时间；永不返回明文值。
 *
 * @param namespace 凭据所属命名空间，用于隔离模型、MCP 与插件配置
 * @param name 展示名称或资源名称；有效服务端响应中非空
 * @param configured 是否存在有效凭据配置；不暴露凭据值
 * @param revision 资源修订号，更新时用作 expectedRevision
 * @param updatedAt 凭据最近更新时间；尚无记录时可为 null
 */
public record SecretMetadata(String namespace, String name, boolean configured, long revision, Instant updatedAt) {}
