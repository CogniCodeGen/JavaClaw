package com.javaclaw.desktop.document;

import java.util.concurrent.CompletionStage;

import com.javaclaw.api.DocumentChunk;
import com.javaclaw.api.DocumentPreview;
import com.javaclaw.api.DocumentReference;

/** 文档页面的 SDK 异步边界；实现不将宿主文件路径暴露给 UI。 */
public interface DocumentPreviewGateway {
    /**
     * @param reference 来源引用
     * @return 新的连接独占版本
     */
    CompletionStage<DocumentPreview> resolve(DocumentReference reference);

    /**
     * @param handle 版本句柄
     * @param offset 起始字节
     * @return 至多256KiB的块
     */
    CompletionStage<DocumentChunk> read(String handle, long offset);

    /**
     * @param handle 父版本
     * @param href 文档中的相对资源
     * @return 同一权限来源的新版本
     */
    CompletionStage<DocumentPreview> resource(String handle, String href);

    /**
     * @param handle 当前可见版本
     * @return 重验权限后的租约
     */
    CompletionStage<DocumentPreview> renew(String handle);

    /**
     * @param handle 要释放的版本
     * @return 幂等关闭完成
     */
    CompletionStage<Void> close(String handle);
}
