package com.javaclaw.desktop.component;

import java.util.Objects;

import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ListView;
import javafx.scene.control.SplitPane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import com.javaclaw.desktop.component.PlatformComponentFactory.ActionSize;
import com.javaclaw.desktop.component.PlatformComponentFactory.ActionStyle;
import com.javaclaw.desktop.component.PlatformComponentFactory.FeedbackKind;

/**
 * 左侧虚拟化列表和右侧详情组成的通用主从布局。
 *
 * @param <T> 列表元素类型
 */
public final class ListDetailPane<T> extends SplitPane {
    private final PlatformComponentFactory components = new PlatformComponentFactory();
    private final ListView<T> list = new ListView<>();
    private final StackPane detail = new StackPane();
    private Projection projection = Projection.EMPTY;

    /** 创建默认 35% 列表、65% 详情的主从布局。 */
    public ListDetailPane() {
        list.getStyleClass().add("platform-data-list");
        detail.getStyleClass().add("platform-detail-pane");
        getItems().addAll(list, detail);
        setDividerPositions(0.35);
        getStyleClass().add("platform-list-detail");
        showEmpty("暂无详情", "选择左侧条目后查看详情。");
    }

    /**
     * 返回列表，供页面配置 cell 与选择监听器。
     *
     * @return 虚拟化列表
     */
    public ListView<T> list() {
        return list;
    }

    /**
     * 显示读取中状态并暂停列表交互。
     *
     * <p>方法不清空列表数据，因此读取失败后仍可以保留上一份权威快照。
     *
     * @param detailText 当前正在读取的内容说明
     */
    public void showLoading(String detailText) {
        showFeedback(Projection.LOADING, FeedbackKind.LOADING, "正在读取", detailText, null);
        list.setDisable(true);
    }

    /**
     * 显示无数据或未选择状态。
     *
     * @param title 简短结论
     * @param detailText 下一步或原因说明
     */
    public void showEmpty(String title, String detailText) {
        showFeedback(Projection.EMPTY, FeedbackKind.EMPTY, title, detailText, null);
        list.setDisable(false);
    }

    /**
     * 显示可重试的读取失败状态。
     *
     * <p>失败状态不清空列表，用户仍可查看已成功读取的旧快照。
     *
     * @param title 简短中文错误结论
     * @param detailText 可执行的恢复说明
     * @param retry 用户点击“重试”时执行的读取动作
     */
    public void showError(String title, String detailText, Runnable retry) {
        showFeedback(Projection.ERROR, FeedbackKind.ERROR, title, detailText, Objects.requireNonNull(retry, "retry"));
        list.setDisable(false);
    }

    /**
     * 显示已就绪的详情内容。
     *
     * @param node 详情节点
     */
    public void showReady(Node node) {
        detail.getChildren().setAll(Objects.requireNonNull(node, "node"));
        list.setDisable(false);
        projection = Projection.READY;
    }

    /**
     * 替换详情内容。
     *
     * <p>该简化入口等价于 {@link #showReady(Node)}。
     *
     * @param node 详情节点
     */
    public void showDetail(Node node) {
        showReady(node);
    }

    /**
     * 返回当前主从布局投影。
     *
     * @return 当前 loading、empty、error 或 ready 状态
     */
    public Projection projection() {
        return projection;
    }

    private void showFeedback(
            Projection nextProjection, FeedbackKind kind, String title, String detailText, Runnable retry) {
        VBox feedback = components.feedback(kind, title, detailText);
        if (retry != null) {
            Button retryButton = components.action("重试", ActionStyle.SOFT, ActionSize.COMPACT);
            retryButton.setOnAction(ignored -> retry.run());
            retryButton.getStyleClass().add("platform-list-detail-retry");
            feedback.getChildren().add(retryButton);
        }
        detail.getChildren().setAll(feedback);
        projection = nextProjection;
    }

    /** 主从布局当前显示的页面状态。 */
    public enum Projection {
        /** 正在读取数据。 */
        LOADING,
        /** 暂无数据或尚未选择记录。 */
        EMPTY,
        /** 读取失败，可以由用户重试。 */
        ERROR,
        /** 列表和详情已就绪。 */
        READY
    }
}
