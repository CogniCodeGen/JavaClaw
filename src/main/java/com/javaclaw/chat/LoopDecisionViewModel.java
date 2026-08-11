package com.javaclaw.chat;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

/** 循环重复调用确认卡的页面状态。 */
final class LoopDecisionViewModel {

    private final StringProperty prompt = new SimpleStringProperty("");
    private final StringProperty resolution = new SimpleStringProperty("");
    private final BooleanProperty awaitingDecision = new SimpleBooleanProperty(true);

    StringProperty promptProperty() { return prompt; }
    StringProperty resolutionProperty() { return resolution; }
    BooleanProperty awaitingDecisionProperty() { return awaitingDecision; }

    void configure(String toolName, int repeats) {
        prompt.set("检测到工具 [%s] 连续 %d 次相似调用，已暂停。是否继续执行？"
                .formatted(toolName, repeats));
        resolution.set("");
        awaitingDecision.set(true);
    }

    void resolve(boolean continueRunning) {
        resolution.set(continueRunning ? "已选择继续执行" : "已选择终止");
        awaitingDecision.set(false);
    }
}
