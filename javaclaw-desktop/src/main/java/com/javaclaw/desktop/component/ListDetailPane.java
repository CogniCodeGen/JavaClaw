package com.javaclaw.desktop.component;

import java.util.Objects;

import javafx.scene.Node;
import javafx.scene.control.ListView;
import javafx.scene.control.SplitPane;
import javafx.scene.layout.StackPane;

/**
 * 左侧虚拟化列表和右侧详情组成的通用主从布局。
 *
 * @param <T> 列表元素类型
 */
public final class ListDetailPane<T> extends SplitPane {
    private final ListView<T> list = new ListView<>();
    private final StackPane detail = new StackPane();

    /** 创建默认 35% 列表、65% 详情的主从布局。 */
    public ListDetailPane() {
        list.getStyleClass().add("platform-data-list");
        detail.getStyleClass().add("platform-detail-pane");
        getItems().addAll(list, detail);
        setDividerPositions(0.35);
        getStyleClass().add("platform-list-detail");
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
     * 替换详情内容。
     *
     * @param node 详情节点
     */
    public void showDetail(Node node) {
        detail.getChildren().setAll(Objects.requireNonNull(node, "node"));
    }
}
