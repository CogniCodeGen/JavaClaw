package com.javaclaw.server.persistence;

/**
 * 单个第三方扩展 namespaced 存储的硬上限。
 *
 * @param documentBytes 单文档最大字节数
 * @param totalDocumentBytes 文档总字节数
 * @param blobBytes 单 Blob 最大字节数
 * @param totalBlobBytes Blob 总字节数
 */
public record ThirdPartyStorageQuota(long documentBytes, long totalDocumentBytes, long blobBytes, long totalBlobBytes) {
    /** 校验所有上限为正数且总量不小于单项。 */
    public ThirdPartyStorageQuota {
        if (documentBytes < 1 || totalDocumentBytes < documentBytes || blobBytes < 1 || totalBlobBytes < blobBytes) {
            throw new IllegalArgumentException("third-party storage quotas are invalid");
        }
    }

    /**
     * 返回平台默认限额。
     *
     * @return 单文档 256 KiB、文档总量 4 MiB、单 Blob 16 MiB、Blob 总量 64 MiB
     */
    public static ThirdPartyStorageQuota defaults() {
        return new ThirdPartyStorageQuota(256L * 1024, 4L * 1024 * 1024, 16L * 1024 * 1024, 64L * 1024 * 1024);
    }
}
