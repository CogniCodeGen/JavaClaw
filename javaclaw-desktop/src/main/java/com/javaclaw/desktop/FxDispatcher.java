package com.javaclaw.desktop;

import javafx.application.Platform;

/** Single boundary for JavaFX state mutation; tests can supply a direct dispatcher. */
@FunctionalInterface
public interface FxDispatcher {
    /** 将非空 action 交给 UI 状态线程执行；实现可以立即执行或排队，调用方不得假定异步排队已完成。 */
    void execute(Runnable action);

    /** 创建 JavaFX Dispatcher；已在 FX 线程时立即执行，否则使用 Platform.runLater 排队，JavaFX Toolkit 必须已初始化。 */
    static FxDispatcher platform() {
        return action -> {
            if (Platform.isFxApplicationThread()) {
                action.run();
            } else {
                Platform.runLater(action);
            }
        };
    }
}
