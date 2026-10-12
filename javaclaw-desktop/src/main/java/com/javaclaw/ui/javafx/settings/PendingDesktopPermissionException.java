package com.javaclaw.ui.javafx.settings;

/** A saved desktop grant cannot be enabled until the selected policy is ready. */
final class PendingDesktopPermissionException extends IllegalStateException {
    PendingDesktopPermissionException(String detail) {
        super("电脑应用系统权限未就绪：" + detail);
    }
}
