package com.javaclaw.desktop.settings;

import java.util.Objects;
import java.util.Optional;

/**
 * 设置与管理中心的本机窗口偏好。
 *
 * @param lastPageKey 最后打开的平台页面 key
 * @param bounds 上次正常隐藏时的窗口边界；首次启动时为空
 */
record ManagementWindowPreferences(String lastPageKey, Optional<WindowBounds> bounds) {
    ManagementWindowPreferences {
        lastPageKey = Objects.requireNonNull(lastPageKey, "lastPageKey").strip();
        if (lastPageKey.isEmpty()) {
            throw new IllegalArgumentException("lastPageKey must not be blank");
        }
        bounds = Objects.requireNonNull(bounds, "bounds");
    }

    static ManagementWindowPreferences defaults() {
        return new ManagementWindowPreferences("appearance", Optional.empty());
    }

    /**
     * 已持久化的窗口坐标与尺寸，单位为 JavaFX 逻辑像素。
     *
     * @param x 左上角横坐标
     * @param y 左上角纵坐标
     * @param width 宽度
     * @param height 高度
     */
    record WindowBounds(double x, double y, double width, double height) {
        WindowBounds {
            if (!Double.isFinite(x)
                    || !Double.isFinite(y)
                    || !Double.isFinite(width)
                    || !Double.isFinite(height)
                    || width < ManagementCenterWindow.MINIMUM_WIDTH
                    || height < ManagementCenterWindow.MINIMUM_HEIGHT) {
                throw new IllegalArgumentException("window bounds are invalid");
            }
        }
    }
}
