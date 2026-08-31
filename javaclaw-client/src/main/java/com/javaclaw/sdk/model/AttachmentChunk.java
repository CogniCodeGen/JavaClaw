package com.javaclaw.sdk.model;

/**
 * 附件的一块读取结果，字节所有权与内部缓冲隔离。
 *
 * @param attachment 附件元数据
 * @param offset 本块起点，单位字节
 * @param data 本块字节；构造和 accessor 均防御性复制
 * @param eof 本块之后是否已经到达内容末尾
 */
public record AttachmentChunk(AttachmentInfo attachment, long offset, byte[] data, boolean eof) {
    /** 复制字节数组，避免消费者修改共享读取结果。 */
    public AttachmentChunk {
        data = data == null ? new byte[0] : data.clone();
    }

    @Override
    public byte[] data() {
        return data.clone();
    }
}
