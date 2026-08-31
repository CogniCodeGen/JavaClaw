package com.javaclaw.agent.runtime.persistence;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;

import com.javaclaw.core.api.AttachmentMetadata;
import com.javaclaw.core.api.AttachmentReadChunk;
import com.javaclaw.core.api.AttachmentReconciliation;
import com.javaclaw.core.api.AttachmentUpload;

/** Content-addressed attachment and resumable upload persistence port. */
public interface AttachmentRepository {
    long MAX_ATTACHMENT_BYTES = 256L * 1024L * 1024L;
    int MAX_CHUNK_BYTES = 1024 * 1024;

    /**
     * 流式保存附件并计算 SHA-256，返回引用元数据；调用方仍负责关闭 source。
     *
     * @throws java.io.IOException 读取、大小限制或持久保存失败
     */
    AttachmentMetadata put(InputStream source, String mediaType) throws IOException;

    /** 按 SHA-256 查询附件元数据，不读取 blob 内容；不存在时返回 Optional.empty。 */
    Optional<AttachmentMetadata> findAttachment(String sha256);

    /**
     * 打开已登记 blob 的读取流，调用方必须关闭；不接受任意文件路径。
     *
     * @throws java.io.IOException blob 缺失或无法读取
     */
    InputStream openAttachment(String sha256) throws IOException;

    /** 增加附件引用并返回更新后的元数据；摘要必须对应已登记内容。 */
    AttachmentMetadata retainAttachment(String sha256);

    /**
     * 释放一份可由客户端释放的引用；不释放 Thread、Knowledge 等领域所有者的引用。 返回 true 表示引用归零后附件记录及 blob 已移除；false 也可能已经释放一份引用，但仍有其他所有者。
     *
     * @throws IOException blob 删除失败；启动期恢复负责校准事务已提交而文件尚未删除的状态
     */
    boolean releaseAttachment(String sha256) throws IOException;

    /** 创建有大小/摘要声明的可续传上传会话；幂等键防止重试产生重复会话，上传最长保留 24 小时。 */
    AttachmentUpload startUpload(
            String expectedSha256, String mediaType, String displayName, long expectedSize, String idempotencyKey)
            throws IOException;

    /** 在准确 offset 追加解码后的字节块；块不得超过 1 MiB 或声明总大小，拒绝错位写入。 */
    AttachmentUpload appendUploadChunk(String uploadId, long offset, byte[] data) throws IOException;

    /** 校验总大小和 SHA-256 后提交上传并建立所有者引用；未收齐或摘要不符必须失败。 */
    AttachmentMetadata completeUpload(String uploadId) throws IOException;

    /** 从字节 offset 读取不超过 maximumBytes 的附件块；返回 EOF 标记和元数据。 */
    AttachmentReadChunk readChunk(String sha256, long offset, int maximumBytes) throws IOException;

    /** 回收过期上传会话，返回处理数量；不触碰 3.x 数据目录。 */
    int collectExpiredUploads() throws IOException;

    /** 启动期校准上传文件、引用与 blob 状态；修复可恢复中间态并延迟回收孤立内容，返回统计。 */
    AttachmentReconciliation reconcileAttachments() throws IOException;
}
