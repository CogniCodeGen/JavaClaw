package com.javaclaw.extension.spi;

import com.javaclaw.api.AttachmentContent;
import com.javaclaw.api.AttachmentMetadata;
import com.javaclaw.api.AttachmentRef;

/** 扩展核验当前调用 Workspace 所有 Core Attachment 的只读端口。 */
@FunctionalInterface
public interface AttachmentEvidencePort {
    /**
     * 核验 Attachment 存在显式 Workspace claim，且摘要、媒体类型和大小与引用完全一致。
     *
     * <p>实现必须从权威 claim 读取所有权，禁止把内容摘要存在当作 Workspace 所有权证据。
     *
     * @param reference 客户端上传后返回的完整引用
     * @return 服务端权威元数据
     */
    AttachmentMetadata requireOwned(AttachmentRef reference);

    /**
     * 读取当前 Workspace 已持有的 Attachment 内容，并在返回前执行大小上限检查。
     *
     * <p>只供可信内置扩展解析明确导入资源；实现不得把宿主路径暴露给扩展。
     *
     * @param digest 内容摘要
     * @param maximumBytes 调用方允许的最大原始字节数
     * @return 权威元数据与复制后的内容
     */
    default AttachmentContent readOwned(String digest, long maximumBytes) {
        throw new UnsupportedOperationException("Attachment content reading is unavailable");
    }

    /**
     * 把可信内置扩展生成的有界结果保存为当前 Workspace 的 Core Attachment。
     *
     * @param operation 生成操作的稳定标识
     * @param idempotencyKey 原始扩展命令的幂等键
     * @param mediaType MIME 类型
     * @param fileName 用户可见文件名
     * @param content 原始内容；实现必须复制且不得保留调用方数组
     * @return 不含宿主路径的内容寻址引用
     */
    default AttachmentRef storeGenerated(
            String operation, String idempotencyKey, String mediaType, String fileName, byte[] content) {
        throw new UnsupportedOperationException("Generated Attachment storage is unavailable");
    }
}
