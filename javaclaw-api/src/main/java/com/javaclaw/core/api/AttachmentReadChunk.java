package com.javaclaw.core.api;

/**
 * Bounded binary download chunk encoded by the protocol adapter.
 *
 * @param metadata 非空附件元数据
 * @param offset 本块起点，单位字节，非负
 * @param data 当前块字节；null 归一为空数组，输入和 accessor 均复制
 * @param eof 读取本块后是否到达附件末尾
 */
public record AttachmentReadChunk(AttachmentMetadata metadata, long offset, byte[] data, boolean eof) {
    /** 校验读取位置并复制字节，防止调用方修改已经返回的附件块。 */
    public AttachmentReadChunk {
        if (offset < 0) {
            throw new IllegalArgumentException("offset is negative");
        }
        data = data == null ? new byte[0] : data.clone();
    }

    @Override
    public byte[] data() {
        return data.clone();
    }
}
