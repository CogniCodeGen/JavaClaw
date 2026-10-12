package com.javaclaw.ui.javafx.agent;

import com.javaclaw.application.agent.AgentManagementApplicationService.Agent;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;

import java.util.Objects;
import java.util.function.Consumer;

/** 单个智能体列表行的无服务 FXML Controller。 */
public final class AgentListRowController {

    @FXML private HBox root;
    @FXML private Label statusDot;
    @FXML private Label nameLabel;
    @FXML private Label builtInTag;

    private String agentId;
    private Consumer<String> onSelected = ignored -> {};

    void configure(Agent agent, boolean selected, Consumer<String> selection) {
        Objects.requireNonNull(agent, "agent");
        agentId = agent.id();
        onSelected = Objects.requireNonNull(selection, "selection");
        nameLabel.setText(agent.name());
        statusDot.setText(agent.enabled() ? "●" : "○");
        statusDot.getStyleClass().setAll(
                agent.enabled() ? "status-dot-enabled" : "status-dot-disabled");
        builtInTag.setVisible(agent.builtIn());
        builtInTag.setManaged(agent.builtIn());
        root.getStyleClass().remove("skill-list-row-selected");
        if (selected) root.getStyleClass().add("skill-list-row-selected");
        root.setAccessibleText(agent.name() + (agent.enabled() ? "，已启用" : "，已停用"));
    }

    @FXML
    private void selectRequested(MouseEvent ignored) { onSelected.accept(agentId); }
}
