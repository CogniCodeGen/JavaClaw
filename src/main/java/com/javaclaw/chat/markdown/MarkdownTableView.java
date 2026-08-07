package com.javaclaw.chat.markdown;

import com.javaclaw.chat.markdown.MarkdownParagraphRenderer.RenderStyleSnapshot;
import javafx.application.Platform;
import javafx.css.PseudoClass;
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.fxmisc.richtext.InlineCssTextArea;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** 在 FX 线程把不可变表格数据转换为可选择的原生 JavaFX 视图。 */
final class MarkdownTableView {

    static final int INTERACTIVE_CELL_LIMIT = 200;
    private static final PseudoClass TABLE_SELECTED =
            PseudoClass.getPseudoClass("table-selected");

    private MarkdownTableView() {}

    static Region create(TableData data, RenderStyleSnapshot style) {
        requireFxThread();
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(style, "style");
        return data.cellCount() <= INTERACTIVE_CELL_LIMIT
                ? interactiveTable(data, style)
                : plainTableFallback(data, style);
    }

    private static Region interactiveTable(TableData data, RenderStyleSnapshot style) {
        GridPane grid = new GridPane();
        grid.getStyleClass().add("md-table");
        grid.setHgap(1);
        grid.setVgap(1);
        grid.setMaxWidth(Double.MAX_VALUE);
        grid.setFocusTraversable(true);

        TableSelectionController selection = new TableSelectionController(grid, data);

        for (int column = 0; column < data.columnCount(); column++) {
            ColumnConstraints constraints = new ColumnConstraints();
            constraints.setMinWidth(56);
            constraints.setHgrow(Priority.ALWAYS);
            constraints.setFillWidth(true);
            grid.getColumnConstraints().add(constraints);
        }

        String tableTsv = data.toTsv();
        for (int row = 0; row < data.rows().size(); row++) {
            for (int column = 0; column < data.columnCount(); column++) {
                TableCellData cell = data.rows().get(row).get(column);
                InlineCssTextArea textArea = selectableTextArea(
                        cell, style, tableTsv, selection);
                StackPane surface = new StackPane(textArea);
                surface.setAlignment(Pos.TOP_LEFT);
                surface.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
                surface.getStyleClass().add(cell.header()
                        ? "md-table-header"
                        : "md-table-cell");
                GridPane.setHgrow(surface, Priority.ALWAYS);
                GridPane.setVgrow(surface, Priority.ALWAYS);
                GridPane.setFillWidth(surface, true);
                GridPane.setFillHeight(surface, true);
                grid.add(surface, column, row);
                selection.register(new CellView(row, column, textArea, surface));
            }
        }
        selection.install();

        VBox wrapper = tableWrapper(grid);
        wrapper.getStyleClass().add("md-table-interactive");
        return wrapper;
    }

    private static InlineCssTextArea selectableTextArea(
            TableCellData cell, RenderStyleSnapshot style, String tableTsv,
            TableSelectionController selection) {
        InlineCssTextArea area = baseTextArea(cell.text(), style, true);
        area.getStyleClass().add("md-table-cell-text");
        String paragraphStyle = "-fx-text-alignment: " + cell.alignment().cssValue()
                + "; -fx-line-spacing: " + format(lineSpacing(style)) + "px;";
        area.setParagraphInsertionStyle(paragraphStyle);
        area.setParagraphStyle(0, paragraphStyle);
        area.setContextMenu(cellContextMenu(area, cell.text(), tableTsv, selection));
        return area;
    }

    private static Region plainTableFallback(TableData data, RenderStyleSnapshot style) {
        String tableTsv = data.toTsv();
        InlineCssTextArea area = baseTextArea(tableTsv, style, false);
        area.getStyleClass().add("md-table-fallback");
        area.setContextMenu(tableContextMenu(area, tableTsv));
        VBox wrapper = tableWrapper(area);
        wrapper.getStyleClass().add("md-table-plain-fallback");
        return wrapper;
    }

    private static InlineCssTextArea baseTextArea(
            String text, RenderStyleSnapshot style, boolean wrapText) {
        InlineCssTextArea area = new InlineCssTextArea();
        area.setEditable(false);
        area.setWrapText(wrapText);
        area.setAutoHeight(false);
        area.setFocusTraversable(false);
        area.setMinWidth(0);
        area.setMaxWidth(Double.MAX_VALUE);
        double initialHeight = Math.max(22, style.fontSize() * style.lineHeight() + 4);
        setContentHeight(area, initialHeight);

        String fontStack = wrapText ? style.uiFontStack() : style.monoFontStack();
        String fontStyle = "-fx-font-family: " + fontStack
                + "; -fx-font-size: " + format(style.fontSize()) + "px;";
        area.setStyle(fontStyle);
        area.setTextInsertionStyle(fontStyle);
        area.replaceText(text);
        if (!text.isEmpty()) area.setStyle(0, text.length(), fontStyle);
        String paragraphStyle = "-fx-line-spacing: "
                + format(lineSpacing(style)) + "px;";
        area.setParagraphInsertionStyle(paragraphStyle);
        for (int paragraph = 0; paragraph < area.getParagraphs().size(); paragraph++) {
            area.setParagraphStyle(paragraph, paragraphStyle);
        }
        if (wrapText) {
            bindHeightToVisualLines(area, style, initialHeight);
        } else {
            setContentHeight(area, Math.max(
                    initialHeight,
                    area.getParagraphs().size() * style.fontSize() * style.lineHeight() + 4));
        }
        return area;
    }

    private static void bindHeightToVisualLines(
            InlineCssTextArea area, RenderStyleSnapshot style, double initialHeight) {
        boolean[] updateScheduled = {false};
        Runnable requestUpdate = () -> {
            if (updateScheduled[0]) return;
            updateScheduled[0] = true;
            Platform.runLater(() -> {
                updateScheduled[0] = false;
                if (area.getWidth() <= 0) return;
                int visualLines = 0;
                for (int paragraph = 0; paragraph < area.getParagraphs().size(); paragraph++) {
                    visualLines += Math.max(1, area.getParagraphLinesCount(paragraph));
                }
                double height = Math.max(
                        initialHeight,
                        visualLines * style.fontSize() * style.lineHeight() + 4);
                setContentHeight(area, height);
            });
        };
        area.widthProperty().addListener((observable, oldValue, newValue) -> {
            if (newValue.doubleValue() > 0
                    && Math.abs(newValue.doubleValue() - oldValue.doubleValue()) > 0.5) {
                requestUpdate.run();
            }
        });
        requestUpdate.run();
    }

    private static void setContentHeight(InlineCssTextArea area, double height) {
        area.setMinHeight(height);
        area.setPrefHeight(height);
        area.setMaxHeight(height);
    }

    private static VBox tableWrapper(Region content) {
        VBox wrapper = new VBox(content);
        wrapper.setFillWidth(true);
        wrapper.setMaxWidth(Double.MAX_VALUE);
        wrapper.getStyleClass().add("md-table-wrapper");
        return wrapper;
    }

    private static ContextMenu cellContextMenu(
            InlineCssTextArea area, String cellText, String tableTsv,
            TableSelectionController selection) {
        ContextMenu menu = new ContextMenu();
        MenuItem copySelection = new MenuItem("复制所选内容");
        copySelection.setOnAction(event -> area.copy());
        MenuItem copyRange = new MenuItem("复制选中区域");
        copyRange.setOnAction(event -> selection.copySelection());
        MenuItem copyCell = new MenuItem("复制单元格");
        copyCell.setOnAction(event -> copyToClipboard(cellText));
        MenuItem copyTable = new MenuItem("复制整张表格");
        copyTable.setOnAction(event -> copyToClipboard(tableTsv));
        menu.setOnShowing(event -> {
            copySelection.setDisable(area.getSelectedText().isEmpty());
            copyRange.setDisable(!selection.hasSelection());
        });
        menu.getItems().addAll(
                copySelection, copyRange, copyCell, new SeparatorMenuItem(), copyTable);
        return menu;
    }

    private static ContextMenu tableContextMenu(InlineCssTextArea area, String tableTsv) {
        ContextMenu menu = new ContextMenu();
        MenuItem copySelection = new MenuItem("复制所选内容");
        copySelection.setOnAction(event -> area.copy());
        MenuItem copyTable = new MenuItem("复制整张表格");
        copyTable.setOnAction(event -> copyToClipboard(tableTsv));
        menu.setOnShowing(event -> copySelection.setDisable(area.getSelectedText().isEmpty()));
        menu.getItems().addAll(copySelection, copyTable);
        return menu;
    }

    private static void copyToClipboard(String text) {
        ClipboardContent content = new ClipboardContent();
        content.putString(text);
        Clipboard.getSystemClipboard().setContent(content);
    }

    private static double lineSpacing(RenderStyleSnapshot style) {
        return Math.max(0, (style.lineHeight() - 1) * style.fontSize() * 0.6);
    }

    private static String format(double value) {
        return value == Math.floor(value) ? String.valueOf((int) value) : String.valueOf(value);
    }

    private static void requireFxThread() {
        if (!Platform.isFxApplicationThread()) {
            throw new IllegalStateException("Markdown 表格必须在 JavaFX Application Thread 创建");
        }
    }

    private record CellView(
            int row, int column, InlineCssTextArea textArea, StackPane surface) {}

    /** 在多个 RichTextFX 单元格之上提供表格级矩形选区。 */
    private static final class TableSelectionController {

        private final GridPane grid;
        private final TableData data;
        private final TableSelectionModel model = new TableSelectionModel();
        private final List<CellView> cells = new ArrayList<>();

        private TableSelectionController(GridPane grid, TableData data) {
            this.grid = grid;
            this.data = data;
        }

        private void register(CellView cell) {
            cells.add(cell);
        }

        private void install() {
            grid.addEventFilter(MouseEvent.MOUSE_PRESSED, this::handleMousePressed);
            grid.addEventFilter(MouseEvent.MOUSE_DRAGGED, this::handleMouseDragged);
            grid.addEventFilter(MouseEvent.MOUSE_RELEASED, this::handleMouseReleased);
            grid.addEventFilter(KeyEvent.KEY_PRESSED, this::handleKeyPressed);
        }

        private void handleMousePressed(MouseEvent event) {
            if (event.getButton() != MouseButton.PRIMARY) return;
            CellView cell = cellAt(event.getSceneX(), event.getSceneY());
            if (cell == null) return;
            clearSelection();
            model.begin(cell.row(), cell.column());
        }

        private void handleMouseDragged(MouseEvent event) {
            if (!event.isPrimaryButtonDown()) return;
            CellView cell = cellAt(event.getSceneX(), event.getSceneY());
            boolean hadSelection = model.hasSelection();
            boolean changed = cell != null && model.extendTo(cell.row(), cell.column());
            if (!model.hasSelection()) return;

            if (!hadSelection) clearTextSelections();
            if (changed) updateSelectionHighlight();
            grid.requestFocus();
            event.consume();
        }

        private void handleMouseReleased(MouseEvent event) {
            if (event.getButton() != MouseButton.PRIMARY) return;
            if (model.hasSelection()) {
                CellView cell = cellAt(event.getSceneX(), event.getSceneY());
                if (cell != null) model.extendTo(cell.row(), cell.column());
                updateSelectionHighlight();
                grid.requestFocus();
            }
            model.finishGesture();
        }

        private void handleKeyPressed(KeyEvent event) {
            if (event.getCode() == KeyCode.ESCAPE && model.hasSelection()) {
                clearSelection();
                event.consume();
                return;
            }
            if (event.getCode() == KeyCode.C
                    && event.isShortcutDown() && model.hasSelection()) {
                copySelection();
                event.consume();
            }
        }

        private CellView cellAt(double sceneX, double sceneY) {
            for (CellView cell : cells) {
                Point2D local = cell.surface().sceneToLocal(sceneX, sceneY);
                if (local != null && cell.surface().contains(local)) return cell;
            }
            return null;
        }

        private void clearTextSelections() {
            for (CellView cell : cells) {
                cell.textArea().selectRange(0, 0);
            }
        }

        private void clearSelection() {
            model.clear();
            updateSelectionHighlight();
        }

        private void updateSelectionHighlight() {
            for (CellView cell : cells) {
                cell.surface().pseudoClassStateChanged(
                        TABLE_SELECTED, model.contains(cell.row(), cell.column()));
            }
        }

        private boolean hasSelection() {
            return model.hasSelection();
        }

        private void copySelection() {
            String tsv = model.selectedTsv(data);
            if (tsv != null) copyToClipboard(tsv);
        }
    }
}
