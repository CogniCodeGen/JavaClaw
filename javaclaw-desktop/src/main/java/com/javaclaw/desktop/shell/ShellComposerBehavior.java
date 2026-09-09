package com.javaclaw.desktop.shell;

import javafx.beans.value.ChangeListener;
import javafx.css.PseudoClass;
import javafx.event.EventHandler;
import javafx.scene.control.Button;
import javafx.scene.control.TextArea;
import javafx.scene.control.Tooltip;
import javafx.scene.input.InputMethodEvent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;

/** 输入区只响应本地编辑；组合输入未提交时不能触发发送，尺寸测量不读取 SDK 或正文历史。 */
final class ShellComposerBehavior implements AutoCloseable {
    private final TextArea input;
    private final VBox card;
    private final Button send;
    private final Runnable changed;
    private boolean composing;
    private final Text measure = new Text();
    private final ChangeListener<String> text = (observable, before, after) -> edited();
    private final ChangeListener<Number> width = (observable, before, after) -> resize();
    private final ChangeListener<javafx.scene.text.Font> font = (observable, before, after) -> resize();
    private final ChangeListener<Boolean> focus = (observable, before, after) -> focused(after);
    private final EventHandler<KeyEvent> key = this::key;
    private final EventHandler<InputMethodEvent> composition =
            event -> composing = !event.getComposed().isEmpty();

    ShellComposerBehavior(TextArea input, VBox card, Button send, Runnable changed) {
        this.input = input;
        this.card = card;
        this.send = send;
        this.changed = changed;
        input.textProperty().addListener(text);
        input.widthProperty().addListener(width);
        input.fontProperty().addListener(font);
        input.focusedProperty().addListener(focus);
        input.addEventFilter(InputMethodEvent.INPUT_METHOD_TEXT_CHANGED, composition);
        input.addEventFilter(KeyEvent.KEY_PRESSED, key);
        input.setTooltip(new Tooltip("Enter 发送，Shift + Enter 换行"));
        input.setAccessibleText("消息输入框；Enter 发送，Shift 加 Enter 换行");
        resize();
    }

    private void edited() {
        resize();
        changed.run();
    }

    private void resize() {
        measure.setFont(input.getFont());
        measure.setWrappingWidth(Math.max(80, input.getWidth() - 32));
        String value = input.getText();
        // 测量只用于高度上限；长草稿无需逐字排版第二遍，真正正文仍由 TextArea 完整保存。
        if (value.length() > 4096) {
            setHeight(240);
            return;
        }
        measure.setText(value.isEmpty() ? "M\nM" : value + "\n");
        double height =
                Math.min(240, Math.max(60, Math.ceil(measure.getLayoutBounds().getHeight()) + 20));
        setHeight(height);
    }

    private void setHeight(double height) {
        if (Math.abs(input.getPrefHeight() - height) > 0.5) {
            input.setPrefHeight(height);
        }
    }

    private void focused(boolean value) {
        card.pseudoClassStateChanged(PseudoClass.getPseudoClass("composer-focused"), value);
        if (!value) {
            composing = false;
        }
    }

    private void key(KeyEvent event) {
        if (event.getCode() == KeyCode.ENTER && !event.isAltDown()) {
            if (composing) {
                // 选词中的回车只能提交输入法，不能发送草稿或插入额外换行。
                event.consume();
                return;
            }
            if (event.isShiftDown()) {
                if (input.isEditable() && !input.isDisabled()) {
                    input.replaceSelection("\n");
                }
            } else if (send.isVisible() && !send.isDisabled()) {
                send.fire();
            }
            event.consume();
        }
    }

    @Override
    public void close() {
        input.textProperty().removeListener(text);
        input.widthProperty().removeListener(width);
        input.fontProperty().removeListener(font);
        input.focusedProperty().removeListener(focus);
        input.removeEventFilter(InputMethodEvent.INPUT_METHOD_TEXT_CHANGED, composition);
        input.removeEventFilter(KeyEvent.KEY_PRESSED, key);
    }
}
