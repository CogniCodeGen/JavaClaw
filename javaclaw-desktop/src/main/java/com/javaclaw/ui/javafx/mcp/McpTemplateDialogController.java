package com.javaclaw.ui.javafx.mcp;

import com.javaclaw.application.mcp.McpManagementApplicationService;
import com.javaclaw.application.mcp.McpManagementApplicationService.SaveCommand;
import com.javaclaw.application.mcp.McpManagementApplicationService.Template;
import com.javaclaw.application.mcp.McpManagementApplicationService.Transport;
import javafx.fxml.FXML;
import javafx.scene.control.ListView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** MCP 模板选择弹窗 Controller。 */
public final class McpTemplateDialogController implements AutoCloseable {
    @FXML private ListView<Template> templateList;

    private final McpManagementApplicationService useCases;
    private final McpTemplateCellFactory cells;
    private final List<McpTemplateCellFactory.Cell> createdCells = new ArrayList<>();
    private Runnable acceptAction = () -> { };

    public McpTemplateDialogController(
            McpManagementApplicationService useCases,
            McpTemplateCellFactory cells) {
        this.useCases = java.util.Objects.requireNonNull(useCases, "useCases");
        this.cells = java.util.Objects.requireNonNull(cells, "cells");
    }

    @FXML
    private void initialize() {
        templateList.getItems().setAll(useCases.templates());
        templateList.setCellFactory(ignored -> {
            McpTemplateCellFactory.Cell cell = cells.create();
            createdCells.add(cell);
            return cell;
        });
        templateList.getSelectionModel().selectFirst();
    }

    @FXML
    private void templateClicked(javafx.scene.input.MouseEvent event) {
        if (event.getClickCount() == 2 && selected() != null) acceptAction.run();
    }

    void onAccept(Runnable action) { acceptAction = java.util.Objects.requireNonNull(action, "action"); }

    SaveCommand selectedCommand() {
        Template template = selected();
        if (template == null) return null;
        Map<String, String> environment = new LinkedHashMap<>();
        template.environmentKeys().forEach(key -> environment.put(key, ""));
        return new SaveCommand("", template.id(), Transport.STDIO, template.command(),
                template.arguments(), environment, "", Map.of(), true);
    }

    private Template selected() { return templateList.getSelectionModel().getSelectedItem(); }

    @Override
    public void close() {
        for (int index = createdCells.size() - 1; index >= 0; index--) createdCells.get(index).close();
        createdCells.clear();
        templateList.setCellFactory(null);
        acceptAction = () -> { };
    }
}
