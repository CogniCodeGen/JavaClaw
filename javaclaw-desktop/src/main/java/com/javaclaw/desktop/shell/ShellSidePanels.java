package com.javaclaw.desktop.shell;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import javafx.beans.value.ChangeListener;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;

import com.javaclaw.desktop.state.DesktopState;

/** 主壳侧区的展示策略；请求身份只用于提醒去重，决议状态始终来自服务端快照。 */
final class ShellSidePanels implements AutoCloseable {
    private final BorderPane root;
    private final VBox sidebar;
    private final VBox progress;
    private final Button toggle;
    private final ChangeListener<Number> resize = (observable, before, after) -> layout();
    private final javafx.event.EventHandler<KeyEvent> escape = this::escape;
    private Set<String> pending = Set.of();
    private Optional<String> inputError = Optional.empty();
    private boolean sidebarWanted = true;
    private boolean progressWanted;
    private boolean automatic;
    private boolean document;
    private Node returnFocus;
    private Runnable pendingContent = () -> {};

    ShellSidePanels(BorderPane root, VBox sidebar, VBox progress, Button toggle) {
        this.root = root;
        this.sidebar = sidebar;
        this.progress = progress;
        this.toggle = toggle;
        root.widthProperty().addListener(resize);
        root.addEventHandler(KeyEvent.KEY_PRESSED, escape);
        layout();
    }

    void render(DesktopState state) {
        Set<String> next = new HashSet<>();
        state.interaction()
                .pendingApprovals()
                .forEach(value -> next.add("approval:" + value.request().id()));
        state.interaction()
                .inputs()
                .pendingRequests()
                .forEach(value -> next.add("input:" + value.request().id()));
        Optional<String> nextError = state.interaction().inputs().error();
        boolean arrived = !pending.containsAll(next) || (nextError.isPresent() && !nextError.equals(inputError));
        inputError = nextError;
        pending = Set.copyOf(next);
        toggle.setText(pending.isEmpty() ? "待处理" : "待处理 " + pending.size());
        toggle.setAccessibleText(pending.isEmpty() ? "打开待处理事项" : pending.size() + " 项等待处理，打开待处理事项");
        if (arrived && !progressWanted) {
            rememberFocus();
            document = false;
            pendingContent.run();
            progressWanted = true;
            automatic = true;
        } else if (pending.isEmpty() && inputError.isEmpty() && automatic && !document) {
            progressWanted = false;
            automatic = false;
        }
        layout();
    }

    void toggleSidebar() {
        // 窄窗中明确打开导航，暂时让出右区；用户的导航偏好不因自动审批而丢失。
        if (!sidebar.isVisible()) {
            sidebarWanted = true;
            if (narrow()) {
                progressWanted = false;
                automatic = false;
            }
        } else {
            sidebarWanted = false;
        }
        layout();
    }

    void toggleProgress() {
        if (progressWanted && document) {
            document = false;
            pendingContent.run();
            automatic = false;
        } else if (progressWanted) {
            closeProgress();
        } else {
            rememberFocus();
            document = false;
            pendingContent.run();
            progressWanted = true;
            automatic = false;
            layout();
        }
    }

    void onPending(Runnable action) {
        pendingContent = action;
    }

    void openDocument() {
        rememberFocus();
        document = true;
        automatic = false;
        progressWanted = true;
        layout();
    }

    void selectDocument(boolean selected) {
        document = selected;
        automatic = false;
    }

    void documentClosed() {
        document = false;
        if (pending.isEmpty() && inputError.isEmpty()) {
            closeProgress();
        }
    }

    private void closeProgress() {
        Node focused = root.getScene() == null ? null : root.getScene().getFocusOwner();
        // 用户在展开后继续编辑时，关闭侧区不应把焦点退回更早的触发控件。
        if (focused != null && !inside(focused, progress)) {
            returnFocus = focused;
        }
        progressWanted = false;
        automatic = false;
        layout();
        Node target = returnFocus != null && returnFocus.getScene() == root.getScene() ? returnFocus : toggle;
        if (!target.isVisible() || target.isDisabled()) {
            target = toggle;
        }
        target.requestFocus();
    }

    private void rememberFocus() {
        returnFocus = root.getScene() == null ? toggle : root.getScene().getFocusOwner();
        if (returnFocus != null && inside(returnFocus, progress)) {
            returnFocus = toggle;
        }
    }

    private void escape(KeyEvent event) {
        // 子菜单先消费 Escape；主壳只关闭右区，不把 Escape 解释为取消 Turn 或拒绝审批。
        if (!event.isConsumed() && event.getCode() == KeyCode.ESCAPE && progressWanted) {
            closeProgress();
            event.consume();
        }
    }

    private static boolean inside(Node node, Node parent) {
        for (Node current = node; current != null; current = current.getParent()) {
            if (current == parent) {
                return true;
            }
        }
        return false;
    }

    private boolean narrow() {
        return root.getWidth() > 0 && root.getWidth() < 1200;
    }

    private void layout() {
        visible(progress, progressWanted);
        visible(sidebar, sidebarWanted && !(progressWanted && narrow()));
        toggle.pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("selected"), progressWanted);
    }

    private static void visible(Node node, boolean value) {
        node.setVisible(value);
        node.setManaged(value);
    }

    @Override
    public void close() {
        root.widthProperty().removeListener(resize);
        root.removeEventHandler(KeyEvent.KEY_PRESSED, escape);
    }
}
