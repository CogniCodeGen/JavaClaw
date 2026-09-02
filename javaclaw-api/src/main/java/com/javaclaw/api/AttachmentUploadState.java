package com.javaclaw.api;

/** 分块 Attachment 上传的持久状态。 */
public enum AttachmentUploadState {
    /** 会话允许继续追加下一个分块。 */
    ACTIVE,
    /** 所有分块已经校验并提交为内容寻址 Attachment。 */
    COMPLETED,
    /** 客户端显式取消了上传。 */
    ABORTED,
    /** staging 内容损坏或与声明摘要不一致。 */
    FAILED,
    /** 上传在时限内没有完成，服务端已经回收 staging。 */
    EXPIRED
}
