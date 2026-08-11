package com.javaclaw.ui.javafx.memory;

import com.javaclaw.application.memory.MemoryApplicationService.EntityItem;
import javafx.fxml.FXML;
import javafx.scene.control.Label;

/** 单个实体卡片。 */
public final class MemoryEntityCardController {
    @FXML private Label name;
    @FXML private Label count;

    void configure(EntityItem entity) {
        name.setText(entity.name());
        count.setText(entity.factCount() + " 事实");
    }
}
