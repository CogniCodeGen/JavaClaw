package com.javaclaw.core.api;

/**
 * Startup attachment repair/collection summary suitable for diagnostics.
 *
 * @param repairedUploads 修复的上传会话数量，非负
 * @param rebuiltReferences 重建的所有者引用数量，非负
 * @param missingBlobs 发现的丢失 blob 数量，非负
 * @param collectedTemporaryFiles 回收的临时文件数量，非负
 * @param collectedOrphanBlobs 回收的孤立 blob 数量，非负
 */
public record AttachmentReconciliation(
        int repairedUploads,
        int rebuiltReferences,
        int missingBlobs,
        int collectedTemporaryFiles,
        int collectedOrphanBlobs) {
    /** 拒绝负数恢复统计，保证诊断结果可直接用于展示与审计。 */
    public AttachmentReconciliation {
        if (repairedUploads < 0
                || rebuiltReferences < 0
                || missingBlobs < 0
                || collectedTemporaryFiles < 0
                || collectedOrphanBlobs < 0) {
            throw new IllegalArgumentException("attachment reconciliation counts are non-negative");
        }
    }
}
