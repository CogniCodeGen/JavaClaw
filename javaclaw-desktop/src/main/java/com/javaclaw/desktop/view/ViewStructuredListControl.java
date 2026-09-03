package com.javaclaw.desktop.view;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import javafx.beans.InvalidationListener;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputControl;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

import com.javaclaw.desktop.component.PlatformComponentFactory;
import com.javaclaw.desktop.component.PlatformComponentFactory.ActionSize;
import com.javaclaw.desktop.component.PlatformComponentFactory.ActionStyle;
import com.javaclaw.extension.spi.ViewOption;
import com.javaclaw.extension.spi.ViewStructuredItemField;
import com.javaclaw.extension.spi.ViewStructuredListField;

/** 平台拥有的结构化列表编辑器；只创建固定 JavaFX 控件并提交有界标量对象。 */
final class ViewStructuredListControl extends VBox {
    private final ViewStructuredListField definition;
    private final ViewData data;
    private final PlatformComponentFactory components;
    private final List<RowEditor> rows = new ArrayList<>();
    private final VBox rowContainer = new VBox();
    private final Button addButton;
    private final ReadOnlyObjectWrapper<List<Map<String, Object>>> value = new ReadOnlyObjectWrapper<>(List.of());
    private long nextKey;

    ViewStructuredListControl(
            ViewStructuredListField definition, Object boundValue, ViewData data, PlatformComponentFactory components) {
        this.definition = Objects.requireNonNull(definition, "definition");
        this.data = Objects.requireNonNull(data, "data");
        this.components = Objects.requireNonNull(components, "components");
        getStyleClass().add("platform-form-grid");
        List<Map<String, Object>> initial = definition.normalizeRows(boundValue);
        initial.forEach(row -> rows.add(new RowEditor(row)));
        addButton = components.action("新增行", ActionStyle.SOFT, ActionSize.COMPACT);
        addButton.setAccessibleText(definition.label() + "新增行");
        addButton.setOnAction(ignored -> addRow());
        HBox actions = new HBox(addButton);
        actions.setAlignment(Pos.CENTER_RIGHT);
        actions.getStyleClass().add("platform-action-bar");
        getChildren().addAll(rowContainer, actions);
        renderRows();
        publish();
    }

    List<Map<String, Object>> value() {
        return value.get();
    }

    ReadOnlyObjectProperty<List<Map<String, Object>>> valueProperty() {
        return value.getReadOnlyProperty();
    }

    Optional<String> validationFailure() {
        return ViewStructuredListValidation.validate(definition, value());
    }

    private void addRow() {
        if (rows.size() >= definition.maxRows()) {
            return;
        }
        rows.add(new RowEditor(definition.newItem(newKey())));
        renderRows();
        publish();
    }

    private void removeRow(int index) {
        if (rows.size() <= definition.minRows()) {
            return;
        }
        rows.remove(index);
        renderRows();
        publish();
    }

    private void moveRow(int index, int offset) {
        int target = index + offset;
        if (target < 0 || target >= rows.size()) {
            return;
        }
        Collections.swap(rows, index, target);
        renderRows();
        publish();
    }

    private void renderRows() {
        List<Node> nodes = new ArrayList<>(rows.size());
        for (int index = 0; index < rows.size(); index++) {
            nodes.add(rowNode(index));
        }
        rowContainer.getChildren().setAll(nodes);
        addButton.setDisable(rows.size() >= definition.maxRows());
    }

    private Node rowNode(int index) {
        Button up = action("上移", () -> moveRow(index, -1));
        Button down = action("下移", () -> moveRow(index, 1));
        Button remove = components.action("移除", ActionStyle.DANGER, ActionSize.COMPACT);
        remove.setOnAction(ignored -> removeRow(index));
        up.setDisable(index == 0);
        down.setDisable(index == rows.size() - 1);
        remove.setDisable(rows.size() <= definition.minRows());
        HBox actions = new HBox(up, down, remove);
        actions.setAlignment(Pos.CENTER_RIGHT);
        actions.getStyleClass().add("platform-action-bar");
        return components.section("第 " + (index + 1) + " 项", rows.get(index).grid, actions);
    }

    private Button action(String label, Runnable action) {
        Button button = components.action(label, ActionStyle.GHOST, ActionSize.COMPACT);
        button.setOnAction(ignored -> action.run());
        return button;
    }

    private void publish() {
        value.set(rows.stream().map(RowEditor::value).toList());
    }

    private String newKey() {
        String key;
        boolean exists;
        do {
            key = "item-" + ++nextKey;
            String candidate = key;
            exists = rows.stream()
                    .map(RowEditor::value)
                    .anyMatch(row -> candidate.equals(row.get(definition.itemKey())));
        } while (exists);
        return key;
    }

    private final class RowEditor {
        private final String key;
        private final GridPane grid = new GridPane();
        private final Map<ViewStructuredItemField, Node> inputs = new LinkedHashMap<>();

        private RowEditor(Map<String, Object> initial) {
            key = Objects.toString(initial.get(definition.itemKey()));
            grid.getStyleClass().add("platform-form-grid");
            int row = 0;
            for (ViewStructuredItemField field : definition.itemFields()) {
                Label label = new Label(field.label() + (field.validation().required() ? " *" : ""));
                label.getStyleClass().addAll("settings-label", "platform-field-label");
                Node input = input(field, initial.get(field.name()));
                GridPane.setHgrow(input, Priority.ALWAYS);
                grid.add(label, 0, row);
                grid.add(input, 1, row++);
                inputs.put(field, input);
                observe(input);
            }
            configureDynamicChoices(initial);
        }

        private Map<String, Object> value() {
            LinkedHashMap<String, Object> result = new LinkedHashMap<>();
            result.put(definition.itemKey(), key);
            inputs.forEach((field, input) -> result.put(field.name(), typedValue(field, input)));
            return Collections.unmodifiableMap(result);
        }

        private void observe(Node input) {
            observe(input, ViewStructuredListControl.this::publish);
        }

        private void observe(Node input, Runnable action) {
            InvalidationListener listener = ignored -> action.run();
            if (input instanceof CheckBox checkBox) {
                checkBox.selectedProperty().addListener(listener);
            } else if (input instanceof ComboBox<?> comboBox) {
                comboBox.valueProperty().addListener(listener);
            } else {
                ((TextInputControl) input).textProperty().addListener(listener);
            }
        }

        private void configureDynamicChoices(Map<String, Object> initial) {
            inputs.forEach((field, input) -> {
                if (field.optionSource().isEmpty()) {
                    return;
                }
                @SuppressWarnings("unchecked")
                ComboBox<ViewOption> choice = (ComboBox<ViewOption>) input;
                boolean[] initialized = {false};
                Runnable refresh = () -> {
                    String selected = initialized[0]
                            ? Optional.ofNullable(choice.getValue())
                                    .map(ViewOption::value)
                                    .orElse("")
                            : Objects.toString(initial.get(field.name()), "");
                    List<ViewOption> options = ViewDynamicOptions.resolve(
                            field.options(),
                            field.optionSource().orElseThrow(),
                            data,
                            dependency -> value().get(dependency));
                    choice.getItems().setAll(options);
                    choice.setValue(options.stream()
                            .filter(option -> option.value().equals(selected))
                            .findFirst()
                            .orElse(null));
                    choice.setDisable(options.isEmpty());
                    initialized[0] = true;
                };
                field.optionSource()
                        .orElseThrow()
                        .filter()
                        .map(filter -> dependencyInput(filter.inputField()))
                        .ifPresent(dependency -> observe(dependency, refresh));
                refresh.run();
            });
        }

        private Node dependencyInput(String fieldName) {
            return inputs.entrySet().stream()
                    .filter(entry -> entry.getKey().name().equals(fieldName))
                    .map(Map.Entry::getValue)
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("动态选项依赖字段未渲染: " + fieldName));
        }
    }

    private Node input(ViewStructuredItemField field, Object initial) {
        Node input =
                switch (field.type()) {
                    case BOOLEAN -> booleanInput(initial);
                    case CHOICE -> choiceInput(field, initial);
                    case MULTILINE, TEXT_LIST -> areaInput(field, initial);
                    case NUMBER, TEXT -> textInput(field, initial);
                };
        input.setAccessibleText(field.label());
        ((Region) input).setMaxWidth(Double.MAX_VALUE);
        return input;
    }

    private CheckBox booleanInput(Object initial) {
        CheckBox input = new CheckBox();
        input.getStyleClass().add("settings-checkbox");
        input.setSelected(Boolean.TRUE.equals(initial));
        return input;
    }

    private ComboBox<ViewOption> choiceInput(ViewStructuredItemField field, Object initial) {
        ComboBox<ViewOption> input = new ComboBox<>();
        input.getStyleClass().add("settings-combo");
        input.setConverter(optionConverter());
        input.getItems().setAll(field.options());
        input.getItems().stream()
                .filter(option -> option.value().equals(initial))
                .findFirst()
                .ifPresent(input::setValue);
        return input;
    }

    private StringConverter<ViewOption> optionConverter() {
        return new StringConverter<>() {
            @Override
            public String toString(ViewOption option) {
                return option == null ? "" : option.label();
            }

            @Override
            public ViewOption fromString(String value) {
                throw new UnsupportedOperationException("结构化列表选项不可自由编辑");
            }
        };
    }

    private TextArea areaInput(ViewStructuredItemField field, Object initial) {
        TextArea input = new TextArea();
        input.setWrapText(true);
        input.setPrefRowCount(4);
        input.setText(
                field.type() == com.javaclaw.extension.spi.ViewStructuredItemType.TEXT_LIST
                        ? String.join("\n", textValues(initial))
                        : Objects.toString(initial, ""));
        decorateTextInput(input, field.label());
        return input;
    }

    private TextField textInput(ViewStructuredItemField field, Object initial) {
        TextField input = new TextField(Objects.toString(initial, ""));
        decorateTextInput(input, field.label());
        return input;
    }

    private void decorateTextInput(TextInputControl input, String label) {
        input.setPromptText(label);
        input.getStyleClass().add("settings-field");
    }

    private List<String> textValues(Object value) {
        if (!(value instanceof List<?> values) || values.stream().anyMatch(entry -> !(entry instanceof String))) {
            throw new IllegalArgumentException("结构化文本列表只允许字符串");
        }
        return values.stream().map(String.class::cast).toList();
    }

    private Object typedValue(ViewStructuredItemField field, Node input) {
        return switch (field.type()) {
            case BOOLEAN -> ((CheckBox) input).isSelected();
            case CHOICE ->
                Optional.ofNullable(((ComboBox<?>) input).getValue())
                        .map(ViewOption.class::cast)
                        .map(ViewOption::value)
                        .orElse("");
            case NUMBER -> decimal(((TextInputControl) input).getText());
            case TEXT_LIST -> textList(((TextInputControl) input).getText());
            case MULTILINE, TEXT -> ((TextInputControl) input).getText();
        };
    }

    private Object decimal(String text) {
        if (text.isBlank()) {
            return "";
        }
        try {
            return new BigDecimal(text).stripTrailingZeros();
        } catch (NumberFormatException failure) {
            return text;
        }
    }

    private List<String> textList(String text) {
        return text.isEmpty() ? List.of() : List.copyOf(Arrays.asList(text.split("\\R", -1)));
    }
}
