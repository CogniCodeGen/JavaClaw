package com.javaclaw.protocol;

/**
 * 内容寻址附件的可公开元数据，不包含本地文件路径。
 *
 * @param sha256 内容 SHA-256，小写十六进制 64 位摘要
 * @param mediaType 内容 MIME 类型
 * @param sizeBytes 总内容大小，单位字节
 * @param referenceCount 持久附件引用计数；零引用可能仍处于回收宽限期
 */
public record WireAttachment(String sha256, String mediaType, long sizeBytes, long referenceCount) {}
