package com.javaclaw.ui.javafx.mcp;

import com.javaclaw.application.mcp.McpManagementApplicationService.Server;
import com.javaclaw.application.mcp.McpManagementApplicationService.State;
import com.javaclaw.application.mcp.McpManagementApplicationService.Transport;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;

/** MCP 服务器卡片 Controller；所有业务动作回传给中心页面协调。 */
public final class McpServerCardController implements AutoCloseable {

    enum Action { START, RESTART, STOP, ENABLE, DISABLE, EDIT, DELETE, LOG, COPY }

    @FXML private Label stateBadge;
    @FXML private Label nameLabel;
    @FXML private Label metaLabel;
    @FXML private Label transportLabel;
    @FXML private Label summaryLabel;
    @FXML private Label valuesLabel;
    @FXML private HBox errorRow;
    @FXML private Label errorLabel;
    @FXML private FlowPane actionBar;
    @FXML private Button startButton;
    @FXML private Button restartButton;
    @FXML private Button stopButton;
    @FXML private CheckBox enabledCheck;
    @FXML private TitledPane toolsPane;
    @FXML private VBox toolList;
    @FXML private Label toolsHint;
    @FXML private Button discoverButton;

    private final McpToolRowFactory toolRows;
    private final McpServerCardViewModel viewModel = new McpServerCardViewModel();
    private final List<McpToolRowFactory.Row> rows = new ArrayList<>();
    private BiConsumer<String, Action> action;

    public McpServerCardController(McpToolRowFactory toolRows) {
        this.toolRows = Objects.requireNonNull(toolRows, "toolRows");
    }

    void configure(Server server, BiConsumer<String, Action> action) {
        viewModel.apply(server);
        this.action = Objects.requireNonNull(action, "action");
        nameLabel.setText(server.name());
        stateBadge.setText(viewModel.badgeText());
        stateBadge.getStyleClass().removeAll("jc-badge-running", "jc-badge-starting",
                "jc-badge-failed", "jc-badge-stopped");
        stateBadge.getStyleClass().add(viewModel.badgeClass());
        metaLabel.setText(viewModel.metaText());
        show(metaLabel, !metaLabel.getText().isBlank());
        transportLabel.setText(server.transport() == Transport.HTTP ? "http" : "stdio");
        summaryLabel.setText(viewModel.summaryText());
        valuesLabel.setText(viewModel.valuesText());
        show(valuesLabel, !valuesLabel.getText().isBlank());
        errorLabel.setText("⚠ " + (server.startupError().isBlank() ? "启动失败" : server.startupError()));
        show(errorRow, server.state() == State.FAILED);
        startButton.setVisible(server.state() == State.STOPPED || server.state() == State.FAILED);
        startButton.setManaged(startButton.isVisible());
        restartButton.setVisible(server.state() == State.RUNNING || server.state() == State.STARTING);
        restartButton.setManaged(restartButton.isVisible());
        restartButton.setDisable(server.state() == State.STARTING);
        stopButton.setVisible(server.state() == State.RUNNING || server.state() == State.STARTING);
        stopButton.setManaged(stopButton.isVisible());
        enabledCheck.setSelected(server.enabled());
        toolsPane.setText(viewModel.toolsTitle());
        toolsHint.setText(viewModel.toolsHint());
        boolean hasTools = server.state() == State.RUNNING && !server.tools().isEmpty();
        show(toolsHint, !hasTools);
        show(discoverButton, server.state() == State.STOPPED);
        renderTools(server, hasTools);
    }

    @FXML private void startRequested() { emit(Action.START); }
    @FXML private void restartRequested() { emit(Action.RESTART); }
    @FXML private void stopRequested() { emit(Action.STOP); }
    @FXML private void editRequested() { emit(Action.EDIT); }
    @FXML private void deleteRequested() { emit(Action.DELETE); }
    @FXML private void logRequested() { emit(Action.LOG); }
    @FXML private void copyRequested() { emit(Action.COPY); }
    @FXML private void enabledChanged() { emit(enabledCheck.isSelected() ? Action.ENABLE : Action.DISABLE); }

    private void renderTools(Server server, boolean hasTools) {
        closeRows();
        if (!hasTools) return;
        for (var tool : server.tools()) {
            McpToolRowFactory.Row row = toolRows.create(tool);
            rows.add(row);
            toolList.getChildren().add(row.root());
        }
    }

    private void emit(Action requested) {
        if (action != null && viewModel.server() != null) {
            action.accept(viewModel.server().name(), requested);
        }
    }

    private static void show(javafx.scene.Node node, boolean visible) {
        node.setVisible(visible);
        node.setManaged(visible);
    }

    private void closeRows() {
        RuntimeException failure = null;
        for (int index = rows.size() - 1; index >= 0; index--) {
            try { rows.get(index).close(); }
            catch (RuntimeException closeFailure) {
                if (failure == null) failure = closeFailure;
                else failure.addSuppressed(closeFailure);
            }
        }
        rows.clear();
        if (toolList != null) toolList.getChildren().clear();
        if (failure != null) throw failure;
    }

    @Override public void close() { closeRows(); }
}
