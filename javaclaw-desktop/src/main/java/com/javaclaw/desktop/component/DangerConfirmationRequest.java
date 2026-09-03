package com.javaclaw.desktop.component;

import java.util.Objects;

/**
 * 危险动作确认请求。
 *
 * @param title 动作名称
 * @param consequence 不可逆或高影响后果
 * @param actionLabel 最终确认按钮文字
 */
public record DangerConfirmationRequest(String title, String consequence, String actionLabel) {
    /** 校验确认文案，避免确认窗口缺少关键后果。 */
    public DangerConfirmationRequest {
        title = requireText(title, "title");
        consequence = requireText(consequence, "consequence");
        actionLabel = requireText(actionLabel, "actionLabel");
    }

    private static String requireText(String value, String name) {
        String checked = Objects.requireNonNull(value, name).strip();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return checked;
    }
}
