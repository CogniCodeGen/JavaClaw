package com.javaclaw.ui.javafx.settings;

import com.javaclaw.desktop.api.DesktopAvailability;

import java.util.function.BooleanSupplier;

/** Text and capability decisions for the general settings desktop permission flow. */
final class DesktopPermissionStatusText {

    private DesktopPermissionStatusText() { }

    static String statusMessage(DesktopAvailability status, boolean pending,
            int awaitedCapability, boolean selected, BooleanSupplier savedAccess) {
        String detail = status.detail().isBlank() ? "请检查系统设置" : status.detail();
        String checklist = permissionChecklist(status);
        if (status.available()) {
            boolean saved = savedAccess.getAsBoolean();
            String message = !selected
                    ? "系统权限已就绪；开启并保存后智能体可直接使用电脑应用会话工具。"
                    : saved ? "系统权限已就绪，智能体可直接使用电脑应用会话工具。"
                    : "系统权限已就绪。保存后智能体可直接使用电脑应用会话工具。";
            return checklist + message;
        }
        String manualPostEventStep = pending
                && awaitedCapability == DesktopAvailability.FOREGROUND_INPUT
                && (status.capabilities() & DesktopAvailability.FOREGROUND_INPUT) == 0
                ? "若未看到系统授权提示，请点击“检查并继续授权”打开辅助功能设置。"
                : "";
        return checklist + (pending
                ? (selected
                    ? "授权待完成；返回本窗口后将自动复查。电脑应用访问尚未生效。"
                    : "授权待完成；返回本窗口后将自动复查。开启并保存后才会生效。")
                    + detail + manualPostEventStep
                : "系统权限未就绪：" + detail);
    }

    static String saveFailureMessage(boolean attemptedAccess, boolean savedAccess,
            boolean pendingPermission, Throwable failure) {
        String prefix;
        if (pendingPermission) {
            prefix = "权限尚未齐备，电脑应用访问尚未生效。";
        } else if (attemptedAccess && !savedAccess) {
            prefix = "电脑应用访问未开启或未保存：";
        } else if (attemptedAccess) {
            prefix = "设置未保存；系统权限可能已变化，请重新检查：";
        } else if (savedAccess) {
            prefix = "关闭未保存；原有电脑应用访问授权仍有效，请重试：";
        } else {
            return null;
        }
        return prefix + SettingsFieldSupport.failureMessage(failure);
    }

    static String permissionChecklist(DesktopAvailability status) {
        if (!"macos".equals(status.providerId())) return "";
        int flags = status.capabilities();
        return "屏幕与系统音频录制：" + permissionLabel(flags, DesktopAvailability.CAPTURE)
                + "\n辅助功能：" + permissionLabel(flags, DesktopAvailability.SEMANTIC_INPUT)
                + "\n发送输入事件：" + ((flags & DesktopAvailability.SEMANTIC_INPUT) == 0
                        ? "待检查（先授权辅助功能）"
                        : permissionLabel(flags, DesktopAvailability.FOREGROUND_INPUT))
                + "\n";
    }

    static int firstMissingCapability(DesktopAvailability status) {
        if (!"macos".equals(status.providerId())) return 0;
        int flags = status.capabilities();
        if ((flags & DesktopAvailability.CAPTURE) == 0) return DesktopAvailability.CAPTURE;
        if ((flags & DesktopAvailability.SEMANTIC_INPUT) == 0) {
            return DesktopAvailability.SEMANTIC_INPUT;
        }
        if ((flags & DesktopAvailability.FOREGROUND_INPUT) == 0) {
            return DesktopAvailability.FOREGROUND_INPUT;
        }
        return 0;
    }

    static boolean promptedPermissionWasGranted(int prompted, DesktopAvailability status) {
        return prompted != 0 && (status.capabilities() & prompted) != 0;
    }

    private static String permissionLabel(int flags, int capability) {
        return (flags & capability) != 0 ? "已授权" : "待授权";
    }
}
