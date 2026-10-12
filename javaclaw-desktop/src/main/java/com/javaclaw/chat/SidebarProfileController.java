package com.javaclaw.chat;

import com.javaclaw.platform.fx.FxDispatcher;
import javafx.fxml.FXML;
import javafx.geometry.Side;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Objects;

/**
 * 协调侧边栏个人菜单的展开、导航和待处理徽章。
 *
 * <p>菜单结构完全由 FXML 描述。导航回调在下一次 FX pulse 执行，避免
 * {@link javafx.scene.control.CustomMenuItem} 自动关闭与页面切换相互重入。</p>
 */
public final class SidebarProfileController implements AutoCloseable {

    private final FxDispatcher fx;

    @FXML private VBox root;
    @FXML private Button profileMenuButton;
    @FXML private ContextMenu profileMenu;
    @FXML private Label profileMenuBadgeLabel;
    @FXML private HBox skillNavRow;
    @FXML private HBox mcpNavRow;
    @FXML private HBox scheduleNavRow;
    @FXML private HBox knowledgeNavRow;
    @FXML private HBox memoryNavRow;
    @FXML private HBox workflowNavRow;
    @FXML private HBox taskNavRow;
    @FXML private HBox pluginNavRow;
    @FXML private HBox settingsNavRow;
    @FXML private Label skillNavBadge;
    @FXML private Label scheduleNavBadge;
    @FXML private Label taskNavBadge;

    private List<HBox> menuRows = List.of();
    private Runnable onOpenSettings;
    private Runnable onOpenSkillCenter;
    private Runnable onOpenMemoryCenter;
    private Runnable onOpenScheduler;
    private Runnable onOpenKnowledgeBase;
    private Runnable onOpenTaskManager;
    private Runnable onOpenWorkflowCenter;
    private Runnable onOpenMcp;
    private Runnable onOpenPluginCenter;
    private boolean menuAutoHiding;
    private int scheduleBadgeCount;
    private int skillBadgeCount;
    private int taskBadgeCount;
    private boolean closed;

    @Autowired
    public SidebarProfileController(FxDispatcher fx) {
        this.fx = Objects.requireNonNull(fx, "fx");
    }

    @FXML
    private void initialize() {
        menuRows = List.of(skillNavRow, mcpNavRow, scheduleNavRow, knowledgeNavRow,
                memoryNavRow, workflowNavRow, taskNavRow, pluginNavRow, settingsNavRow);
        for (HBox row : menuRows) {
            row.prefWidthProperty().bind(profileMenuButton.widthProperty().subtract(16));
        }
    }

    public VBox getRoot() {
        return root;
    }

    @FXML
    private void onProfileMenuShowing() {
        profileMenuButton.getTooltip().setText("收起个人菜单");
        if (!profileMenuButton.getStyleClass().contains("sidebar-profile-menu-button-open")) {
            profileMenuButton.getStyleClass().add("sidebar-profile-menu-button-open");
        }
    }

    @FXML
    private void onProfileMenuHidden() {
        profileMenuButton.getTooltip().setText("打开个人菜单");
        profileMenuButton.getStyleClass().remove("sidebar-profile-menu-button-open");
    }

    @FXML
    private void onProfileMenuAutoHide() {
        menuAutoHiding = true;
        fx.dispatch(() -> menuAutoHiding = false);
    }

    @FXML
    private void toggleProfileMenu() {
        if (menuAutoHiding) {
            menuAutoHiding = false;
        } else if (profileMenu.isShowing()) {
            profileMenu.hide();
        } else {
            profileMenu.show(profileMenuButton, Side.TOP, 0, -4);
        }
    }

    private void dispatch(Runnable action) {
        if (action != null) fx.dispatch(action);
    }

    @FXML private void onOpenSkillCenterRequested() { dispatch(onOpenSkillCenter); }
    @FXML private void onOpenMcpRequested() { dispatch(onOpenMcp); }
    @FXML private void onOpenSchedulerRequested() { dispatch(onOpenScheduler); }
    @FXML private void onOpenKnowledgeBaseRequested() { dispatch(onOpenKnowledgeBase); }
    @FXML private void onOpenMemoryCenterRequested() { dispatch(onOpenMemoryCenter); }
    @FXML private void onOpenWorkflowCenterRequested() { dispatch(onOpenWorkflowCenter); }
    @FXML private void onOpenTaskManagerRequested() { dispatch(onOpenTaskManager); }
    @FXML private void onOpenPluginCenterRequested() { dispatch(onOpenPluginCenter); }
    @FXML private void onOpenSettingsRequested() { dispatch(onOpenSettings); }

    public void setOnOpenSettings(Runnable callback) { onOpenSettings = callback; }
    public void setOnOpenSkillCenter(Runnable callback) { onOpenSkillCenter = callback; }
    public void setOnOpenMemoryCenter(Runnable callback) { onOpenMemoryCenter = callback; }
    public void setOnOpenScheduler(Runnable callback) { onOpenScheduler = callback; }
    public void setOnOpenKnowledgeBase(Runnable callback) { onOpenKnowledgeBase = callback; }
    public void setOnOpenTaskManager(Runnable callback) { onOpenTaskManager = callback; }
    public void setOnOpenWorkflowCenter(Runnable callback) { onOpenWorkflowCenter = callback; }
    public void setOnOpenMcp(Runnable callback) { onOpenMcp = callback; }
    public void setOnOpenPluginCenter(Runnable callback) { onOpenPluginCenter = callback; }

    public void updateScheduleBadge(int count) {
        scheduleBadgeCount = Math.max(0, count);
        updateNavBadge(scheduleNavBadge, count);
        updateProfileMenuBadge();
    }

    public void updateSkillBadge(int count) {
        skillBadgeCount = Math.max(0, count);
        updateNavBadge(skillNavBadge, count);
        updateProfileMenuBadge();
    }

    public void updateTaskBadge(int count) {
        taskBadgeCount = Math.max(0, count);
        updateNavBadge(taskNavBadge, count);
        updateProfileMenuBadge();
    }

    private static void updateNavBadge(Label badge, int count) {
        boolean visible = count > 0;
        badge.setText(visible ? Integer.toString(count) : "");
        badge.setVisible(visible);
        badge.setManaged(visible);
    }

    private void updateProfileMenuBadge() {
        int pending = scheduleBadgeCount + skillBadgeCount + taskBadgeCount;
        boolean visible = pending > 0;
        profileMenuBadgeLabel.setVisible(visible);
        profileMenuBadgeLabel.setManaged(visible);
        profileMenuBadgeLabel.setText(visible ? (pending > 99 ? "99+" : Integer.toString(pending)) : "");
        profileMenuBadgeLabel.setTooltip(visible ? new Tooltip(pending + " 项待处理") : null);
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        profileMenu.hide();
        for (HBox row : menuRows) row.prefWidthProperty().unbind();
        profileMenu.getItems().clear();
        onOpenSettings = null;
        onOpenSkillCenter = null;
        onOpenMemoryCenter = null;
        onOpenScheduler = null;
        onOpenKnowledgeBase = null;
        onOpenTaskManager = null;
        onOpenWorkflowCenter = null;
        onOpenMcp = null;
        onOpenPluginCenter = null;
    }

    boolean isClosed() {
        return closed;
    }
}
