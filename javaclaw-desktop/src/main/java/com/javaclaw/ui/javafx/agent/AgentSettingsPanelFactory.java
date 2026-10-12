package com.javaclaw.ui.javafx.agent;

import com.javaclaw.platform.fxml.SpringFxmlLoader;
import com.javaclaw.platform.fxml.ViewHandle;
import javafx.scene.layout.HBox;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.util.Objects;

/** 为当前工作区创建智能体设置 FXML 分区。 */
public final class AgentSettingsPanelFactory {

    private final SpringFxmlLoader loader;

    public AgentSettingsPanelFactory(SpringFxmlLoader loader) {
        this.loader = Objects.requireNonNull(loader, "loader");
    }

    public AgentSettingsPanel create(Runnable onConfigChanged) {
        URL resource = Objects.requireNonNull(
                AgentSettingsPanelFactory.class.getResource("/fxml/agent/agent-settings.fxml"),
                "缺少智能体设置 FXML");
        try {
            ViewHandle<HBox> handle = loader.load(resource);
            handle.controller(AgentSettingsController.class)
                    .configure(onConfigChanged == null ? () -> {} : onConfigChanged);
            return new AgentSettingsPanel(handle);
        } catch (IOException failure) {
            throw new UncheckedIOException("加载智能体设置失败", failure);
        }
    }
}
