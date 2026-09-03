package com.javaclaw.desktop.view;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

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
import javafx.util.StringConverter;

import com.javaclaw.desktop.component.PlatformComponentFactory;
import com.javaclaw.desktop.component.PlatformComponentFactory.ActionSize;
import com.javaclaw.desktop.component.PlatformComponentFactory.ActionStyle;
import com.javaclaw.extension.spi.ViewAction;
import com.javaclaw.extension.spi.ViewAttachmentPolicy;
import com.javaclaw.extension.spi.ViewCondition;
import com.javaclaw.extension.spi.ViewConditionOperator;
import com.javaclaw.extension.spi.ViewField;
import com.javaclaw.extension.spi.ViewFieldType;
import com.javaclaw.extension.spi.ViewFieldValidation;
import com.javaclaw.extension.spi.ViewFormField;
import com.javaclaw.extension.spi.ViewOption;
import com.javaclaw.extension.spi.ViewOptionSource;
import com.javaclaw.extension.spi.ViewSchema;
import com.javaclaw.extension.spi.ViewStructuredListField;

/** ViewSchema v2 表单的初值、dirty、条件显示和类型校验实现。 */
final class ViewFormRenderer {
    private final PlatformComponentFactory components;

    ViewFormRenderer(PlatformComponentFactory components) {
        this.components = Objects.requireNonNull(components, "components");
    }

    Node render(
            ViewSchema.Form form,
            ViewData data,
            ViewInteractionHandler interactions,
            ViewCommandBindingResolver commandBindings) {
        GridPane grid = new GridPane();
        grid.getStyleClass().add("platform-form-grid");
        Map<String, ViewFormInputState> fields = createFields(form.fields(), data, grid, interactions);
        Map<com.javaclaw.extension.spi.ViewBinding, ViewFormInputState> fieldBindings = new LinkedHashMap<>();
        fields.values().forEach(state -> fieldBindings.put(state.field.binding(), state));

        Label feedback = new Label();
        feedback.setWrapText(true);
        feedback.setManaged(false);
        feedback.setVisible(false);
        feedback.getStyleClass().addAll("sec-hint", "platform-form-feedback");
        Button submit = components.action(form.submit().label(), ActionStyle.PRIMARY, ActionSize.NORMAL);
        submit.setDisable(true);
        FormActionContext context = new FormActionContext(form.id(), form.submit(), commandBindings);
        Runnable refresh = () -> refresh(context, fields, fieldBindings, data, submit, interactions);
        fields.values().forEach(state -> state.observe(refresh));
        commandBindings.observe(refresh);
        submit.setOnAction(event -> submit(form.submit(), fields, feedback, interactions, commandBindings));
        refresh.run();

        HBox actions = new HBox(10, feedback, submit);
        actions.setAlignment(Pos.CENTER_RIGHT);
        HBox.setHgrow(feedback, Priority.ALWAYS);
        actions.getStyleClass().add("platform-action-bar");
        return components.section(form.title(), grid, actions);
    }

    private Map<String, ViewFormInputState> createFields(
            List<? extends ViewFormField> definitions,
            ViewData data,
            GridPane grid,
            ViewInteractionHandler interactions) {
        LinkedHashMap<String, ViewFormInputState> result = new LinkedHashMap<>();
        int row = 0;
        for (ViewFormField field : definitions) {
            Node input = input(field, data, interactions);
            Label label = fieldLabel(field);
            GridPane.setHgrow(input, Priority.ALWAYS);
            grid.add(label, 0, row);
            grid.add(input, 1, row++);
            Object initial = field instanceof ViewField scalar && scalar.type() == ViewFieldType.CHOICE
                    ? initialText(scalar, data)
                    : typedValue(field, input);
            result.put(field.name(), new ViewFormInputState(field, label, input, initial));
        }
        configureDynamicChoices(result, data);
        return java.util.Collections.unmodifiableMap(result);
    }

    private Label fieldLabel(ViewFormField field) {
        boolean required =
                switch (field) {
                    case ViewField scalar -> scalar.validation().required();
                    case ViewStructuredListField structured -> structured.minRows() > 0;
                };
        Label label = new Label(field.label() + (required ? " *" : ""));
        label.getStyleClass().addAll("settings-label", "platform-field-label");
        return label;
    }

    private Node input(ViewFormField field, ViewData data, ViewInteractionHandler interactions) {
        Node input =
                switch (field) {
                    case ViewField scalar -> scalarInput(scalar, data, interactions);
                    case ViewStructuredListField structured ->
                        new ViewStructuredListControl(structured, data.value(structured.binding()), data, components);
                };
        input.setAccessibleText(field.label());
        ((Region) input).setMaxWidth(Double.MAX_VALUE);
        return input;
    }

    private Node scalarInput(ViewField field, ViewData data, ViewInteractionHandler interactions) {
        return switch (field.type()) {
            case BOOLEAN -> checkBox(field, data);
            case CHOICE -> choice(field, data);
            case MULTILINE -> multiline(field, data);
            case ATTACHMENT -> attachment(field, data, interactions);
            case NUMBER, TEXT -> textInput(new TextField(), field, data);
        };
    }

    private ViewAttachmentFieldControl attachment(ViewField field, ViewData data, ViewInteractionHandler interactions) {
        ViewAttachmentPolicy policy = field.validation().attachment().orElseThrow();
        return new ViewAttachmentFieldControl(
                field.label(), policy, data.value(field.binding()), interactions, components);
    }

    private CheckBox checkBox(ViewField field, ViewData data) {
        CheckBox input = new CheckBox();
        input.getStyleClass().add("settings-checkbox");
        input.setSelected(Boolean.parseBoolean(initialText(field, data)));
        return input;
    }

    private ComboBox<ViewOption> choice(ViewField field, ViewData data) {
        ComboBox<ViewOption> input = new ComboBox<>();
        input.getStyleClass().add("settings-combo");
        input.setConverter(new StringConverter<>() {
            @Override
            public String toString(ViewOption option) {
                return option == null ? "" : option.label();
            }

            @Override
            public ViewOption fromString(String value) {
                throw new UnsupportedOperationException("ViewSchema choice is not editable");
            }
        });
        input.getItems().setAll(field.options());
        String selected = initialText(field, data);
        input.getItems().stream()
                .filter(option -> option.value().equals(selected))
                .findFirst()
                .ifPresent(input::setValue);
        return input;
    }

    private void configureDynamicChoices(Map<String, ViewFormInputState> states, ViewData data) {
        states.values().stream()
                .filter(state -> state.field instanceof ViewField scalar
                        && scalar.optionSource().isPresent())
                .forEach(state -> configureDynamicChoice(state, states, data));
    }

    private void configureDynamicChoice(
            ViewFormInputState state, Map<String, ViewFormInputState> states, ViewData data) {
        ViewField field = (ViewField) state.field;
        ViewOptionSource source = field.optionSource().orElseThrow();
        @SuppressWarnings("unchecked")
        ComboBox<ViewOption> choice = (ComboBox<ViewOption>) state.input;
        boolean[] initialized = {false};
        Runnable refresh = () -> {
            String selected = initialized[0]
                    ? Optional.ofNullable(choice.getValue())
                            .map(ViewOption::value)
                            .orElse("")
                    : Objects.toString(state.initial, "");
            List<ViewOption> options =
                    ViewDynamicOptions.resolve(field.options(), source, data, input -> inputValue(states.get(input)));
            choice.getItems().setAll(options);
            choice.setValue(options.stream()
                    .filter(option -> option.value().equals(selected))
                    .findFirst()
                    .orElse(null));
            choice.setDisable(options.isEmpty());
            initialized[0] = true;
        };
        source.filter()
                .map(filter -> states.get(filter.inputField()))
                .ifPresent(dependency -> observe(dependency.input, refresh));
        refresh.run();
    }

    private Object inputValue(ViewFormInputState state) {
        ViewFormInputState checked = Objects.requireNonNull(state, "动态选项依赖字段");
        return typedValue(checked.field, checked.input);
    }

    private void observe(Node input, Runnable refresh) {
        javafx.beans.InvalidationListener listener = ignored -> refresh.run();
        if (input instanceof CheckBox checkBox) {
            checkBox.selectedProperty().addListener(listener);
        } else if (input instanceof ComboBox<?> comboBox) {
            comboBox.valueProperty().addListener(listener);
        } else if (input instanceof TextInputControl text) {
            text.textProperty().addListener(listener);
        }
    }

    private TextArea multiline(ViewField field, ViewData data) {
        TextArea input = textInput(new TextArea(), field, data);
        input.setWrapText(true);
        input.setPrefRowCount(5);
        return input;
    }

    private <T extends TextInputControl> T textInput(T input, ViewField field, ViewData data) {
        input.setPromptText(field.label());
        input.setText(initialText(field, data));
        input.getStyleClass().add("settings-field");
        return input;
    }

    private String initialText(ViewField field, ViewData data) {
        Object bound = data.value(field.binding());
        return bound == null ? field.initialValue().orElse("") : Objects.toString(bound, "");
    }

    private void refresh(
            FormActionContext context,
            Map<String, ViewFormInputState> fields,
            Map<com.javaclaw.extension.spi.ViewBinding, ViewFormInputState> bindings,
            ViewData data,
            Button submit,
            ViewInteractionHandler interactions) {
        fields.values().forEach(state -> state.visible(visible(state.field, bindings, data)));
        boolean dirty = fields.values().stream()
                .filter(ViewFormInputState::visible)
                .anyMatch(state -> !same(state.initial, typedValue(state.field, state.input)));
        boolean pending = fields.values().stream().anyMatch(ViewFormInputState::pending);
        Optional<String> unavailable = context.commandBindings().unavailable(context.action(), Map.of());
        submit.setDisable(!dirty || pending || unavailable.isPresent());
        submit.setAccessibleHelp(unavailable.orElse(""));
        interactions.dirty(context.formId(), dirty);
    }

    private boolean visible(
            ViewFormField field,
            Map<com.javaclaw.extension.spi.ViewBinding, ViewFormInputState> bindings,
            ViewData data) {
        Optional<ViewCondition> condition = field.visibleWhen();
        if (condition.isEmpty()) {
            return true;
        }
        ViewCondition value = condition.orElseThrow();
        ViewFormInputState state = bindings.get(value.binding());
        Object actual = state == null ? data.value(value.binding()) : typedValue(state.field, state.input);
        boolean equals = Objects.toString(actual, "").equals(value.expectedValue());
        return value.operator() == ViewConditionOperator.EQUALS ? equals : !equals;
    }

    private void submit(
            ViewAction action,
            Map<String, ViewFormInputState> fields,
            Label feedback,
            ViewInteractionHandler interactions,
            ViewCommandBindingResolver commandBindings) {
        Optional<String> failure = fields.values().stream()
                .filter(ViewFormInputState::visible)
                .map(this::validate)
                .flatMap(Optional::stream)
                .findFirst();
        if (failure.isPresent()) {
            showFeedback(feedback, failure.orElseThrow());
            return;
        }
        LinkedHashMap<String, Object> arguments = new LinkedHashMap<>();
        fields.values().stream()
                .filter(ViewFormInputState::visible)
                .forEach(state -> arguments.put(state.field.name(), typedValue(state.field, state.input)));
        try {
            ViewCommandInvocation invocation = commandBindings.invocation(action, Map.copyOf(arguments), Map.of());
            feedback.setManaged(false);
            feedback.setVisible(false);
            interactions.execute(invocation);
        } catch (IllegalArgumentException resolutionFailure) {
            showFeedback(feedback, resolutionFailure.getMessage());
        }
    }

    private Optional<String> validate(ViewFormInputState state) {
        return switch (state.field) {
            case ViewField scalar -> validateScalar(scalar, typedValue(scalar, state.input));
            case ViewStructuredListField ignored -> ((ViewStructuredListControl) state.input).validationFailure();
        };
    }

    private Optional<String> validateScalar(ViewField field, Object value) {
        ViewFieldValidation validation = field.validation();
        String text = Objects.toString(value, "");
        if (validation.required() && text.isBlank()) {
            return Optional.of(field.label() + "不能为空");
        }
        if (value == null || text.isBlank()) {
            return Optional.empty();
        }
        if (validation.minLength().filter(minimum -> text.length() < minimum).isPresent()) {
            return Optional.of(field.label() + "长度不足");
        }
        if (validation.maxLength().filter(maximum -> text.length() > maximum).isPresent()) {
            return Optional.of(field.label() + "长度超出限制");
        }
        if (field.type() == com.javaclaw.extension.spi.ViewFieldType.NUMBER) {
            return validateNumber(field, value, validation);
        }
        return Optional.empty();
    }

    private Optional<String> validateNumber(ViewField field, Object value, ViewFieldValidation validation) {
        if (!(value instanceof BigDecimal number)) {
            return Optional.of(field.label() + "必须是数值");
        }
        if (validation
                .minimum()
                .filter(minimum -> number.compareTo(minimum) < 0)
                .isPresent()) {
            return Optional.of(field.label() + "小于允许的最小值");
        }
        if (validation
                .maximum()
                .filter(maximum -> number.compareTo(maximum) > 0)
                .isPresent()) {
            return Optional.of(field.label() + "大于允许的最大值");
        }
        return Optional.empty();
    }

    private Object typedValue(ViewFormField field, Node input) {
        return switch (field) {
            case ViewField scalar -> scalarValue(scalar, input);
            case ViewStructuredListField ignored -> ((ViewStructuredListControl) input).value();
        };
    }

    private Object scalarValue(ViewField field, Node input) {
        return switch (field.type()) {
            case BOOLEAN -> ((CheckBox) input).isSelected();
            case CHOICE ->
                Optional.ofNullable(((ComboBox<?>) input).getValue())
                        .map(ViewOption.class::cast)
                        .map(ViewOption::value)
                        .orElse("");
            case NUMBER -> decimal(((TextInputControl) input).getText());
            case ATTACHMENT ->
                ((ViewAttachmentFieldControl) input)
                        .value()
                        .<Object>map(attachment -> attachment)
                        .orElse("");
            case MULTILINE, TEXT -> ((TextInputControl) input).getText();
        };
    }

    private Object decimal(String value) {
        if (value.isBlank()) {
            return "";
        }
        try {
            return new BigDecimal(value).stripTrailingZeros();
        } catch (NumberFormatException failure) {
            return value;
        }
    }

    private boolean same(Object first, Object second) {
        if (first instanceof BigDecimal left && second instanceof BigDecimal right) {
            return left.compareTo(right) == 0;
        }
        return Objects.equals(first, second);
    }

    private void showFeedback(Label feedback, String message) {
        feedback.setText(message);
        feedback.setManaged(true);
        feedback.setVisible(true);
    }

    private record FormActionContext(String formId, ViewAction action, ViewCommandBindingResolver commandBindings) {}
}
