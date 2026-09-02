package com.javaclaw.desktop.settings;

/** 设置与管理中心窗口偏好的本机存储边界。 */
interface ManagementWindowPreferenceStore {
    /**
     * 读取偏好；损坏或缺失的持久化值由实现回退默认值。
     *
     * @return 完整偏好
     */
    ManagementWindowPreferences load();

    /**
     * 原子保存一组完整偏好。
     *
     * @param preferences 完整偏好
     */
    void save(ManagementWindowPreferences preferences);
}
