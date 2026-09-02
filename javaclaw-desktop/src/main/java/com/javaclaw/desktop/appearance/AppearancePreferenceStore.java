package com.javaclaw.desktop.appearance;

/** Desktop 外观偏好的本地持久化边界。 */
public interface AppearancePreferenceStore {
    /**
     * 读取最后一次保存的偏好；损坏值由实现回退默认值。
     *
     * @return 可直接应用的偏好
     */
    AppearancePreferences load();

    /**
     * 保存一组完整偏好；调用成功后后续读取必须返回该组值。
     *
     * @param preferences 完整偏好
     * @throws IllegalStateException 本地偏好存储不可写
     */
    void save(AppearancePreferences preferences);
}
