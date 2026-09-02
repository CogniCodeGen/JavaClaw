package com.javaclaw.desktop.settings;

import java.util.Objects;

import javafx.application.Platform;

/** 把直接消费异步结果的设置页面串行收口到 JavaFX Application Thread。 */
final class FxStateDispatcher {
    private FxStateDispatcher() {}

    /**
     * 在唯一 UI 调度边界执行状态更新。
     *
     * <p>已经位于 FX Thread 时直接执行，保留同一事件内的确定顺序；后台完成只入队，不得触碰控件。
     *
     * @param update 页面状态更新
     */
    static void dispatch(Runnable update) {
        Runnable checked = Objects.requireNonNull(update, "update");
        if (Platform.isFxApplicationThread()) {
            checked.run();
        } else {
            Platform.runLater(checked);
        }
    }
}
