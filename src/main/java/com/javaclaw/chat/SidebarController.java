package com.javaclaw.chat;

import com.javaclaw.app.UIHelper;
import com.javaclaw.config.Workspace;
import com.javaclaw.config.WorkspaceManager;
import com.javaclaw.platform.fx.FxDispatcher;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 左侧侧边栏视图（支持多会话管理）
 *
 * <p>包含功能入口按钮、会话列表和底部用户信息区域。
 * 会话列表支持新建、选中切换、右键删除。</p>
 *
 * @author JavaClaw
 */
public class SidebarController implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SidebarController.class);

    @FXML
    private VBox root;
    @FXML private SidebarSessionListController sessionListController;
    @FXML private SidebarProfileController profileController;
    @FXML
    private ComboBox<Workspace> workspaceCombo;
    private FxDispatcher fx;

    private boolean closed;

    /** 新建会话回调 */
    private Runnable onNewChat;

    /** 工作区切换回调（参数为目标工作区 ID） */
    private Consumer<String> onSwitchWorkspace;

    /** 正在刷新工作区下拉，暂时禁止触发切换回调 */
    private boolean refreshingWorkspaceCombo = false;


    /** Spring/FXML 构造路径；FXMLLoader 随后注入所有静态控件。 */
    @Autowired
    public SidebarController(FxDispatcher fx) {
        this.fx = java.util.Objects.requireNonNull(fx, "fx");
    }


    /** FXML 注入完成后的事件与动态菜单装配。 */
    @FXML
    private void initialize() {
        initializeWorkspaceSelector();
        log.info("侧边栏 FXML 构建完成");
    }

    private void initializeWorkspaceSelector() {
        WorkspaceManager workspaces = WorkspaceManager.getInstance();
        workspaceCombo.getItems().setAll(workspaces.getWorkspaces());
        workspaces.getWorkspaces().stream()
                .filter(workspace -> workspace.getId().equals(workspaces.getCurrentWorkspaceId()))
                .findFirst()
                .ifPresent(workspaceCombo.getSelectionModel()::select);
    }

    @FXML
    private void onWorkspaceSelected() {
        if (refreshingWorkspaceCombo) return;
        Workspace selected = workspaceCombo.getSelectionModel().getSelectedItem();
        WorkspaceManager workspaces = WorkspaceManager.getInstance();
        if (selected == null || selected.getId().equals(workspaces.getCurrentWorkspaceId())) return;
        String targetId = selected.getId();
        fx.dispatch(() -> {
            if (onSwitchWorkspace != null) onSwitchWorkspace.accept(targetId);
        });
    }

    @FXML
    private void onNewChatRequested() {
        if (onNewChat != null) onNewChat.run();
    }

    public void addSession(ChatSession session, boolean selected) {
        sessionListController.addSession(session, selected);
    }

    public void insertSessionAtTop(ChatSession session, boolean selected) {
        sessionListController.insertSessionAtTop(session, selected);
    }

    public void updateSessionTitle(String sessionId, String newTitle) {
        sessionListController.updateSessionTitle(sessionId, newTitle);
    }

    public void removeSession(String sessionId) {
        sessionListController.removeSession(sessionId);
    }

    public void selectSession(String sessionId) {
        sessionListController.selectSession(sessionId);
    }

    public void clearSessions() {
        sessionListController.clearSessions();
    }

    public String getSelectedSessionId() {
        return sessionListController.getSelectedSessionId();
    }

    public int getSessionCount() {
        return sessionListController.getSessionCount();
    }

    public VBox getRoot() {
        return root;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        onNewChat = null;
        onSwitchWorkspace = null;
    }

    boolean isClosed() {
        return closed;
    }

    // ==================== 回调设置 ====================

    public void setOnNewChat(Runnable callback) {
        this.onNewChat = callback;
    }

    public void setOnSwitchSession(Consumer<String> callback) {
        sessionListController.setOnSwitchSession(callback);
    }

    public void setOnDeleteSession(Consumer<String> callback) {
        sessionListController.setOnDeleteSession(callback);
    }

    public void setOnBatchDeleteSessions(Consumer<List<String>> callback) {
        sessionListController.setOnBatchDeleteSessions(callback);
    }

    public void setOnOpenSettings(Runnable callback) {
        profileController.setOnOpenSettings(callback);
    }

    public void setOnOpenSkillCenter(Runnable callback) {
        profileController.setOnOpenSkillCenter(callback);
    }

    public void setOnOpenMemoryCenter(Runnable callback) {
        profileController.setOnOpenMemoryCenter(callback);
    }

    public void setOnOpenScheduler(Runnable callback) {
        profileController.setOnOpenScheduler(callback);
    }

    public void setOnOpenKnowledgeBase(Runnable callback) {
        profileController.setOnOpenKnowledgeBase(callback);
    }

    public void setOnOpenTaskManager(Runnable callback) {
        profileController.setOnOpenTaskManager(callback);
    }

    public void setOnOpenWorkflowCenter(Runnable callback) {
        profileController.setOnOpenWorkflowCenter(callback);
    }

    public void setOnOpenMcp(Runnable callback) {
        profileController.setOnOpenMcp(callback);
    }

    public void setOnOpenPluginCenter(Runnable callback) {
        profileController.setOnOpenPluginCenter(callback);
    }

    public void setOnSwitchWorkspace(Consumer<String> callback) {
        this.onSwitchWorkspace = callback;
    }

    // ==================== 工作区操作 ====================

    /**
     * 新建工作区
     */
    @FXML
    private void onCreateWorkspace() {
        TextInputDialog dialog = UIHelper.createTextInputDialog("新工作区", "新建工作区", "工作区名称:", null);
        Optional<String> result = dialog.showAndWait();
        result.ifPresent(name -> {
            if (!name.isBlank()) {
                Workspace ws = WorkspaceManager.getInstance().createWorkspace(name.trim());
                workspaceCombo.getItems().add(ws);
                workspaceCombo.getSelectionModel().select(ws);
                if (onSwitchWorkspace != null) {
                    onSwitchWorkspace.accept(ws.getId());
                }
            }
        });
    }

    /**
     * 删除当前选中的工作区
     */
    @FXML
    private void onDeleteWorkspace() {
        WorkspaceManager wsMgr = WorkspaceManager.getInstance();
        Workspace selected = workspaceCombo.getSelectionModel().getSelectedItem();
        if (selected == null) return;

        if (wsMgr.getWorkspaces().size() <= 1) {
            UIHelper.createWarningAlert("不能删除最后一个工作区", null).showAndWait();
            return;
        }

        Alert confirm = UIHelper.createConfirmAlert("删除工作区",
                "确定要删除工作区「" + selected.getName() + "」吗？\n此操作将永久删除该工作区的所有数据。", null);
        confirm.showAndWait().ifPresent(btn -> {
            if (btn == ButtonType.OK) {
                boolean isCurrent = selected.getId().equals(wsMgr.getCurrentWorkspaceId());
                if (isCurrent) {
                    UIHelper.createWarningAlert("当前工作区仍被聊天、定时任务和插件服务使用，"
                            + "请先切换到其他工作区，待切换完成后再删除。", null).showAndWait();
                    return;
                }
                if (!wsMgr.deleteWorkspace(selected.getId())) return;
                workspaceCombo.getItems().remove(selected);
            }
        });
    }

    /**
     * 刷新工作区下拉列表（工作区切换完成后调用）
     */
    public void refreshWorkspaceCombo() {
        refreshingWorkspaceCombo = true;
        try {
            WorkspaceManager wsMgr = WorkspaceManager.getInstance();
            workspaceCombo.getItems().clear();
            workspaceCombo.getItems().addAll(wsMgr.getWorkspaces());
            for (Workspace ws : wsMgr.getWorkspaces()) {
                if (ws.getId().equals(wsMgr.getCurrentWorkspaceId())) {
                    workspaceCombo.getSelectionModel().select(ws);
                    break;
                }
            }
        } finally {
            refreshingWorkspaceCombo = false;
        }
    }

    public void updateScheduleBadge(int count) {
        profileController.updateScheduleBadge(count);
    }

    public void updateSkillBadge(int count) {
        profileController.updateSkillBadge(count);
    }

    public void updateTaskBadge(int count) {
        profileController.updateTaskBadge(count);
    }

    public void updateSessionCount(int count) {
        sessionListController.updateSessionCount(count);
    }

}
