package com.javaclaw.ui.javafx.site;

import com.javaclaw.site.*;

import com.javaclaw.app.UIHelper;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 站点管理设置面板
 *
 * <p>对 {@link SiteCredentialManager} 提供 UI：列表 / 添加 / 编辑 / 删除 / 重置会话。</p>
 *
 * @author JavaClaw
 */
public class SiteCredentialView {

    private static final Logger log = LoggerFactory.getLogger(SiteCredentialView.class);

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final SiteCredentialManager manager;
    private VBox listBox;
    private Label statusLabel;

    public SiteCredentialView() {
        this.manager = SiteCredentialManager.getInstance();
    }

    public Node buildPanel() {
        Label sectionTitle = new Label("站点管理");
        sectionTitle.getStyleClass().add("settings-section-title");

        Label description = new Label(
                "登记常用网站的用户名和密码。浏览器智能体导航到这些站点时，"
                + "会自动恢复已保存的登录会话；首次访问可调用 site_login_now 自动登录并保存会话，免去后续重复登录。");
        description.getStyleClass().add("settings-hint");
        description.setWrapText(true);

        Button addBtn = new Button("+ 添加站点");
        addBtn.getStyleClass().add("settings-test-button");
        addBtn.setOnAction(e -> showEditDialog(null));

        listBox = new VBox(8);
        listBox.setPadding(new Insets(4));

        statusLabel = new Label();
        statusLabel.getStyleClass().add("settings-status");

        Label pathHint = new Label("配置文件: " + manager.getConfigFilePath());
        pathHint.getStyleClass().add("settings-hint");

        ScrollPane scroll = new ScrollPane();
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().add("settings-scroll-pane");

        VBox panel = new VBox(12,
                sectionTitle, description, new Separator(),
                addBtn, listBox,
                new Separator(), pathHint, statusLabel);
        panel.setPadding(new Insets(4));
        scroll.setContent(panel);

        refreshList();
        return scroll;
    }

    private void refreshList() {
        listBox.getChildren().clear();
        List<SiteCredential> all = manager.all();
        if (all.isEmpty()) {
            Label empty = new Label("暂无登记站点，点击上方「+ 添加站点」开始");
            empty.getStyleClass().add("settings-hint");
            listBox.getChildren().add(empty);
            return;
        }
        for (SiteCredential c : all) {
            listBox.getChildren().add(buildCard(c));
        }
    }

    private Node buildCard(SiteCredential c) {
        VBox card = new VBox(4);
        card.getStyleClass().add("mcp-server-card");
        card.setPadding(new Insets(10, 12, 10, 12));

        Label nameLabel = new Label(c.getName());
        nameLabel.getStyleClass().add("mcp-server-name");

        Label sessionBadge = new Label(c.isHasSession() ? "● 已保存会话" : "○ 未登录");
        sessionBadge.getStyleClass().add("mcp-state-badge");
        sessionBadge.getStyleClass().add(c.isHasSession() ? "mcp-state-running" : "mcp-state-stopped");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button editBtn = new Button("编辑");
        editBtn.getStyleClass().add("settings-test-button");
        editBtn.setOnAction(e -> showEditDialog(c));

        Button resetBtn = new Button("重置会话");
        resetBtn.getStyleClass().add("settings-test-button");
        resetBtn.setDisable(!c.isHasSession());
        resetBtn.setOnAction(e -> {
            Alert alert = UIHelper.createConfirmAlert("重置会话",
                    "确认清除「" + c.getName() + "」的已保存会话？\n下次访问该站点需要重新登录。", null);
            alert.showAndWait().ifPresent(bt -> {
                if (bt == ButtonType.OK) {
                    manager.clearSession(c.getId());
                    refreshList();
                    setStatus("已重置 " + c.getName() + " 的会话");
                }
            });
        });

        Button deleteBtn = new Button("删除");
        deleteBtn.getStyleClass().add("danger-btn");
        deleteBtn.setOnAction(e -> {
            Alert alert = UIHelper.createConfirmAlert("删除站点",
                    "确认删除站点「" + c.getName() + "」？\n会同时删除其已保存会话。", null);
            alert.showAndWait().ifPresent(bt -> {
                if (bt == ButtonType.OK) {
                    manager.remove(c.getId());
                    refreshList();
                    setStatus("已删除 " + c.getName());
                }
            });
        });

        HBox header = new HBox(8, sessionBadge, nameLabel, spacer, editBtn, resetBtn, deleteBtn);
        header.setAlignment(Pos.CENTER_LEFT);

        Label hostLabel = new Label("主机匹配: " + (c.getHostPattern() == null ? "-" : c.getHostPattern()));
        hostLabel.getStyleClass().add("settings-hint");

        Label userLabel = new Label("用户名: " + (c.getUsername() == null ? "-" : c.getUsername())
                + "    密码: ********");
        userLabel.getStyleClass().add("settings-hint");

        StringBuilder ts = new StringBuilder();
        if (c.getCreatedAt() > 0) {
            ts.append("创建于 ").append(TIME_FMT.format(Instant.ofEpochMilli(c.getCreatedAt())));
        }
        if (c.getLastUsedAt() > 0) {
            if (!ts.isEmpty()) ts.append("  ·  ");
            ts.append("最近使用 ").append(TIME_FMT.format(Instant.ofEpochMilli(c.getLastUsedAt())));
        }
        Label tsLabel = new Label(ts.toString());
        tsLabel.getStyleClass().add("settings-hint");

        card.getChildren().addAll(header, hostLabel, userLabel);
        if (!ts.isEmpty()) card.getChildren().add(tsLabel);

        if (c.getNotes() != null && !c.getNotes().isBlank()) {
            Label notesLabel = new Label("备注: " + c.getNotes());
            notesLabel.getStyleClass().add("settings-hint");
            notesLabel.setWrapText(true);
            card.getChildren().add(notesLabel);
        }

        return card;
    }

    private void showEditDialog(SiteCredential existing) {
        Dialog<SiteCredential> dialog = new Dialog<>();
        dialog.setTitle(existing == null ? "添加站点" : "编辑站点");
        dialog.setHeaderText(null);

        String cssPath = getClass().getResource("/css/chat.css") != null
                ? getClass().getResource("/css/chat.css").toExternalForm() : null;
        if (cssPath != null) {
            dialog.getDialogPane().getStylesheets().add(cssPath);
        }

        Label titleLabel = new Label(existing == null ? "添加站点" : "编辑站点");
        titleLabel.getStyleClass().add("settings-section-title");

        TextField nameField = new TextField();
        nameField.setPromptText("展示名（如 GitHub、内部 OA）");
        nameField.getStyleClass().add("settings-field");
        nameField.setPrefWidth(420);

        TextField hostField = new TextField();
        hostField.setPromptText("主机匹配，如 github.com 或 *.example.com");
        hostField.getStyleClass().add("settings-field");

        TextField loginUrlField = new TextField();
        loginUrlField.setPromptText("登录页 URL（可选，便于一键打开）");
        loginUrlField.getStyleClass().add("settings-field");

        TextField usernameField = new TextField();
        usernameField.setPromptText("用户名 / 邮箱");
        usernameField.getStyleClass().add("settings-field");

        PasswordField passwordField = new PasswordField();
        passwordField.setPromptText("密码（不进入 LLM 上下文）");
        passwordField.getStyleClass().add("settings-field");

        TextField passwordPlain = new TextField();
        passwordPlain.setPromptText("密码（不进入 LLM 上下文）");
        passwordPlain.getStyleClass().add("settings-field");
        passwordPlain.setVisible(false);
        passwordPlain.setManaged(false);
        passwordPlain.textProperty().bindBidirectional(passwordField.textProperty());

        ToggleButton revealToggle = new ToggleButton("👁");
        revealToggle.getStyleClass().add("settings-test-button");
        Tooltip.install(revealToggle, new Tooltip("显示/隐藏密码"));
        revealToggle.setOnAction(e -> {
            boolean show = revealToggle.isSelected();
            passwordPlain.setVisible(show);
            passwordPlain.setManaged(show);
            passwordField.setVisible(!show);
            passwordField.setManaged(!show);
            revealToggle.setText(show ? "🙈" : "👁");
        });

        StackPane passwordSlot = new StackPane(passwordField, passwordPlain);
        HBox passwordRow = new HBox(8, passwordSlot, revealToggle);
        passwordRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(passwordSlot, Priority.ALWAYS);

        TextArea notesArea = new TextArea();
        notesArea.setPromptText("备注（可选）");
        notesArea.setPrefRowCount(2);
        notesArea.getStyleClass().add("mcp-env-area");

        if (existing != null) {
            nameField.setText(existing.getName());
            hostField.setText(existing.getHostPattern());
            loginUrlField.setText(existing.getLoginUrl());
            usernameField.setText(existing.getUsername());
            passwordField.setText(existing.getPassword());
            notesArea.setText(existing.getNotes());
        }

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        int row = 0;
        grid.add(group("展示名"), 0, row++);
        grid.add(nameField, 0, row++);
        grid.add(group("主机匹配"), 0, row++);
        grid.add(hostField, 0, row++);
        grid.add(hint("精确匹配域名（github.com）或前缀通配（*.example.com 匹配任意子域）"), 0, row++);
        grid.add(group("登录页 URL（可选）"), 0, row++);
        grid.add(loginUrlField, 0, row++);
        grid.add(group("用户名"), 0, row++);
        grid.add(usernameField, 0, row++);
        grid.add(group("密码"), 0, row++);
        grid.add(passwordRow, 0, row++);
        grid.add(group("备注"), 0, row++);
        grid.add(notesArea, 0, row++);

        VBox content = new VBox(12, titleLabel, grid);
        content.setPadding(new Insets(4));

        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        dialog.getDialogPane().setMinWidth(520);

        dialog.setResultConverter(button -> {
            if (button != ButtonType.OK) return null;
            String name = nameField.getText() == null ? "" : nameField.getText().trim();
            String host = hostField.getText() == null ? "" : hostField.getText().trim();
            String user = usernameField.getText() == null ? "" : usernameField.getText().trim();
            if (name.isEmpty() || host.isEmpty() || user.isEmpty()) return null;

            SiteCredential cred = (existing != null) ? existing : new SiteCredential();
            cred.setName(name);
            cred.setHostPattern(host.toLowerCase());
            cred.setLoginUrl(emptyToNull(loginUrlField.getText()));
            cred.setUsername(user);
            cred.setPassword(passwordField.getText() == null ? "" : passwordField.getText());
            cred.setNotes(emptyToNull(notesArea.getText()));
            return cred;
        });

        dialog.showAndWait().ifPresent(cred -> {
            manager.put(cred);
            refreshList();
            setStatus("已" + (existing == null ? "添加" : "更新") + "站点: " + cred.getName());
        });
    }

    // ==================== 工具 ====================

    private Label group(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("settings-group-title");
        return l;
    }

    private Label hint(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("settings-hint");
        l.setWrapText(true);
        return l;
    }

    private static String emptyToNull(String s) {
        if (s == null) return null;
        s = s.trim();
        return s.isEmpty() ? null : s;
    }

    private void setStatus(String message) {
        if (statusLabel != null) {
            statusLabel.setText(message);
            statusLabel.getStyleClass().removeAll("status-success", "status-error");
            statusLabel.getStyleClass().add("status-success");
        }
    }
}
