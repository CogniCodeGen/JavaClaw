package com.javaclaw.ui.javafx.memory;

import com.javaclaw.application.memory.MemoryApplicationService.OperationResult;
import javafx.stage.Window;

/** 分区向窗口提交结果、通知和窗口上下文的窄接口。 */
interface MemorySectionHost {
    void apply(OperationResult result);
    void showMessage(String message);
    Window window();
}
