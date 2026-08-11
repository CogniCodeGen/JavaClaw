package com.javaclaw.ui.javafx.agent;

import com.javaclaw.platform.fxml.ViewHandle;
import javafx.scene.layout.HBox;

import java.util.Objects;

/** 嵌入设置窗口的智能体分区及其 FXML Controller 生命周期。 */
public final class AgentSettingsPanel implements AutoCloseable {

    private final ViewHandle<HBox> handle;

    AgentSettingsPanel(ViewHandle<HBox> handle) {
        this.handle = Objects.requireNonNull(handle, "handle");
    }

    public HBox root() { return handle.root(); }

    AgentSettingsController controller() {
        return handle.controller(AgentSettingsController.class);
    }

    @Override
    public void close() { handle.close(); }
}
