package com.javaclaw.browser.client;

import java.util.Arrays;
import java.util.Objects;

import com.javaclaw.builtin.contracts.BrowserContracts;

/**
 * 浏览器脱敏观察与可选附件私有字节；调用方存储附件后必须关闭以清空临时副本。
 *
 * @param observation 不含凭据的页面观察
 * @param content 原始附件字节，构造和读取均复制；无附件时为空
 */
public record BrowserActionResult(BrowserContracts.Observation observation, byte[] content) implements AutoCloseable {
    /** 验证二进制长度与公开元数据一致。 */
    public BrowserActionResult {
        Objects.requireNonNull(observation, "observation");
        content = Objects.requireNonNull(content, "content").clone();
        long expected =
                observation.artifact().map(BrowserContracts.Artifact::sizeBytes).orElse(0L);
        if (content.length != expected || content.length > BrowserContracts.MAXIMUM_ARTIFACT_BYTES) {
            throw new IllegalArgumentException("Browser artifact metadata does not match content");
        }
    }

    /** @return 附件的独立副本；调用方负责其生命周期 */
    @Override
    public byte[] content() {
        return content.clone();
    }

    /** 清空本结果持有的附件字节；可重复调用。 */
    @Override
    public void close() {
        Arrays.fill(content, (byte) 0);
    }
}
