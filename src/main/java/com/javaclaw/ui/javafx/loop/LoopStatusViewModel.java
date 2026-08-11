package com.javaclaw.ui.javafx.loop;

import com.javaclaw.loop.model.Decision;
import com.javaclaw.loop.model.LoopStatus;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

/** 循环状态卡的可观察页面状态，不持有服务或 JavaFX 布局节点。 */
final class LoopStatusViewModel {

    private final StringProperty iteration = new SimpleStringProperty("");
    private final StringProperty badgeText = new SimpleStringProperty("进行中");
    private final StringProperty badgeStyleClass = new SimpleStringProperty("loop-badge-run");
    private final DoubleProperty criteriaProgress = new SimpleDoubleProperty(0);
    private final StringProperty criteriaText = new SimpleStringProperty("");
    private final BooleanProperty criteriaVisible = new SimpleBooleanProperty(false);
    private final StringProperty reason = new SimpleStringProperty("");
    private final BooleanProperty reasonVisible = new SimpleBooleanProperty(false);
    private final StringProperty tokens = new SimpleStringProperty("累计用量 0 tokens");
    private long tokensUsed;

    void update(LoopStatus status) {
        if (status == null) return;
        iteration.set("第 " + status.iteration() + " 轮");
        applyDecision(status.decision());

        boolean hasCriteria = status.total() > 0;
        criteriaVisible.set(hasCriteria);
        if (hasCriteria) {
            criteriaProgress.set((double) status.satisfied() / status.total());
            criteriaText.set("已满足 " + status.satisfied() + "/" + status.total());
        } else {
            criteriaProgress.set(0);
            criteriaText.set("");
        }

        String explanation = status.reason() == null ? "" : status.reason();
        reason.set(explanation);
        reasonVisible.set(!explanation.isBlank());
        tokensUsed = status.tokensUsed();
        String waiting = status.nextDelaySeconds() > 0
                ? " · ⏳ " + status.nextDelaySeconds() + "s 后开下一轮"
                : "";
        tokens.set("累计用量 " + tokensUsed + " tokens" + waiting);
    }

    void markCancelled() {
        applyDecision(Decision.STOP);
        reason.set("用户取消");
        reasonVisible.set(true);
        tokens.set("累计用量 " + tokensUsed + " tokens");
    }

    private void applyDecision(Decision decision) {
        BadgeSpec badge = switch (decision == null ? Decision.CONTINUE : decision) {
            case CONTINUE -> new BadgeSpec("进行中", "loop-badge-run");
            case DONE -> new BadgeSpec("已完成", "loop-badge-done");
            case STOP -> new BadgeSpec("已停止", "loop-badge-stop");
        };
        badgeText.set(badge.text());
        badgeStyleClass.set(badge.styleClass());
    }

    StringProperty iterationProperty() { return iteration; }
    StringProperty badgeTextProperty() { return badgeText; }
    StringProperty badgeStyleClassProperty() { return badgeStyleClass; }
    DoubleProperty criteriaProgressProperty() { return criteriaProgress; }
    StringProperty criteriaTextProperty() { return criteriaText; }
    BooleanProperty criteriaVisibleProperty() { return criteriaVisible; }
    StringProperty reasonProperty() { return reason; }
    BooleanProperty reasonVisibleProperty() { return reasonVisible; }
    StringProperty tokensProperty() { return tokens; }

    private record BadgeSpec(String text, String styleClass) { }
}
