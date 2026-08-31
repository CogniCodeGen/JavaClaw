package com.javaclaw.sdk;

import java.util.List;

/**
 * 等待用户回答的通知；先注册问题再发送，避免快速响应丢失。
 *
 * @param threadId 所属 Thread 标识；有效服务端响应中非空
 * @param requestId 用户输入问题关联标识
 * @param prompt 向用户展示的问题，不作为系统指令或权限声明
 * @param choices 可选回答列表；null 归一为空列表并复制
 */
public record UserInputRequestedNotification(String threadId, String requestId, String prompt, List<String> choices)
        implements ClientNotification {
    /** 复制可选回答集合；保留问题标识以与用户响应一一关联。 */
    public UserInputRequestedNotification {
        choices = choices == null ? List.of() : List.copyOf(choices);
    }
}
