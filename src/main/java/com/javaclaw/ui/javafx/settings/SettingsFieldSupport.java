package com.javaclaw.ui.javafx.settings;

import com.javaclaw.application.error.ValidationException;
import javafx.scene.Node;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;

/** 设置表单的数值解析、错误样式和程序性装载守卫。 */
public final class SettingsFieldSupport {

    static final String LOADING_KEY = "javaclaw.settings.loading";

    private SettingsFieldSupport() {
    }

    static int integer(TextField field, int min, int max, String label) {
        clear(field);
        try {
            int value = Integer.parseInt(text(field));
            if (value < min || value > max) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException failure) {
            String range = max == Integer.MAX_VALUE
                    ? "请输入 ≥ " + min + " 的整数"
                    : "请输入 " + min + " ~ " + max + " 之间的整数";
            error(field, range);
            throw new ValidationException(label + "格式不正确");
        }
    }

    static double decimal(TextField field, double min, double max, String label) {
        clear(field);
        try {
            double value = Double.parseDouble(text(field));
            if (!Double.isFinite(value) || value < min || value > max) {
                throw new NumberFormatException();
            }
            return value;
        } catch (NumberFormatException failure) {
            error(field, "请输入 " + min + " ~ " + max + " 之间的数值");
            throw new ValidationException(label + "格式不正确");
        }
    }

    static void validateInteger(TextField field, int min, int max) {
        Runnable validate = () -> {
            if (field.isDisabled()) {
                clear(field);
                return;
            }
            try {
                int value = Integer.parseInt(text(field));
                if (value < min || value > max) throw new NumberFormatException();
                clear(field);
            } catch (NumberFormatException failure) {
                error(field, max == Integer.MAX_VALUE
                        ? "请输入 ≥ " + min + " 的整数"
                        : "请输入 " + min + " ~ " + max + " 之间的整数");
            }
        };
        field.textProperty().addListener((ignored, previous, value) -> validate.run());
        field.disabledProperty().addListener((ignored, previous, value) -> validate.run());
    }

    static void validateDecimal(TextField field, double min, double max) {
        Runnable validate = () -> {
            if (field.isDisabled()) {
                clear(field);
                return;
            }
            try {
                double value = Double.parseDouble(text(field));
                if (!Double.isFinite(value) || value < min || value > max) {
                    throw new NumberFormatException();
                }
                clear(field);
            } catch (NumberFormatException failure) {
                error(field, "请输入 " + min + " ~ " + max + " 之间的数值");
            }
        };
        field.textProperty().addListener((ignored, previous, value) -> validate.run());
        field.disabledProperty().addListener((ignored, previous, value) -> validate.run());
    }

    static void loading(Node root, Runnable loader) {
        root.getProperties().put(LOADING_KEY, Boolean.TRUE);
        try {
            loader.run();
        } finally {
            root.getProperties().remove(LOADING_KEY);
        }
    }

    public static boolean isLoading(Node root) {
        return Boolean.TRUE.equals(root.getProperties().get(LOADING_KEY));
    }

    static String failureMessage(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof java.util.concurrent.CompletionException
                || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank()
                ? current.getClass().getSimpleName() : message;
    }

    static String text(TextField field) {
        return field.getText() == null ? "" : field.getText().strip();
    }

    private static void error(TextField field, String message) {
        if (!field.getStyleClass().contains("field-error")) {
            field.getStyleClass().add("field-error");
        }
        field.setTooltip(new Tooltip(message));
    }

    private static void clear(TextField field) {
        field.getStyleClass().remove("field-error");
        field.setTooltip(null);
    }
}
