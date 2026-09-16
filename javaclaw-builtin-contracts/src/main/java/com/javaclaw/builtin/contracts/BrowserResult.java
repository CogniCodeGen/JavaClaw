package com.javaclaw.builtin.contracts;

import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.AttachmentRef;

/**
 * Site 浏览器的版本化公开结果；图片和文件只引用宿主已保存的 Attachment。
 *
 * @param version 领域 wire 版本，当前为一
 * @param observation 本次脱敏页面观察
 * @param attachment 可选已归属当前 Thread 的附件；截图必须与 frame 同时存在
 */
public record BrowserResult(int version, BrowserContracts.Observation observation, Optional<AttachmentRef> attachment) {
    /** 校验版本与二进制元数据一致，避免模型把任意网页地址视为可信附件。 */
    public BrowserResult {
        if (version != 1) {
            throw new IllegalArgumentException("不支持的 Browser 结果版本");
        }
        Objects.requireNonNull(observation, "observation");
        attachment = Objects.requireNonNull(attachment, "attachment");
        if (attachment.isPresent() != observation.artifact().isPresent()) {
            throw new IllegalArgumentException("Browser 附件与观察不一致");
        }
        if (observation.frame().isPresent() && attachment.isEmpty()) {
            throw new IllegalArgumentException("截图帧必须具有图片附件");
        }
    }
}
