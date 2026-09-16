package com.javaclaw.desktop.settings;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import javafx.beans.property.SimpleStringProperty;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import com.javaclaw.builtin.contracts.BuiltinExtensionIds;
import com.javaclaw.builtin.contracts.SiteContracts;
import com.javaclaw.desktop.component.PlatformComponentFactory;
import com.javaclaw.desktop.component.PlatformComponentFactory.ActionSize;
import com.javaclaw.desktop.component.PlatformComponentFactory.ActionStyle;
import com.javaclaw.desktop.view.ViewData;
import com.javaclaw.desktop.view.ViewRenderLayout;
import com.javaclaw.extension.spi.ViewSchema;
import com.javaclaw.protocol.CanonicalJson;

/** 网站管理的列表和折叠分区；字段、校验和命令仍由同一个 ViewRenderSession 生成。 */
final class SiteSettingsLayout implements ViewRenderLayout {
    private static final Set<String> KNOWN_NODES = Set.of(
            "sites",
            "site-create",
            "site-edit",
            "site-credential-bind",
            "site-private-network-bind",
            "site-actions",
            "site-authority-boundary");
    private final PlatformComponentFactory components = new PlatformComponentFactory();
    private final CanonicalJson json = new CanonicalJson();
    private final SiteSettingsActions actions;
    private final VBox root = new VBox(12);
    private final VBox catalog = new VBox(8);
    private final VBox createBody = new VBox(12);
    private final VBox advancedBody = new VBox(12);
    private final Label selectedName = new Label();
    private final Label empty = new Label("请选择上方网站，查看和修改它的配置。");
    private final VBox details = new VBox(8);
    private final TitledPane basic = section("基本信息", "site-basic");
    private final TitledPane accounts = section("账号与登录", "site-accounts");
    private final TitledPane advanced = section("高级配置", "site-advanced");
    private TitledPane expanded = basic;
    private final Button create;
    private final Button cancelCreate;
    private final Button discard;
    private final Button manageCredentials;
    private Optional<SiteContracts.Projection> selected = Optional.empty();
    private boolean creating;
    private boolean restoring;
    private boolean supported;

    SiteSettingsLayout(SiteSettingsActions actions, Node accountContent, Node loginContent) {
        this.actions = Objects.requireNonNull(actions, "actions");
        create = button("新建网站", ActionStyle.PRIMARY, this::beginCreate);
        cancelCreate = button("取消新建", ActionStyle.GHOST, this::cancelCreate);
        discard = button("放弃修改", ActionStyle.GHOST, this::discardChanges);
        manageCredentials = button("管理共享凭据", ActionStyle.SOFT, actions.manageCredentials());
        create.setId("site-new");
        cancelCreate.setId("site-cancel-new");
        discard.setId("site-discard");
        accounts.setContent(new VBox(12, accountContent, loginContent));
        advanced.setContent(advancedBody);
        details.setId("site-detail-sections");
        details.getChildren().addAll(basic, accounts, advanced);
        for (TitledPane pane : List.of(basic, accounts, advanced)) {
            pane.setExpanded(pane == basic);
            pane.expandedProperty().addListener((ignored, previous, next) -> changeSection(pane, previous, next));
        }
        selectedName.getStyleClass().add("grp-title");
        selectedName.setWrapText(true);
        empty.getStyleClass().add("sec-hint");
        empty.setWrapText(true);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        root.getChildren()
                .addAll(
                        new HBox(8, create, cancelCreate, spacer, discard),
                        catalog,
                        selectedName,
                        empty,
                        createBody,
                        details);
        root.setId("site-settings-layout");
        renderMode();
    }

    @Override
    public Node node() {
        return root;
    }

    @Override
    public void apply(ViewSchema schema, Map<String, Node> nodes, ViewData data) {
        supported = supports(schema, nodes);
        if (!supported) {
            catalog.getChildren().setAll(nodes.values());
            selected = Optional.empty();
            actions.selected().accept(selected);
            renderMode();
            return;
        }
        Optional<SiteContracts.Projection> next = selectedSite(data);
        if (!selected.map(SiteContracts.Projection::id).equals(next.map(SiteContracts.Projection::id))) {
            creating = false;
            expandBasic();
        }
        selected = next;
        catalog.getChildren().setAll(nodes.get("sites"));
        configureTable(nodes.get("sites"));
        basic.setContent(nodes.get("site-edit"));
        createBody.getChildren().setAll(nodes.get("site-create"));
        configureAdvanced(nodes);
        actions.selected().accept(creating ? Optional.empty() : selected);
        renderMode();
    }

    /** 成功创建后退出新建模式，随后由页面沿权威目录定位新网站。 */
    void created() {
        creating = false;
        expandBasic();
    }

    /** 页面状态变化只更新动作可用性，不重建表单或清空秘密。 */
    void refreshActions() {
        boolean pending = actions.pending().getAsBoolean();
        create.setDisable(!supported || pending);
        cancelCreate.setDisable(pending);
        discard.setDisable(pending || !actions.dirty().getAsBoolean());
        manageCredentials.setDisable(pending);
    }

    boolean accountsExpanded() {
        return !creating && supported && selected.isPresent() && expanded == accounts;
    }

    private void configureAdvanced(Map<String, Node> nodes) {
        Label sharing = new Label("HTTP 凭据从共享凭据库绑定；账号密码在“账号与登录”中分别管理。");
        sharing.setWrapText(true);
        sharing.getStyleClass().add("sec-hint");
        advancedBody.getChildren().setAll(sharing, manageCredentials);
        for (String id : List.of(
                "site-credential-bind", "site-private-network-bind", "site-actions", "site-authority-boundary")) {
            Optional.ofNullable(nodes.get(id)).ifPresent(advancedBody.getChildren()::add);
        }
        nodes.forEach((id, node) -> {
            if (!KNOWN_NODES.contains(id)) {
                advancedBody.getChildren().add(node);
            }
        });
    }

    private Optional<SiteContracts.Projection> selectedSite(ViewData data) {
        ViewData.Source source = data.source("documents");
        return source.selectedKey()
                .flatMap(id -> source.rows().stream()
                        .filter(row -> id.equals(row.get("id")))
                        .findFirst()
                        .map(row -> json.decode(json.encode(row), SiteContracts.Projection.class)));
    }

    private void beginCreate() {
        if (!supported || !actions.changeContext().getAsBoolean()) {
            return;
        }
        creating = true;
        actions.selected().accept(Optional.empty());
        renderMode();
    }

    private void cancelCreate() {
        if (actions.changeContext().getAsBoolean()) {
            creating = false;
            actions.selected().accept(selected);
            expandBasic();
            renderMode();
        }
    }

    private void discardChanges() {
        if (actions.changeContext().getAsBoolean()) {
            actions.discard().run();
        }
    }

    private void changeSection(TitledPane pane, boolean previous, boolean next) {
        if (restoring) {
            return;
        }
        if (!actions.changeContext().getAsBoolean()) {
            restoring = true;
            pane.setExpanded(previous);
            restoring = false;
            return;
        }
        // 先完成上下文确认，再统一修改全部分区，最后启动读取，避免内部折叠事件与新查询的 pending 相互回滚。
        expanded = next ? pane : null;
        applyExpansion();
        actions.accountsExpanded().accept(accountsExpanded());
        refreshActions();
    }

    private void expandBasic() {
        expanded = basic;
        applyExpansion();
        actions.accountsExpanded().accept(false);
    }

    private void applyExpansion() {
        restoring = true;
        for (TitledPane pane : List.of(basic, accounts, advanced)) {
            pane.setExpanded(pane == expanded);
        }
        restoring = false;
    }

    private void renderMode() {
        visible(cancelCreate, supported && creating);
        visible(createBody, supported && creating);
        visible(details, supported && !creating && selected.isPresent());
        visible(empty, supported && !creating && selected.isEmpty());
        visible(selectedName, supported && (creating || selected.isPresent()));
        selectedName.setText(
                creating ? "新建网站" : selected.map(SiteContracts.Projection::name).orElse(""));
        actions.accountsExpanded().accept(accountsExpanded());
        refreshActions();
    }

    private static boolean supports(ViewSchema schema, Map<String, Node> nodes) {
        return (BuiltinExtensionIds.SITE + ".management").equals(schema.viewId())
                && nodes.keySet().containsAll(Set.of("sites", "site-create", "site-edit"))
                && schema.nodes().stream()
                        .anyMatch(node -> node.id().equals("sites") && node instanceof ViewSchema.Table);
    }

    private static void configureTable(Node section) {
        if (!(section.lookup(".platform-data-table") instanceof TableView<?> table)) {
            return;
        }
        table.setId("site-list");
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        double height = Math.min(240, Math.max(120, table.getItems().size() * 34.0 + 42));
        table.setMinHeight(height);
        table.setPrefHeight(height);
        table.setMaxHeight(240);
        Label placeholder = new Label("暂无网站，点击“新建网站”开始配置。");
        placeholder.setWrapText(true);
        placeholder.getStyleClass().add("sec-hint");
        table.setPlaceholder(placeholder);
        if (table.getColumns().size() == 3) {
            @SuppressWarnings("unchecked")
            TableColumn<Map<String, Object>, String> status = (TableColumn<Map<String, Object>, String>)
                    table.getColumns().get(2);
            status.setCellValueFactory(row ->
                    new SimpleStringProperty(Boolean.TRUE.equals(row.getValue().get("enabled")) ? "启用" : "停用"));
        }
    }

    private static TitledPane section(String title, String id) {
        TitledPane pane = new TitledPane();
        pane.setText(title);
        pane.setId(id);
        pane.setAnimated(false);
        return pane;
    }

    private Button button(String text, ActionStyle style, Runnable action) {
        Button button = components.action(text, style, ActionSize.NORMAL);
        button.setOnAction(ignored -> action.run());
        return button;
    }

    private static void visible(Node node, boolean value) {
        node.setVisible(value);
        node.setManaged(value);
    }

    @Override
    public void close() {
        selected = Optional.empty();
        creating = false;
        supported = false;
        catalog.getChildren().clear();
        createBody.getChildren().clear();
        advancedBody.getChildren().clear();
        basic.setContent(null);
        actions.selected().accept(Optional.empty());
        renderMode();
    }
}
