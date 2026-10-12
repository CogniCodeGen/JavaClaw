package com.javaclaw.api.interaction;

import java.util.List;

/**
 * 要求用户从一组互斥选项中选择一项。
 *
 * @param title          对话框标题
 * @param message        选择原因和影响
 * @param options        可选项；为空时实现应直接返回 {@code null}
 * @param timeoutSeconds 等待秒数；小于等于零时使用实现端默认值
 */
public record ChoiceRequest(
        String title,
        String message,
        List<ChoiceOption> options,
        int timeoutSeconds
) {

    public ChoiceRequest {
        if (title == null) title = "";
        if (message == null) message = "";
        options = options == null ? List.of() : List.copyOf(options);
    }
}
