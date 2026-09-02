package com.javaclaw.extension.spi;

import java.io.InputStream;
import java.util.Optional;

import com.javaclaw.api.CanonicalPayload;

/** 第三方扩展可用的限额 namespaced document/blob 存储。 */
public interface ExtensionDocumentStore {
    /**
     * 读取文档。
     *
     * @param key 扩展命名空间内的键
     * @return 文档
     */
    Optional<VersionedDocument> get(String key);

    /**
     * 条件保存文档。
     *
     * @param key 键
     * @param expectedRevision 期望版本，创建为 0
     * @param payload 内容
     * @return 新版本
     */
    long put(String key, long expectedRevision, CanonicalPayload payload);

    /**
     * 保存内容寻址 Blob；平台负责限额和摘要校验。
     *
     * @param content 内容流，调用结束前由平台关闭
     * @param sizeBytes 已知字节数
     * @param mediaType MIME 类型
     * @return Blob 摘要
     */
    String putBlob(InputStream content, long sizeBytes, String mediaType);
}
