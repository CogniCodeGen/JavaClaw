package com.javaclaw.ui.javafx.settings;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** 设置分区暴露给窗口页脚的动作，不泄漏具体 Controller 类型。 */
record SettingsPanelActions(
        SaveAction save,
        Supplier<String> savedTip,
        Runnable test,
        String testLabel) {

    @FunctionalInterface
    interface SaveAction {
        void run(Runnable success, Consumer<Throwable> failure);
    }

    SettingsPanelActions {
        savedTip = savedTip == null ? () -> "✓ 已保存，下一轮对话生效" : savedTip;
    }

    static SettingsPanelActions asyncSave(
            SaveAction save, Supplier<String> savedTip) {
        return new SettingsPanelActions(Objects.requireNonNull(save, "save"),
                savedTip, null, null);
    }

    static SettingsPanelActions asyncSaveAndTest(
            SaveAction save, Supplier<String> savedTip, Runnable test, String testLabel) {
        return new SettingsPanelActions(Objects.requireNonNull(save, "save"), savedTip,
                Objects.requireNonNull(test, "test"), Objects.requireNonNull(testLabel, "testLabel"));
    }

    static SettingsPanelActions none() {
        return new SettingsPanelActions(null, () -> "", null, null);
    }
}
