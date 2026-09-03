package com.javaclaw.desktop.component;

import java.util.Objects;
import java.util.Optional;

import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/** 设置与管理中心复用的左侧导航、面包屑和内容区骨架。 */
public final class ManagementPageShell extends BorderPane {
    private final Label currentPage = new Label();
    private final VBox navigation = new VBox();
    private final BorderPane content = new BorderPane();
    private final StackPane scopeSlot = new StackPane();
    private final StackPane actionSlot = new StackPane();
    private boolean pageInteractionEnabled = true;

    /**
     * 创建管理中心骨架。
     *
     * @param title 窗口导航标题
     */
    public ManagementPageShell(String title) {
        Objects.requireNonNull(title, "title");
        getStyleClass().addAll("platform-window", "management-center");
        setLeft(createNavigation(title));
        setCenter(createContent());
    }

    /**
     * 替换左侧导航内容。
     *
     * @param node 搜索框、列表或其他导航节点
     */
    public void setNavigationContent(Node node) {
        if (navigation.getChildren().size() > 1) {
            navigation.getChildren().remove(1, navigation.getChildren().size());
        }
        navigation.getChildren().add(Objects.requireNonNull(node, "node"));
        VBox.setVgrow(node, javafx.scene.layout.Priority.ALWAYS);
    }

    /**
     * 设置当前页面标题和内容。
     *
     * @param title 页面标题
     * @param node 页面内容
     */
    public void showPage(String title, Node node) {
        currentPage.setText(Objects.requireNonNull(title, "title"));
        Node checked = Objects.requireNonNull(node, "node");
        checked.setDisable(!pageInteractionEnabled);
        content.setCenter(checked);
    }

    /**
     * 设置面包屑下方的平台作用域控件。
     *
     * @param node Workspace 选择或其他全局作用域控件
     */
    public void setScopeContent(Node node) {
        scopeSlot.getChildren().setAll(Objects.requireNonNull(node, "node"));
    }

    /**
     * 设置固定在可滚动页面之外的动作栏；空值会清理上一页动作。
     *
     * @param node 当前页面的顶层动作栏
     */
    public void setActionContent(Optional<? extends Node> node) {
        Optional<? extends Node> checked = Objects.requireNonNull(node, "node");
        actionSlot.getChildren().clear();
        checked.ifPresent(value -> actionSlot.getChildren().add(Objects.requireNonNull(value, "action content")));
        actionSlot.setDisable(!pageInteractionEnabled);
        boolean visible = checked.isPresent();
        actionSlot.setVisible(visible);
        actionSlot.setManaged(visible);
    }

    /**
     * 设置当前页面是否允许交互，同时保留页面节点和未保存草稿。
     *
     * <p>Workspace 目录处于 LOADING、ERROR 或固定 Workspace 已失效时，窗口用此门禁阻止页面提交；本地全局页面不受该门禁影响。
     *
     * @param enabled 是否允许页面和动作栏接收交互
     */
    public void setPageInteractionEnabled(boolean enabled) {
        pageInteractionEnabled = enabled;
        Node page = content.getCenter();
        if (page != null) {
            page.setDisable(!enabled);
        }
        actionSlot.setDisable(!enabled);
    }

    /**
     * 返回左侧导航容器，供窗口追加本地状态。
     *
     * @return 导航容器
     */
    public VBox navigation() {
        return navigation;
    }

    private VBox createNavigation(String title) {
        Label heading = new Label(title);
        heading.getStyleClass().add("modal-left-title");
        VBox.setMargin(heading, new Insets(20, 18, 14, 18));
        navigation.getChildren().add(heading);
        navigation.getStyleClass().addAll("modal-left-pane", "platform-navigation");
        return navigation;
    }

    private BorderPane createContent() {
        Label rootLabel = new Label("设置与管理中心");
        rootLabel.getStyleClass().add("settings-crumb-root");
        Label separator = new Label("›");
        separator.getStyleClass().add("settings-crumb-sep");
        currentPage.getStyleClass().add("settings-crumb-cur");
        Region spacer = new Region();
        HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS);
        HBox breadcrumb = new HBox(8, rootLabel, separator, currentPage, spacer);
        breadcrumb.getStyleClass().add("settings-crumb");
        scopeSlot.getStyleClass().add("management-scope-slot");
        content.setTop(new VBox(breadcrumb, scopeSlot));
        actionSlot.getStyleClass().add("management-action-slot");
        actionSlot.setVisible(false);
        actionSlot.setManaged(false);
        content.setBottom(actionSlot);
        content.getStyleClass().add("settings-content-area");
        return content;
    }
}
