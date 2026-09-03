package com.javaclaw.desktop.settings;

import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;

import com.javaclaw.desktop.component.PlatformDialogs;

/** ViewSchema 草稿与危险命令的平台确认策略。 */
final class ViewSchemaConfirmation {
    private ViewSchemaConfirmation() {}

    static boolean discard(Node owner) {
        return confirm(owner, "丢弃未保存修改", "是否离开当前草稿？", "当前页面有未保存修改。继续会丢弃这些修改。");
    }

    static boolean dangerous(Node owner, String operation) {
        return confirm(owner, "确认危险操作", "请确认操作范围", "操作 “" + operation + "” 被扩展声明为危险操作。确认后才会执行。");
    }

    private static boolean confirm(Node owner, String title, String header, String detail) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, detail, ButtonType.CANCEL, ButtonType.OK);
        alert.setTitle(title);
        alert.setHeaderText(header);
        PlatformDialogs.style(alert, owner);
        return alert.showAndWait().filter(ButtonType.OK::equals).isPresent();
    }
}
