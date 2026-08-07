package com.javaclaw.chat.markdown;

import com.javaclaw.chat.markdown.MarkdownParagraphRenderer.RenderStyleSnapshot;
import javafx.application.Platform;
import javafx.css.PseudoClass;
import javafx.event.Event;
import javafx.geometry.Bounds;
import javafx.geometry.Point2D;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.input.Clipboard;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.PickResult;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.fxmisc.richtext.InlineCssTextArea;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfSystemProperty(named = "javaclaw.fx.tests", matches = "true",
        disabledReason = "需要可用的 JavaFX 显示服务")
class MarkdownTableViewTest {

    private static final long TIMEOUT_SECONDS = 5;
    private static final RenderStyleSnapshot STYLE = new RenderStyleSnapshot(
            14.5, 1.65, "\"System\", sans-serif", "\"SF Mono\", monospace");

    @BeforeAll
    static void startToolkit() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        try {
            Platform.startup(started::countDown);
        } catch (IllegalStateException alreadyStarted) {
            started.countDown();
        }
        assertTrue(started.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        callFx(() -> {
            Platform.setImplicitExit(false);
            return null;
        });
    }

    @Test
    void interactiveCellsAreReadOnlySelectableAndAligned() throws Exception {
        TableData data = new TableData(List.of(List.of(
                cell("左对齐", true, TableCellAlignment.LEFT),
                cell("居中文本", true, TableCellAlignment.CENTER),
                cell("右对齐", true, TableCellAlignment.RIGHT))), 3);
        Region table = callFx(() -> MarkdownTableView.create(data, STYLE));

        List<InlineCssTextArea> areas = callFx(() -> findTextAreas(table));
        assertEquals(3, areas.size());
        runFx(() -> areas.get(1).selectRange(0, 2));

        assertEquals("居中", callFx(areas.get(1)::getSelectedText));
        assertFalse(callFx(areas.get(1)::isEditable));
        assertFalse(callFx(areas.get(1)::isAutoHeight));
        assertTrue(callFx(areas.get(1)::isWrapText));
        assertTrue(callFx(() -> areas.get(1).getParagraph(0).getParagraphStyle())
                .contains("-fx-text-alignment: center"));
        assertEquals(List.of("复制所选内容", "复制选中区域", "复制单元格", "", "复制整张表格"),
                callFx(() -> areas.get(1).getContextMenu().getItems().stream()
                        .map(item -> item.getText() == null ? "" : item.getText())
                        .toList()));

        runFx(() -> disposeTextAreas(table));
    }

    @Test
    void usesSingleSelectableFallbackAboveInteractiveCellLimit() throws Exception {
        Region interactive = callFx(() -> MarkdownTableView.create(
                tableWithCells(MarkdownTableView.INTERACTIVE_CELL_LIMIT), STYLE));
        Region fallback = callFx(() -> MarkdownTableView.create(
                tableWithCells(MarkdownTableView.INTERACTIVE_CELL_LIMIT + 1), STYLE));

        assertTrue(callFx(() -> hasStyleClass(interactive, "md-table-interactive")));
        assertEquals(MarkdownTableView.INTERACTIVE_CELL_LIMIT,
                callFx(() -> findTextAreas(interactive).size()));
        assertTrue(callFx(() -> hasStyleClass(fallback, "md-table-plain-fallback")));
        assertEquals(1, callFx(() -> findTextAreas(fallback).size()));
        InlineCssTextArea fallbackArea = callFx(() -> findTextAreas(fallback).getFirst());
        assertFalse(callFx(fallbackArea::isWrapText));
        runFx(() -> fallbackArea.selectRange(0, 4));
        assertEquals("cell", callFx(fallbackArea::getSelectedText));

        runFx(() -> {
            disposeTextAreas(interactive);
            disposeTextAreas(fallback);
        });
    }

    @Test
    void cellSurfacesFillHeightOfTallestCellInRow() throws Exception {
        TableData data = new TableData(List.of(List.of(
                cell("这是一段足够长、在固定宽度内需要换成多行显示的表格内容。"
                                .repeat(4), false, TableCellAlignment.LEFT),
                cell("短文本", false, TableCellAlignment.LEFT))), 2);

        LayoutFixture fixture = callFx(() -> {
            Region table = MarkdownTableView.create(data, STYLE);
            StackPane root = new StackPane(table);
            Scene scene = new Scene(root, 520, 320);
            scene.getStylesheets().add(
                    getClass().getResource("/css/chat.css").toExternalForm());
            Stage stage = new Stage();
            stage.setScene(scene);
            stage.show();
            root.applyCss();
            root.layout();
            return new LayoutFixture(stage, root, table);
        });

        Thread.sleep(150);
        runFx(() -> {
            try {
                fixture.root().applyCss();
                fixture.root().layout();
                GridPane grid = findGrid(fixture.table());
                assertNotNull(grid);
                List<Region> firstRow = grid.getChildren().stream()
                        .filter(node -> GridPane.getRowIndex(node) == null
                                || GridPane.getRowIndex(node) == 0)
                        .map(Region.class::cast)
                        .toList();
                assertEquals(2, firstRow.size());
                assertTrue(firstRow.get(0).getHeight() > 80,
                        "长文本必须推动整行增高，不能被裁成单行");
                assertEquals(firstRow.get(0).getHeight(), firstRow.get(1).getHeight(), 0.5);
            } finally {
                fixture.stage().close();
                disposeTextAreas(fixture.table());
            }
        });
    }

    @Test
    void draggingAcrossCellsCopiesEverySelectedRowAsTsv() throws Exception {
        TableData data = new TableData(List.of(
                List.of(
                        cell("H0", true, TableCellAlignment.LEFT),
                        cell("H1", true, TableCellAlignment.LEFT),
                        cell("H2", true, TableCellAlignment.LEFT)),
                List.of(
                        cell("A0", false, TableCellAlignment.LEFT),
                        cell("A1", false, TableCellAlignment.LEFT),
                        cell("", false, TableCellAlignment.LEFT)),
                List.of(
                        cell("B0", false, TableCellAlignment.LEFT),
                        cell("B1", false, TableCellAlignment.LEFT),
                        cell("B2", false, TableCellAlignment.LEFT))), 3);

        LayoutFixture fixture = callFx(() -> {
            Region table = MarkdownTableView.create(data, STYLE);
            StackPane root = new StackPane(table);
            Scene scene = new Scene(root, 620, 320);
            scene.getStylesheets().add(
                    getClass().getResource("/css/chat.css").toExternalForm());
            Stage stage = new Stage();
            stage.setScene(scene);
            stage.show();
            root.applyCss();
            root.layout();
            return new LayoutFixture(stage, root, table);
        });

        runFx(() -> {
            try {
                GridPane grid = findGrid(fixture.table());
                assertNotNull(grid);
                StackPane last = cellSurface(grid, 2, 2);
                StackPane first = cellSurface(grid, 1, 1);

                firePrimaryMouse(last, MouseEvent.MOUSE_PRESSED, true);
                firePrimaryMouse(first, MouseEvent.MOUSE_DRAGGED, true);
                firePrimaryMouse(first, MouseEvent.MOUSE_RELEASED, false);

                PseudoClass selected = PseudoClass.getPseudoClass("table-selected");
                assertTrue(cellSurface(grid, 1, 1).getPseudoClassStates().contains(selected));
                assertTrue(cellSurface(grid, 1, 2).getPseudoClassStates().contains(selected));
                assertTrue(cellSurface(grid, 2, 1).getPseudoClassStates().contains(selected));
                assertTrue(cellSurface(grid, 2, 2).getPseudoClassStates().contains(selected));
                assertFalse(cellSurface(grid, 1, 0).getPseudoClassStates().contains(selected));

                fireShortcutCopy(grid);
                assertEquals("A1\t\nB1\tB2", Clipboard.getSystemClipboard().getString(),
                        "跨行选区必须完整复制，不能只保留最后一行");
            } finally {
                fixture.stage().close();
                disposeTextAreas(fixture.table());
            }
        });
    }

    @Test
    void refusesToCreateJavaFxControlsOffFxThread() {
        TableData data = tableWithCells(1);
        assertThrows(IllegalStateException.class,
                () -> MarkdownTableView.create(data, STYLE));
    }

    private static TableData tableWithCells(int count) {
        List<List<TableCellData>> rows = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            rows.add(List.of(cell("cell-" + index, index == 0, TableCellAlignment.LEFT)));
        }
        return new TableData(rows, 1);
    }

    private static TableCellData cell(
            String text, boolean header, TableCellAlignment alignment) {
        return new TableCellData(text, header, alignment);
    }

    private static List<InlineCssTextArea> findTextAreas(Node node) {
        List<InlineCssTextArea> areas = new ArrayList<>();
        collectTextAreas(node, areas);
        return areas;
    }

    private static void collectTextAreas(Node node, List<InlineCssTextArea> areas) {
        if (node instanceof InlineCssTextArea area) areas.add(area);
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                collectTextAreas(child, areas);
            }
        }
    }

    private static void disposeTextAreas(Node node) {
        for (InlineCssTextArea area : findTextAreas(node)) area.dispose();
    }

    private static boolean hasStyleClass(Node node, String styleClass) {
        if (node.getStyleClass().contains(styleClass)) return true;
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                if (hasStyleClass(child, styleClass)) return true;
            }
        }
        return false;
    }

    private static GridPane findGrid(Node node) {
        if (node instanceof GridPane grid) return grid;
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                GridPane found = findGrid(child);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static StackPane cellSurface(GridPane grid, int row, int column) {
        return grid.getChildren().stream()
                .filter(StackPane.class::isInstance)
                .map(StackPane.class::cast)
                .filter(surface -> gridIndex(GridPane.getRowIndex(surface)) == row
                        && gridIndex(GridPane.getColumnIndex(surface)) == column)
                .findFirst()
                .orElseThrow();
    }

    private static int gridIndex(Integer index) {
        return index == null ? 0 : index;
    }

    private static void firePrimaryMouse(
            StackPane cell, javafx.event.EventType<MouseEvent> type, boolean primaryDown) {
        Bounds bounds = cell.getBoundsInLocal();
        Point2D localPoint = new Point2D(
                (bounds.getMinX() + bounds.getMaxX()) / 2,
                (bounds.getMinY() + bounds.getMaxY()) / 2);
        Point2D scenePoint = cell.localToScene(localPoint);
        Point2D screenPoint = cell.localToScreen(localPoint);
        MouseEvent event = new MouseEvent(
                cell, cell,
                type,
                scenePoint.getX(), scenePoint.getY(),
                screenPoint.getX(), screenPoint.getY(),
                MouseButton.PRIMARY, 1,
                false, false, false, false,
                primaryDown, false, false,
                false, false, type != MouseEvent.MOUSE_DRAGGED,
                new PickResult(cell, localPoint.getX(), localPoint.getY()));
        Event.fireEvent(cell, event);
    }

    private static void fireShortcutCopy(GridPane grid) {
        boolean mac = System.getProperty("os.name", "").toLowerCase().contains("mac");
        Event.fireEvent(grid, new KeyEvent(
                KeyEvent.KEY_PRESSED, "c", "c", KeyCode.C,
                false, !mac, false, mac));
    }

    private static void runFx(ThrowingRunnable action) throws Exception {
        callFx(() -> {
            action.run();
            return null;
        });
    }

    private static <T> T callFx(Callable<T> action) throws Exception {
        if (Platform.isFxApplicationThread()) return action.call();
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                result.set(action.call());
            } catch (Throwable thrown) {
                failure.set(thrown);
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "FX 操作超时");
        if (failure.get() != null) {
            if (failure.get() instanceof Exception exception) throw exception;
            if (failure.get() instanceof Error error) throw error;
            throw new RuntimeException(failure.get());
        }
        return result.get();
    }

    private record LayoutFixture(Stage stage, StackPane root, Region table) {}

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
