package com.javaclaw.desktop;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

import javafx.beans.binding.Bindings;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.value.ObservableBooleanValue;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/** 复用原版 modal、卡片和按钮 CSS 的表单部件；没有运行时、数据库或 JSON 依赖。 */
final class ManagementForms {
    private static final String COMMAND_STATE = "javaclaw.management.command-state";
    private static final String IGNORE_DIRTY = "javaclaw.management.ignore-dirty";

    private ManagementForms() {}

    static Button button(String text, Runnable action) {
        return button(text, UiActionKind.SECONDARY, action);
    }

    static Button button(String text, UiActionKind kind, Runnable action) {
        var button = new Button(text);
        button.setId("action-" + controlId(text));
        kind.apply(button);
        button.setOnAction(ignored -> action.run());
        button.setAccessibleText(text);
        return button;
    }

    static Button command(String text, ManagementViewModel model, Runnable action) {
        return command(text, UiActionKind.SECONDARY, model, action);
    }

    static Button command(String text, UiActionKind kind, ManagementViewModel model, Runnable action) {
        var button = button(text, kind, () -> {});
        button.setOnAction(ignored -> model.invokeCommand(button, action));
        commandState(button).install(button);
        return button;
    }

    /** 为命令按钮增加页面或字段级禁用条件，同时保留该按钮自己的运行状态。 */
    static void guard(Button button, ObservableBooleanValue disabled) {
        BooleanProperty guard = commandState(button).guard();
        guard.unbind();
        guard.bind(disabled);
    }

    static void guard(Button button, boolean disabled) {
        BooleanProperty guard = commandState(button).guard();
        guard.unbind();
        guard.set(disabled);
    }

    /** 返回按钮对应的操作是否仍在等待确定结果。 */
    static boolean commandRunning(Button button) {
        return commandState(button).running().get();
    }

    /** 标记独立操作区域，使页面 dirty 快照不把凭据、登录等独立提交内容混入普通配置。 */
    static <T extends Node> T independent(T node) {
        node.getProperties().put(IGNORE_DIRTY, Boolean.TRUE);
        return node;
    }

    static boolean ignoresDirty(Node node) {
        return Boolean.TRUE.equals(node.getProperties().get(IGNORE_DIRTY));
    }

    static void setCommandRunning(Button button, boolean running) {
        commandState(button).running().set(running);
    }

    static TextField text(String value, String prompt) {
        var field = new TextField(value == null ? "" : value);
        field.setPromptText(prompt);
        field.setAccessibleText(prompt);
        field.setMaxWidth(620);
        field.getStyleClass().add("management-field-medium");
        return field;
    }

    static TextArea area(String value, int rows) {
        var field = new TextArea(value == null ? "" : value);
        field.setWrapText(true);
        field.setPrefRowCount(rows);
        field.setMaxWidth(820);
        field.getStyleClass().add("management-field-multiline");
        return field;
    }

    static Label hint(String text) {
        var value = new Label(text);
        value.setWrapText(true);
        value.getStyleClass().add("sec-hint");
        return value;
    }

    static VBox field(String name, Node input) {
        var label = new Label(name);
        label.getStyleClass().add("form-label");
        label.setLabelFor(input);
        if (input.getId() == null || input.getId().isBlank()) {
            input.setId("field-" + controlId(name));
        }
        if (input.getAccessibleText() == null || input.getAccessibleText().isBlank()) {
            input.setAccessibleText(name);
        }
        return new VBox(5, label, input);
    }

    static <T> ComboBox<T> choices(List<T> values, Function<T, String> label, T selected) {
        var result = new ComboBox<T>();
        result.getItems().setAll(values);
        result.setCellFactory(ignored -> cell(label));
        result.setButtonCell(cell(label));
        result.setMaxWidth(520);
        result.getStyleClass().add("management-field-medium");
        result.setValue(selected);
        return result;
    }

    static <T> ListView<T> list(Function<T, String> label) {
        return list(label, "暂无内容", "完成创建或导入后，内容会显示在这里。");
    }

    static <T> ListView<T> list(Function<T, String> label, String emptyTitle, String emptyHint) {
        var result = new ListView<T>();
        result.setId("list-" + controlId(emptyTitle));
        result.setAccessibleText(emptyTitle);
        result.setCellFactory(ignored -> cell(label));
        result.setPlaceholder(emptyState("✦", emptyTitle, emptyHint));
        result.getStyleClass().add("management-list");
        return result;
    }

    private static <T> ListCell<T> cell(Function<T, String> label) {
        return new ListCell<>() {
            private final Label text = new Label();

            {
                text.setWrapText(true);
                text.maxWidthProperty().bind(widthProperty().subtract(22));
            }

            @Override
            protected void updateItem(T value, boolean empty) {
                super.updateItem(value, empty);
                text.setText(empty || value == null ? "" : label.apply(value));
                setText(null);
                setGraphic(empty || value == null ? null : text);
            }
        };
    }

    static BorderPane split(Node left, Node detail) {
        var rail = new VBox(left);
        rail.setPrefWidth(244);
        rail.setMinWidth(210);
        rail.getStyleClass().addAll("modal-left-pane", "management-rail");
        VBox.setVgrow(left, Priority.ALWAYS);
        var result = new BorderPane(detail);
        result.setLeft(rail);
        result.getStyleClass().add("modal-content-area");
        return result;
    }

    static ScrollPane scroll(Node content) {
        var result = new ScrollPane(content);
        result.setFitToWidth(true);
        result.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        result.setPrefViewportHeight(580);
        result.getStyleClass().add("modal-content-area");
        return result;
    }

    static VBox form(Node... fields) {
        var result = new VBox(12, fields);
        result.setPadding(new Insets(20, 24, 20, 24));
        result.setAlignment(Pos.TOP_LEFT);
        result.setMaxWidth(920);
        result.getStyleClass().addAll("domain-form", "management-form");
        return result;
    }

    static VBox section(String title, Node... fields) {
        var heading = new Label(title);
        heading.getStyleClass().add("management-section-title");
        var result = new VBox(10, heading);
        result.getChildren().addAll(fields);
        result.getStyleClass().add("management-section");
        return result;
    }

    static VBox emptyState(String icon, String title, String description) {
        var mark = new Label(icon);
        mark.getStyleClass().add("empty-state-icon");
        var heading = new Label(title);
        heading.getStyleClass().add("empty-state-text");
        var detail = new Label(description);
        detail.setWrapText(true);
        detail.setMaxWidth(360);
        detail.getStyleClass().add("empty-state-hint");
        var result = new VBox(8, mark, heading, detail);
        result.setAlignment(javafx.geometry.Pos.CENTER);
        result.getStyleClass().add("management-empty-state");
        return result;
    }

    static HBox actions(Node... buttons) {
        var result = new HBox(8, buttons);
        result.setPadding(new Insets(10, 12, 10, 12));
        result.getStyleClass().add("management-action-bar");
        return result;
    }

    /**
     * 创建内容可滚动、关键操作始终可见的详情编辑器。
     *
     * <p>保存、运行和危险操作不能随长表单滚出最小窗口的可达区域，因此页脚位于 ScrollPane 之外。
     *
     * @param content 可滚动的表单正文
     * @param buttons 固定在页脚的操作按钮
     * @return 使用原版内容区和页脚视觉令牌的编辑器
     */
    static BorderPane editor(Node content, Node... buttons) {
        var footer = actions(buttons);
        footer.getStyleClass().add("management-fixed-actions");
        var result = new BorderPane(scroll(content));
        result.setBottom(footer);
        result.getStyleClass().addAll("management-editor", "modal-content-area");
        ManagementEditSession session = ManagementEditSession.find(content);
        if (session != null) {
            var message = new Label("服务端版本已经变化，本地草稿仍保留，尚未覆盖任何内容。");
            message.setWrapText(true);
            message.getStyleClass().add("management-conflict-message");
            var inspect = button("查看服务端版本摘要", UiActionKind.GHOST, () -> {
                Throwable conflict = session.conflict();
                showText(
                        result,
                        "服务端版本摘要",
                        conflict == null
                                ? "当前没有 revision 冲突。"
                                : "服务端拒绝了旧修订写入。\n\n"
                                        + java.util.Objects.toString(conflict.getMessage(), "服务端版本已更新")
                                        + "\n\n本地草稿尚未丢失；重新加载前可以复制需要保留的内容。");
            });
            var reload = button("放弃本地并重新加载", UiActionKind.DANGER, () -> {
                if (confirm(result, session.model(), "放弃本地修改", "将清除当前本地草稿并读取服务端最新版本。此操作不会覆盖服务端。")) {
                    session.reloadServerVersion();
                }
            });
            var conflict = new VBox(8, message, actions(inspect, reload));
            conflict.getStyleClass().add("management-conflict-banner");
            conflict.visibleProperty().bind(session.conflictProperty().isNotNull());
            conflict.managedProperty().bind(conflict.visibleProperty());
            result.setTop(conflict);
        }
        return result;
    }

    static boolean confirm(Node owner, ManagementViewModel model, String title, String text) {
        String acceptText = JavaFxDesktopDialogGateway.dangerous(title) ? title : "确认";
        return model.dialogs().confirm(owner, title, title, text, acceptText);
    }

    static void showText(Node owner, String title, String text) {
        var dialog = new Dialog<Void>();
        dialog.setTitle(title);
        var content = area(text, 20);
        content.setEditable(false);
        content.setPrefWidth(720);
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        style(owner, dialog);
        dialog.showAndWait();
    }

    static <T> void review(
            Node owner,
            ManagementViewModel model,
            String title,
            List<T> values,
            Function<T, String> caption,
            Function<T, String> body,
            Consumer<T> accept,
            Consumer<T> reject) {
        var dialog = new Dialog<Void>();
        dialog.setTitle(title);
        var list = list(caption);
        list.getItems().setAll(values);
        list.setPrefWidth(230);
        var text = area("选择一条记录查看完整内容", 20);
        text.setEditable(false);
        var approve = button("采用所选版本", () -> {
            T value = list.getSelectionModel().getSelectedItem();
            if (value != null && confirm(owner, model, "确认采用", "将按当前修订保存；内容已变化时服务端会拒绝覆盖。")) {
                accept.accept(value);
                dialog.close();
            }
        });
        var decline = button("拒绝提案", () -> {
            T value = list.getSelectionModel().getSelectedItem();
            if (value != null) {
                reject.accept(value);
                dialog.close();
            }
        });
        decline.setVisible(reject != null);
        decline.setManaged(reject != null);
        approve.disableProperty()
                .bind(list.getSelectionModel().selectedItemProperty().isNull());
        decline.disableProperty()
                .bind(list.getSelectionModel().selectedItemProperty().isNull());
        list.getSelectionModel().selectedItemProperty().addListener((ignored, old, value) -> {
            if (value != null) {
                text.setText(body.apply(value));
            }
        });
        var content = new HBox(14, list, new VBox(12, text, actions(approve, decline)));
        content.setPrefWidth(780);
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        style(owner, dialog);
        dialog.showAndWait();
    }

    static <T> void edit(Node owner, String title, Node form, java.util.function.Supplier<T> value, Consumer<T> save) {
        var dialog = new Dialog<T>();
        dialog.setTitle(title);
        var submit = new ButtonType("保存", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(submit, ButtonType.CANCEL);
        dialog.getDialogPane().setContent(form);
        var validated = new java.util.concurrent.atomic.AtomicReference<T>();
        dialog.getDialogPane().lookupButton(submit).addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            try {
                validated.set(value.get());
            } catch (RuntimeException failure) {
                event.consume();
                var error =
                        new Alert(Alert.AlertType.ERROR, java.util.Objects.toString(failure.getMessage(), "表单内容无效"));
                error.setTitle("请检查输入");
                style(owner, error);
                error.showAndWait();
            }
        });
        dialog.setResultConverter(button -> button == submit ? validated.get() : null);
        dialog.setResizable(true);
        style(owner, dialog);
        dialog.showAndWait().ifPresent(save);
    }

    static CheckBox check(String name, boolean selected) {
        var result = new CheckBox(name);
        result.setSelected(selected);
        return result;
    }

    static javafx.scene.control.Spinner<Integer> number(int value, int maximum) {
        var result = new javafx.scene.control.Spinner<Integer>(0, maximum, Math.max(0, value));
        result.setEditable(true);
        result.getEditor().focusedProperty().addListener((ignored, old, focused) -> {
            if (!focused) {
                try {
                    result.commitValue();
                } catch (RuntimeException invalid) {
                    result.getEditor().setText(result.getValue().toString());
                }
            }
        });
        result.setMaxWidth(220);
        result.getStyleClass().add("management-field-number");
        return result;
    }

    static void style(Node owner, Dialog<?> dialog) {
        dialog.initOwner(owner.getScene().getWindow());
        dialog.getDialogPane().setGraphic(null);
        String theme = owner.getScene().getRoot().getStyleClass().stream()
                .filter(value -> value.startsWith("theme-"))
                .map(value -> value.substring(6))
                .findFirst()
                .orElse(DesktopTheme.DEFAULT_ID);
        DesktopTheme.apply(dialog.getDialogPane(), theme);
        for (ButtonType type : dialog.getDialogPane().getButtonTypes()) {
            if (!(dialog.getDialogPane().lookupButton(type) instanceof Button button)) {
                continue;
            }
            button.setId("dialog-" + type.getButtonData().name().toLowerCase(java.util.Locale.ROOT));
            button.setAccessibleText(type.getText());
            if (button.getStyleClass().stream().noneMatch(value -> value.startsWith("jc-btn-"))) {
                actionKind(type.getButtonData()).apply(button);
            }
        }
    }

    private static UiActionKind actionKind(ButtonBar.ButtonData data) {
        return switch (data) {
            case OK_DONE, YES, FINISH, APPLY -> UiActionKind.PRIMARY;
            case CANCEL_CLOSE, NO, BACK_PREVIOUS -> UiActionKind.GHOST;
            default -> UiActionKind.SECONDARY;
        };
    }

    private static String controlId(String value) {
        var result = new StringBuilder();
        String source = value == null ? "control" : value.strip();
        for (int offset = 0; offset < source.length(); ) {
            int codePoint = source.codePointAt(offset);
            if (Character.isLetterOrDigit(codePoint)) {
                result.appendCodePoint(Character.toLowerCase(codePoint));
            } else if (!result.isEmpty() && result.charAt(result.length() - 1) != '-') {
                result.append('-');
            }
            offset += Character.charCount(codePoint);
        }
        while (!result.isEmpty() && result.charAt(result.length() - 1) == '-') {
            result.setLength(result.length() - 1);
        }
        return result.isEmpty() ? "control" : result.toString();
    }

    private static CommandState commandState(Button button) {
        Object present = button.getProperties().get(COMMAND_STATE);
        if (present instanceof CommandState state) {
            return state;
        }
        var state = new CommandState();
        button.getProperties().put(COMMAND_STATE, state);
        return state;
    }

    private record CommandState(BooleanProperty running, BooleanProperty guard) {
        private CommandState() {
            this(new SimpleBooleanProperty(), new SimpleBooleanProperty());
        }

        private void install(Button button) {
            button.disableProperty().bind(Bindings.or(running, guard));
        }
    }
}
