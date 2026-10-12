package com.javaclaw.ui.javafx.memory;

import com.javaclaw.application.memory.MemoryApplicationService.EpisodeItem;
import javafx.fxml.FXML;
import javafx.scene.control.Label;

/** 情景时间线行，只把不可变快照映射到 FXML。 */
public final class MemoryEpisodeRowController {
    @FXML private Label time;
    @FXML private Label derived;
    @FXML private Label pending;
    @FXML private Label userText;
    @FXML private Label assistantText;
    @FXML private Label toolTrace;

    void configure(EpisodeItem item) {
        time.setText(MemoryUiText.formatTime(item.timestamp()));
        derived.setText("沉淀 " + item.derivedFactCount() + " 条事实 →");
        pending.setVisible(item.pending());
        pending.setManaged(item.pending());
        userText.setText(item.userInput());
        assistantText.setText(item.assistantReply());
        toolTrace.setVisible(item.hasToolTrace());
        toolTrace.setManaged(item.hasToolTrace());
        toolTrace.setTooltip(item.hasToolTrace()
                ? new javafx.scene.control.Tooltip(item.toolTraceJson()) : null);
    }
}
