package com.javaclaw.chat;

import javafx.fxml.FXML;
import javafx.scene.control.TextArea;

/** Owns the platform-specific text displayed by the FXML shortcut help content. */
public final class ChatShortcutHelpController {

    @FXML private TextArea shortcutsText;

    void configure(String shortcutKey) {
        shortcutsText.setText("""
                【输入框】
                  Enter             发送消息
                  Shift + Enter     换行
                  %s + Enter     换行
                  ↑ (输入为空)       回填上一条消息
                  Esc               清空输入 / 取消生成

                【全局】
                  %s + N         新建会话
                  %s + L         清空当前对话
                  %s + K         聚焦输入框
                  %s + ,         打开设置
                  %s + \\         切换侧栏
                  %s + / 或 ?    显示本帮助
                """.formatted(
                shortcutKey, shortcutKey, shortcutKey, shortcutKey,
                shortcutKey, shortcutKey, shortcutKey));
        shortcutsText.positionCaret(0);
    }
}
