package com.javaclaw.desktop.view;

import java.nio.file.Path;
import java.util.Objects;

import com.javaclaw.api.CancellationSource;
import com.javaclaw.extension.spi.ViewAttachmentPolicy;

/**
 * ViewSchema Attachment 控件交给 Desktop SDK 边界的本地上传请求。
 *
 * <p>{@code source} 只存在于 Desktop 进程，不是 Protocol payload；SDK 上传完成后表单只保留无路径的 AttachmentRef。
 *
 * @param source 用户选择的本地文件
 * @param policy Schema 声明的媒体类型和大小上限
 * @param cancellation 离页、替换文件或用户取消时使用的协作式取消源
 */
public record ViewAttachmentUploadRequest(Path source, ViewAttachmentPolicy policy, CancellationSource cancellation) {
    /** 校验本地上传请求。 */
    public ViewAttachmentUploadRequest {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(cancellation, "cancellation");
    }
}
