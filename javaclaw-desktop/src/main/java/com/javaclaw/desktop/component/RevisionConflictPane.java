package com.javaclaw.desktop.component;

import java.util.Objects;

import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import com.javaclaw.desktop.component.PlatformComponentFactory.ActionSize;
import com.javaclaw.desktop.component.PlatformComponentFactory.ActionStyle;

/** 写入 revision 冲突时保留草稿并让用户选择刷新或比较的反馈组件。 */
public final class RevisionConflictPane extends VBox {
    private final Label detail = new Label();

    /**
     * 创建默认隐藏的冲突提示。
     *
     * @param reload 重新读取权威数据
     * @param compare 比较本地草稿和权威数据
     */
    public RevisionConflictPane(Runnable reload, Runnable compare) {
        Runnable checkedReload = Objects.requireNonNull(reload, "reload");
        Runnable checkedCompare = Objects.requireNonNull(compare, "compare");
        PlatformComponentFactory components = new PlatformComponentFactory();
        Label title = new Label("数据已在其他位置更新");
        title.getStyleClass().add("platform-conflict-title");
        detail.setWrapText(true);
        detail.getStyleClass().add("platform-conflict-detail");
        Button compareButton = components.action("比较差异", ActionStyle.GHOST, ActionSize.COMPACT);
        compareButton.setOnAction(event -> checkedCompare.run());
        Button reloadButton = components.action("重新读取", ActionStyle.SOFT, ActionSize.COMPACT);
        reloadButton.setOnAction(event -> checkedReload.run());
        getChildren().addAll(title, detail, new HBox(8, compareButton, reloadButton));
        getStyleClass().add("platform-revision-conflict");
        hide();
    }

    /**
     * 显示冲突 revision。
     *
     * @param expected 提交时预期 revision
     * @param actual 服务端当前 revision
     */
    public void show(long expected, long actual) {
        detail.setText("本地草稿基于 v" + expected + "，当前数据为 v" + actual + "。草稿仍保留。");
        setVisible(true);
        setManaged(true);
    }

    /**
     * 服务端没有返回当前 revision 时只展示已知的预期值。
     *
     * @param expected 提交时预期 revision
     */
    public void showUnknownActual(long expected) {
        detail.setText("本地草稿基于 v" + expected + "，服务端已存在其他版本。草稿仍保留，重新读取后可比较。");
        setVisible(true);
        setManaged(true);
    }

    /**
     * 使用调用页面提供的领域说明显示冲突，适用于没有本地草稿的状态动作。
     *
     * @param message 不包含敏感 payload 的冲突说明
     */
    public void showMessage(String message) {
        String checked = Objects.requireNonNull(message, "message").strip();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException("message 不能为空");
        }
        detail.setText(checked);
        setVisible(true);
        setManaged(true);
    }

    /** 隐藏冲突反馈，不清理页面草稿。 */
    public void hide() {
        setVisible(false);
        setManaged(false);
    }
}
