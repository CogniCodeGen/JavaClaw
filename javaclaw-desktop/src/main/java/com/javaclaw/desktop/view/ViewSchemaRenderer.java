package com.javaclaw.desktop.view;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import javafx.beans.property.SimpleStringProperty;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import com.javaclaw.desktop.component.PlatformComponentFactory;
import com.javaclaw.desktop.component.PlatformComponentFactory.ActionSize;
import com.javaclaw.desktop.component.PlatformComponentFactory.ActionStyle;
import com.javaclaw.desktop.component.PlatformComponentFactory.FeedbackKind;
import com.javaclaw.extension.spi.ViewAction;
import com.javaclaw.extension.spi.ViewSchema;
import com.javaclaw.extension.spi.ViewSelectionMode;

/** 把受限 ViewSchema v2 映射为平台拥有且符合 JavaClaw 设计系统的 JavaFX 控件。 */
public final class ViewSchemaRenderer {
    private final PlatformComponentFactory components = new PlatformComponentFactory();
    private final ViewFormRenderer forms = new ViewFormRenderer(components);
    private final ViewGraphRenderer graphs = new ViewGraphRenderer(components);

    /**
     * 渲染一个页面。
     *
     * @param schema 通过安全策略的页面
     * @param data 已由 SDK 标准 query 获取的数据
     * @param interactions 平台拥有的有限交互
     * @return 平台控件树
     */
    public Node render(ViewSchema schema, ViewData data, ViewInteractionHandler interactions) {
        ViewSchema checked = ViewSchemaPolicy.requireSupported(schema);
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(interactions, "interactions");
        ViewCommandBindingResolver bindings = new ViewCommandBindingResolver(checked, data);
        VBox page = components.page(checked.title());
        checked.nodes().stream()
                .map(node -> renderNode(node, data, interactions, bindings))
                .forEach(page.getChildren()::add);
        return page;
    }

    /**
     * 取消一个已渲染页面内尚未完成的本地 Attachment 上传。
     *
     * @param rendered 即将离页或被替换的控件树；为空时不处理
     */
    public void cancelUploads(Node rendered) {
        if (rendered instanceof ViewAttachmentFieldControl attachment) {
            attachment.cancelUpload();
        }
        if (rendered instanceof Parent parent) {
            parent.getChildrenUnmodifiable().forEach(this::cancelUploads);
        }
    }

    Node renderNode(
            ViewSchema.Node node,
            ViewData data,
            ViewInteractionHandler interactions,
            ViewCommandBindingResolver bindings) {
        return switch (node) {
            case ViewSchema.Form form -> forms.render(form, data, interactions, bindings);
            case ViewSchema.ListView list -> list(list, data, interactions, bindings);
            case ViewSchema.Table table -> table(table, data, interactions, bindings);
            case ViewSchema.Card card -> card(card, interactions, bindings);
            case ViewSchema.Progress progress -> progress(progress, data.source(progress.sourceId()));
            case ViewSchema.Timeline timeline -> timeline(timeline, data.source(timeline.sourceId()));
            case ViewSchema.Markdown markdown ->
                textBlock(markdown.title(), Objects.toString(data.value(markdown.source()), ""));
            case ViewSchema.Code code -> code(code, data);
            case ViewSchema.Artifact artifact -> artifact(artifact, data);
            case ViewSchema.Graph graph -> graph(graph, data, interactions);
        };
    }

    private Node graph(ViewSchema.Graph graph, ViewData data, ViewInteractionHandler interactions) {
        var view = graphs.create(graph);
        view.apply(data, interactions);
        return view.node();
    }

    private Node list(
            ViewSchema.ListView schema,
            ViewData data,
            ViewInteractionHandler interactions,
            ViewCommandBindingResolver bindings) {
        ViewData.Source source = data.source(schema.sourceId());
        ListView<Map<String, Object>> list = new ListView<>();
        list.getItems().setAll(source.rows());
        list.setCellFactory(ignored ->
                components.detailCell(row -> value(row, schema.titleField()), row -> value(row, schema.detailField())));
        list.setPlaceholder(components.feedback(FeedbackKind.EMPTY, "暂无内容", "扩展当前没有返回可展示的数据。"));
        list.setPrefHeight(Math.min(420, Math.max(120, source.rows().size() * 58.0)));
        list.getStyleClass().add("platform-data-list");
        configureSelection(
                list,
                schema.sourceId(),
                schema.keyField(),
                schema.selection(),
                source.selectedKey(),
                interactions,
                bindings);
        VBox body = new VBox(8, list, rowActions(schema.actions(), schema.sourceId(), list, interactions, bindings));
        return components.section(schema.title(), body, pagination(schema.sourceId(), source, interactions));
    }

    private Node table(
            ViewSchema.Table schema,
            ViewData data,
            ViewInteractionHandler interactions,
            ViewCommandBindingResolver bindings) {
        ViewData.Source source = data.source(schema.sourceId());
        TableView<Map<String, Object>> table = new TableView<>();
        for (ViewSchema.Column definition : schema.columns()) {
            TableColumn<Map<String, Object>, String> column = new TableColumn<>(definition.label());
            column.setCellValueFactory(cell -> new SimpleStringProperty(value(cell.getValue(), definition.field())));
            definition.width().ifPresent(column::setPrefWidth);
            table.getColumns().add(column);
        }
        table.getItems().setAll(source.rows());
        table.setPlaceholder(components.feedback(FeedbackKind.EMPTY, "暂无记录", "扩展当前没有返回表格数据。"));
        table.setPrefHeight(Math.min(520, Math.max(160, source.rows().size() * 34.0 + 42)));
        table.getStyleClass().add("platform-data-table");
        configureSelection(
                table,
                schema.sourceId(),
                schema.keyField(),
                schema.selection(),
                source.selectedKey(),
                interactions,
                bindings);
        VBox body = new VBox(8, table, rowActions(schema.actions(), schema.sourceId(), table, interactions, bindings));
        return components.section(schema.title(), body, pagination(schema.sourceId(), source, interactions));
    }

    private <T extends javafx.scene.control.Control> void configureSelection(
            T control,
            String sourceId,
            String keyField,
            ViewSelectionMode selection,
            Optional<String> selectedKey,
            ViewInteractionHandler interactions,
            ViewCommandBindingResolver bindings) {
        if (control instanceof ListView<?> rawList) {
            @SuppressWarnings("unchecked")
            ListView<Map<String, Object>> list = (ListView<Map<String, Object>>) rawList;
            list.getSelectionModel().setSelectionMode(SelectionMode.SINGLE);
            if (selection == ViewSelectionMode.SINGLE) {
                select(list, keyField, selectedKey);
                list.getSelectionModel().selectedItemProperty().addListener((ignored, previous, row) -> {
                    bindings.select(sourceId, row);
                    interactions.select(sourceId, key(row, keyField));
                });
            }
        } else if (control instanceof TableView<?> rawTable) {
            @SuppressWarnings("unchecked")
            TableView<Map<String, Object>> table = (TableView<Map<String, Object>>) rawTable;
            table.getSelectionModel().setSelectionMode(SelectionMode.SINGLE);
            if (selection == ViewSelectionMode.SINGLE) {
                select(table, keyField, selectedKey);
                table.getSelectionModel().selectedItemProperty().addListener((ignored, previous, row) -> {
                    bindings.select(sourceId, row);
                    interactions.select(sourceId, key(row, keyField));
                });
            }
        }
    }

    private void select(javafx.scene.control.Control control, String keyField, Optional<String> selectedKey) {
        if (selectedKey.isEmpty()) {
            return;
        }
        if (control instanceof ListView<?> rawList) {
            @SuppressWarnings("unchecked")
            ListView<Map<String, Object>> list = (ListView<Map<String, Object>>) rawList;
            select(list.getItems(), keyField, selectedKey.orElseThrow()).ifPresent(list.getSelectionModel()::select);
        } else if (control instanceof TableView<?> rawTable) {
            @SuppressWarnings("unchecked")
            TableView<Map<String, Object>> table = (TableView<Map<String, Object>>) rawTable;
            select(table.getItems(), keyField, selectedKey.orElseThrow()).ifPresent(table.getSelectionModel()::select);
        }
    }

    private Optional<Map<String, Object>> select(List<Map<String, Object>> rows, String keyField, String selectedKey) {
        return rows.stream()
                .filter(row -> selectedKey.equals(value(row, keyField)))
                .findFirst();
    }

    private Node rowActions(
            List<ViewAction> actions,
            String sourceId,
            javafx.scene.control.Control control,
            ViewInteractionHandler interactions,
            ViewCommandBindingResolver bindings) {
        HBox bar = new HBox(8);
        bar.setAlignment(Pos.CENTER_RIGHT);
        bar.getStyleClass().add("platform-action-bar");
        for (ViewAction action : actions) {
            Button button = actionButton(action);
            Runnable refresh = () -> refreshAction(button, action, selectedRow(control), bindings);
            if (control instanceof ListView<?> list) {
                list.getSelectionModel().selectedItemProperty().addListener((ignored, previous, row) -> refresh.run());
                button.setOnAction(event -> executeRow(
                        action, sourceId, list.getSelectionModel().getSelectedItem(), interactions, bindings));
            } else if (control instanceof TableView<?> table) {
                table.getSelectionModel().selectedItemProperty().addListener((ignored, previous, row) -> refresh.run());
                button.setOnAction(event -> executeRow(
                        action, sourceId, table.getSelectionModel().getSelectedItem(), interactions, bindings));
            }
            bindings.observe(refresh);
            refresh.run();
            bar.getChildren().add(button);
        }
        bar.setManaged(!actions.isEmpty());
        bar.setVisible(!actions.isEmpty());
        return bar;
    }

    private void executeRow(
            ViewAction action,
            String sourceId,
            Object selected,
            ViewInteractionHandler interactions,
            ViewCommandBindingResolver bindings) {
        @SuppressWarnings("unchecked")
        Map<String, Object> row = selected instanceof Map<?, ?> ? (Map<String, Object>) selected : Map.of();
        bindings.select(sourceId, row.isEmpty() ? null : row);
        interactions.execute(bindings.invocation(action, Map.of(), row));
    }

    private Node card(ViewSchema.Card card, ViewInteractionHandler interactions, ViewCommandBindingResolver bindings) {
        Label body = wrapping(card.body());
        body.getStyleClass().add("platform-body-text");
        VBox section = components.section(card.title(), body);
        if (!card.actions().isEmpty()) {
            HBox actions = new HBox(8);
            actions.getStyleClass().add("platform-action-bar");
            for (ViewAction action : card.actions()) {
                Button button = actionButton(action);
                Runnable refresh = () -> refreshAction(button, action, Map.of(), bindings);
                bindings.observe(refresh);
                refresh.run();
                button.setOnAction(event -> interactions.execute(bindings.invocation(action, Map.of(), Map.of())));
                actions.getChildren().add(button);
            }
            section.getChildren().add(actions);
        }
        return section;
    }

    private HBox pagination(String sourceId, ViewData.Source source, ViewInteractionHandler interactions) {
        Button previous = components.action("上一页", ActionStyle.GHOST, ActionSize.COMPACT);
        previous.setDisable(source.pageIndex() == 0);
        previous.setOnAction(event -> interactions.page(sourceId, ViewPageDirection.PREVIOUS));
        Label page = new Label("第 " + (source.pageIndex() + 1) + " 页");
        page.getStyleClass().add("sec-hint");
        Button next = components.action("下一页", ActionStyle.GHOST, ActionSize.COMPACT);
        next.setDisable(!source.hasMore());
        next.setOnAction(event -> interactions.page(sourceId, ViewPageDirection.NEXT));
        Button reload = components.action("刷新", ActionStyle.SOFT, ActionSize.COMPACT);
        reload.setOnAction(event -> interactions.reload());
        HBox bar = new HBox(8, previous, page, next, reload);
        bar.setAlignment(Pos.CENTER_RIGHT);
        bar.getStyleClass().add("platform-pagination");
        return bar;
    }

    private void refreshAction(
            Button button, ViewAction action, Map<String, Object> selectedRow, ViewCommandBindingResolver bindings) {
        Optional<String> failure = bindings.unavailable(action, selectedRow);
        button.setDisable(failure.isPresent());
        button.setAccessibleHelp(failure.orElse(""));
    }

    private Map<String, Object> selectedRow(javafx.scene.control.Control control) {
        Object selected =
                switch (control) {
                    case ListView<?> list -> list.getSelectionModel().getSelectedItem();
                    case TableView<?> table -> table.getSelectionModel().getSelectedItem();
                    default -> null;
                };
        if (!(selected instanceof Map<?, ?> row)) {
            return Map.of();
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> typed = (Map<String, Object>) row;
        return typed;
    }

    private Button actionButton(ViewAction action) {
        ActionStyle style = action.dangerous() ? ActionStyle.DANGER : ActionStyle.SOFT;
        return components.action(action.label(), style, ActionSize.COMPACT);
    }

    private Node progress(ViewSchema.Progress schema, ViewData.Source source) {
        Map<String, Object> row = source.rows().stream().findFirst().orElse(source.values());
        double number = number(row.get(schema.valueField()));
        ProgressBar bar = new ProgressBar(Math.max(0, Math.min(1, number)));
        bar.setMaxWidth(Double.MAX_VALUE);
        Label label = wrapping(value(row, schema.labelField()));
        label.getStyleClass().add("platform-detail-text");
        VBox content = new VBox(6, label, bar);
        content.getStyleClass().add("platform-progress");
        return components.section(schema.title(), content);
    }

    private Node timeline(ViewSchema.Timeline schema, ViewData.Source source) {
        VBox timeline = new VBox(8);
        timeline.getStyleClass().add("platform-timeline");
        if (source.rows().isEmpty()) {
            timeline.getChildren().add(components.feedback(FeedbackKind.EMPTY, "暂无时间线", "扩展当前没有返回时间线事件。"));
        } else {
            source.rows().stream().map(row -> timelineEntry(schema, row)).forEach(timeline.getChildren()::add);
        }
        return components.section(schema.title(), timeline);
    }

    private Node timelineEntry(ViewSchema.Timeline schema, Map<String, Object> row) {
        Label marker = new Label("●");
        marker.getStyleClass().add("platform-timeline-marker");
        Label time = new Label(value(row, schema.timeField()));
        time.getStyleClass().add("platform-timeline-time");
        Label content = wrapping(value(row, schema.contentField()));
        content.getStyleClass().add("platform-body-text");
        VBox copy = new VBox(3, time, content);
        HBox entry = new HBox(10, marker, copy);
        entry.getStyleClass().add("platform-timeline-entry");
        return entry;
    }

    private Node code(ViewSchema.Code schema, ViewData data) {
        TextArea area = new TextArea(Objects.toString(data.value(schema.source()), ""));
        area.setEditable(false);
        area.setWrapText(false);
        area.setPrefRowCount(12);
        area.getStyleClass().addAll("settings-field", "code-view");
        return components.section(schema.title(), area);
    }

    private Node artifact(ViewSchema.Artifact schema, ViewData data) {
        String reference = Objects.toString(data.value(schema.reference()), "");
        String mediaType = Objects.toString(data.value(schema.mediaType()), "");
        Label summary = wrapping(mediaType + " · " + reference);
        summary.setAccessibleText("内容寻址 Artifact；Desktop 不直接打开本地 URL");
        summary.getStyleClass().addAll("platform-body-text", "platform-artifact");
        return components.section(schema.title(), summary);
    }

    private Node textBlock(String title, String text) {
        Label label = wrapping(text);
        label.getStyleClass().add("markdown-view");
        return components.section(title, label);
    }

    private Label wrapping(String text) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.setMaxWidth(Double.MAX_VALUE);
        return label;
    }

    private Optional<String> key(Map<String, Object> row, String keyField) {
        if (row == null) {
            return Optional.empty();
        }
        String value = Objects.toString(row.get(keyField), "").strip();
        return value.isEmpty() ? Optional.empty() : Optional.of(value);
    }

    private String value(Map<String, Object> row, String field) {
        return Objects.toString(row.get(field), "");
    }

    private double number(Object value) {
        return value instanceof Number number ? number.doubleValue() : 0;
    }
}
