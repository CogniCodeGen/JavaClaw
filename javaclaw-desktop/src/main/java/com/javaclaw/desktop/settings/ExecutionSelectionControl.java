package com.javaclaw.desktop.settings;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;

import com.javaclaw.api.AgentRole;
import com.javaclaw.api.AgentRoleRef;
import com.javaclaw.api.ExecutionOverrides;
import com.javaclaw.api.ModelPreference;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.PermissionProfileRef;
import com.javaclaw.api.ProviderEndpoint;
import com.javaclaw.api.ProviderLifecycle;
import com.javaclaw.api.ProviderModelPurpose;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.api.ReasoningPreference;
import com.javaclaw.api.RoleLifecycle;
import com.javaclaw.desktop.component.PlatformComponentFactory;

/**
 * Workspace 向导、执行设置和输入区复用的独立选择控件。
 *
 * <p>此控件只保存显式选择和展示来源，不计算有效权限或预算；服务端是唯一配置解析者。
 */
public final class ExecutionSelectionControl extends VBox {
    private final PlatformComponentFactory components = new PlatformComponentFactory();
    private final ComboBox<AgentRole> role = new ComboBox<>();
    private final ComboBox<ProviderRef> model = new ComboBox<>();
    private final ComboBox<PermissionProfile> permission = new ComboBox<>();
    private final ComboBox<ReasoningPreference> reasoning = new ComboBox<>();
    private final Label source = new Label("未选择的字段继承上级执行配置");
    private ExecutionOverrides value = ExecutionOverrides.empty();
    private Consumer<ExecutionOverrides> listener = ignored -> {};
    private boolean rendering;
    private Optional<AgentRole> inheritedRole = Optional.empty();

    /** 创建沿用输入区 CSS 令牌、可自动换行的选择控件。 */
    public ExecutionSelectionControl() {
        super(4);
        configure();
        FlowPane choices = new FlowPane(8, 6, role, model, reasoning, permission);
        Button inherit = new Button("恢复继承");
        inherit.getStyleClass().add("sidebar-manage-btn");
        inherit.setOnAction(event -> {
            setValue(ExecutionOverrides.empty());
            listener.accept(value);
        });
        choices.getChildren().add(inherit);
        source.setWrapText(true);
        source.getStyleClass().add("sec-hint");
        getChildren().addAll(choices, source);
        role.valueProperty().addListener((ignored, previous, selected) -> changed(Field.ROLE));
        model.valueProperty().addListener((ignored, previous, selected) -> changed(Field.MODEL));
        permission.valueProperty().addListener((ignored, previous, selected) -> changed(Field.PERMISSION));
        reasoning.valueProperty().addListener((ignored, previous, selected) -> changed(Field.REASONING));
    }

    /** 聊天区的更多设置仅呈现 Agent、权限与来源，模型和思考由主工具栏控制。 */
    void showAdvancedOnly() {
        FlowPane choices = (FlowPane) getChildren().getFirst();
        choices.getChildren().setAll(role, permission);
    }

    /** @param callback 显式选择变更监听器 */
    public void onChanged(Consumer<ExecutionOverrides> callback) {
        listener = Objects.requireNonNull(callback, "callback");
    }

    /**
     * 更新独立资源目录，目录本身不授予权限。
     *
     * @param roles 角色目录
     * @param providers Provider 目录
     * @param permissions 权限目录
     */
    public void setCatalog(
            List<AgentRole> roles, List<ProviderEndpoint> providers, List<PermissionProfile> permissions) {
        rendering = true;
        try {
            role.getItems()
                    .setAll(roles.stream()
                            .filter(item -> item.lifecycle() == RoleLifecycle.ACTIVE)
                            .toList());
            model.getItems()
                    .setAll(providers.stream()
                            .filter(item -> item.lifecycle() == ProviderLifecycle.ACTIVE)
                            .flatMap(endpoint -> endpoint.spec().models().stream()
                                    .filter(item -> item.supports(ProviderModelPurpose.CHAT))
                                    .map(item -> new ProviderRef(endpoint.id(), endpoint.revision(), item.modelId())))
                            .toList());
            permission.getItems().setAll(permissions);
        } finally {
            rendering = false;
        }
        setValue(value);
    }

    /** @param selected 精确显式覆盖；未展示的审批、预算和能力字段原样保留 */
    public void setValue(ExecutionOverrides selected) {
        value = Objects.requireNonNull(selected, "selected");
        rendering = true;
        try {
            role.setValue(selected.role()
                    .flatMap(reference -> role.getItems().stream()
                            .filter(item -> item.id().equals(reference.id()) && item.revision() == reference.revision())
                            .findFirst())
                    .orElse(null));
            model.setValue(selected.provider().orElse(null));
            permission.setValue(selected.permissionProfile()
                    .flatMap(reference -> permission.getItems().stream()
                            .filter(item -> item.id().equals(reference.id()) && item.version() == reference.version())
                            .findFirst())
                    .orElse(null));
            reasoning.setValue(selected.reasoning().orElse(null));
            renderLock();
        } finally {
            rendering = false;
        }
    }

    /** @return 当前显式覆盖，不包含客户端推测的有效权限 */
    public ExecutionOverrides value() {
        return value;
    }

    /** @param text 权威配置来源和 revision 的只读说明 */
    public void showSource(String text) {
        source.setText("查看继承来源与版本");
        source.setTooltip(new Tooltip(Objects.requireNonNullElse(text, "")));
        renderLock();
    }

    /** @param inherited 精确上级角色，只用于展示固定模型原因，不写入显式覆盖 */
    public void showInheritedRole(Optional<AgentRole> inherited) {
        inheritedRole = Objects.requireNonNull(inherited, "inherited");
        renderLock();
    }

    /** 为新 Workspace 选中通用 default，保留模型与权限的独立选择。 */
    public void selectDefaultRole() {
        role.getItems().stream()
                .filter(item -> item.id().equals("default"))
                .findFirst()
                .ifPresent(role::setValue);
    }

    private void configure() {
        role.setPromptText("Agent · 继承");
        model.setPromptText("模型 · 继承");
        permission.setPromptText("权限 · 继承");
        reasoning.setPromptText("推理 · 继承");
        role.setId("executionRole");
        model.setId("executionModel");
        permission.setId("executionPermission");
        reasoning.setId("executionReasoning");
        role.setCellFactory(ignored -> components.textCell(item -> item.spec().name()));
        role.setButtonCell(components.textCell(item -> item.spec().name()));
        model.setCellFactory(ignored -> components.textCell(ExecutionSelectionControl::modelLabel));
        model.setButtonCell(components.textCell(ExecutionSelectionControl::modelLabel));
        permission.setCellFactory(ignored -> components.textCell(item -> SettingsLabels.permissionProfile(item.id())));
        permission.setButtonCell(components.textCell(item -> SettingsLabels.permissionProfile(item.id())));
        reasoning.getItems().setAll(ReasoningPreference.values());
        for (ComboBox<?> box : List.of(role, model, permission, reasoning)) {
            box.setPrefWidth(box == model ? 230 : 150);
            box.setMaxWidth(260);
            box.getStyleClass().add("composer-select");
        }
        role.setAccessibleText("独立选择 Agent");
        model.setAccessibleText("独立选择模型；Agent 固定模型时锁定");
        permission.setAccessibleText("独立选择权限配置");
        reasoning.setAccessibleText("独立选择推理偏好");
    }

    private void changed(Field field) {
        if (rendering) {
            return;
        }
        value = new ExecutionOverrides(
                field == Field.ROLE
                        ? Optional.ofNullable(role.getValue()).map(item -> new AgentRoleRef(item.id(), item.revision()))
                        : value.role(),
                field == Field.MODEL ? Optional.ofNullable(model.getValue()) : value.provider(),
                field == Field.PERMISSION
                        ? Optional.ofNullable(permission.getValue())
                                .map(item -> new PermissionProfileRef(item.id(), item.version()))
                        : value.permissionProfile(),
                value.approvalPolicy(),
                value.budget(),
                value.visibleCapabilities(),
                field == Field.REASONING ? Optional.ofNullable(reasoning.getValue()) : value.reasoning());
        renderLock();
        listener.accept(value);
    }

    private void renderLock() {
        renderUnavailableReferences();
        Optional<AgentRole> selected = value.role().isPresent() ? Optional.ofNullable(role.getValue()) : inheritedRole;
        Optional<ModelPreference> locked = selected.flatMap(item -> item.spec().model());
        model.setDisable(locked.isPresent());
        model.setTooltip(new Tooltip(locked.map(preference -> "由 Agent 锁定：" + modelLabel(preference.provider()))
                .orElse("未选择时由服务端继承 Thread、Workspace 或安装默认模型")));
        reasoning.setDisable(selected.flatMap(item -> item.spec().reasoning()).isPresent());
        reasoning.setTooltip(new Tooltip(selected.flatMap(item -> item.spec().reasoning())
                .map(preference -> "由 Agent 锁定：" + preference)
                .orElse("未选择时继承执行配置")));
        if (locked.isPresent()) {
            source.setText("由 Agent 锁定模型：" + modelLabel(locked.orElseThrow().provider()) + "；权限继续由所选方案与上级限制取交集。");
        } else {
            source.setText("查看继承来源与版本");
        }
    }

    private void renderUnavailableReferences() {
        role.setPromptText(value.role()
                .filter(ignored -> role.getValue() == null)
                .map(reference -> "Agent · " + reference.id() + "@" + reference.revision() + " 不可用")
                .orElse("Agent · 继承"));
        permission.setPromptText(value.permissionProfile()
                .filter(ignored -> permission.getValue() == null)
                .map(reference -> "权限 · " + reference.id() + "@" + reference.version() + " 不可用")
                .orElse("权限 · 继承"));
        role.setTooltip(new Tooltip(value.role()
                .map(reference ->
                        "已保留精确 Agent 引用：" + reference.id() + "@" + reference.revision() + "；目录中不可用时请重新选择或恢复继承")
                .orElse("未选择时继承上级 Agent")));
        permission.setTooltip(new Tooltip(value.permissionProfile()
                .map(reference -> "已保留精确权限引用：" + reference.id() + "@" + reference.version() + "；目录中不可用时请重新选择或恢复继承")
                .orElse("未选择时继承上级权限配置")));
    }

    private enum Field {
        ROLE,
        MODEL,
        PERMISSION,
        REASONING
    }

    private static String modelLabel(ProviderRef reference) {
        return reference.model() + " · " + reference.endpointId() + "@" + reference.endpointRevision();
    }
}
