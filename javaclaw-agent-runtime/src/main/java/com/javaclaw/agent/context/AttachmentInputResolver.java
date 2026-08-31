package com.javaclaw.agent.context;

import com.javaclaw.core.api.ModelMessage;
import com.javaclaw.core.api.TurnInput;

/** 从服务端内容地址解析真实模型输入，不接受客户端任意文件路径。 */
@FunctionalInterface
public interface AttachmentInputResolver {
    AttachmentInputResolver UNAVAILABLE = reference -> {
        throw new IllegalStateException("attachment understanding is not configured");
    };

    /** 返回真实文本或多模态消息；格式不支持或过大时明确失败，不能只发送文件名假装已经读取。 */
    ModelMessage resolve(TurnInput.AttachmentRef reference);
}
