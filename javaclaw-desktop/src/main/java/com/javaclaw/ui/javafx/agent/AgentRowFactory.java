package com.javaclaw.ui.javafx.agent;

import com.javaclaw.application.agent.AgentManagementApplicationService.Agent;
import com.javaclaw.platform.fxml.SpringFxmlLoader;
import com.javaclaw.platform.fxml.ViewHandle;
import javafx.scene.layout.HBox;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.util.Objects;
import java.util.function.Consumer;

/** 创建可复用的智能体列表行 FXML，并保留其销毁句柄。 */
public final class AgentRowFactory {

    private final SpringFxmlLoader loader;

    public AgentRowFactory(SpringFxmlLoader loader) {
        this.loader = Objects.requireNonNull(loader, "loader");
    }

    public AgentRowView create(
            Agent agent,
            boolean selected,
            Consumer<String> onSelected) {
        URL resource = Objects.requireNonNull(
                AgentRowFactory.class.getResource("/fxml/agent/agent-list-row.fxml"),
                "缺少智能体列表行 FXML");
        try {
            ViewHandle<HBox> handle = loader.load(resource);
            handle.controller(AgentListRowController.class)
                    .configure(agent, selected, onSelected);
            return new AgentRowView(handle);
        } catch (IOException failure) {
            throw new UncheckedIOException("加载智能体列表行失败", failure);
        }
    }

    public static final class AgentRowView implements AutoCloseable {
        private final ViewHandle<HBox> handle;

        private AgentRowView(ViewHandle<HBox> handle) { this.handle = handle; }
        public HBox root() { return handle.root(); }
        @Override public void close() { handle.close(); }
    }
}
