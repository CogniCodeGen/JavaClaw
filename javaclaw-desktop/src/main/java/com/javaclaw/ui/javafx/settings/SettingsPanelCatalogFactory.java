package com.javaclaw.ui.javafx.settings;

import com.javaclaw.ui.javafx.agent.AgentSettingsPanelFactory;
import com.javaclaw.ui.javafx.mcp.McpCenterViewFactory;
import com.javaclaw.ui.javafx.site.SiteCredentialPanelFactory;
import javafx.scene.Node;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.Region;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/** 只注册设置分区工厂；FXML 和 Controller 在对应导航首次访问时创建。 */
public final class SettingsPanelCatalogFactory {

    interface Callbacks {
        void applied(SettingsCategory category, String message, boolean runtimeRefreshRequired);
        void testFinished(SettingsCategory category, String message, boolean succeeded);
        void runtimeConfigurationChanged();
        String modelSavedTip();
    }

    private final AgentSettingsPanelFactory agents;
    private final SiteCredentialPanelFactory sites;
    private final McpCenterViewFactory mcp;
    private final ModelSettingsSectionFactory models;
    private final CommunicationSettingsSectionFactory communication;
    private final BehaviorSettingsSectionFactory behavior;
    private final MaintenanceSettingsSectionFactory maintenance;
    private final AppearanceSettingsSectionFactory appearance;

    public SettingsPanelCatalogFactory(
            AgentSettingsPanelFactory agents,
            SiteCredentialPanelFactory sites,
            McpCenterViewFactory mcp,
            ModelSettingsSectionFactory models,
            CommunicationSettingsSectionFactory communication,
            BehaviorSettingsSectionFactory behavior,
            MaintenanceSettingsSectionFactory maintenance,
            AppearanceSettingsSectionFactory appearance) {
        this.agents = Objects.requireNonNull(agents, "agents");
        this.sites = Objects.requireNonNull(sites, "sites");
        this.mcp = Objects.requireNonNull(mcp, "mcp");
        this.models = Objects.requireNonNull(models, "models");
        this.communication = Objects.requireNonNull(communication, "communication");
        this.behavior = Objects.requireNonNull(behavior, "behavior");
        this.maintenance = Objects.requireNonNull(maintenance, "maintenance");
        this.appearance = Objects.requireNonNull(appearance, "appearance");
    }

    SettingsPanelCatalog create(Callbacks callbacks) {
        Objects.requireNonNull(callbacks, "callbacks");
        List<SettingsPanelCatalog.Definition> definitions = new ArrayList<>();
        addModelPanels(definitions, callbacks);
        addBehaviorPanels(definitions, callbacks);
        addIntegrationPanels(definitions, callbacks);
        addAppearancePanels(definitions);
        addMaintenancePanel(definitions);
        addCommunicationPanels(definitions, callbacks);
        return new SettingsPanelCatalog(definitions);
    }

    private void addModelPanels(
            List<SettingsPanelCatalog.Definition> values, Callbacks callbacks) {
        values.add(definition(SettingsCategory.MODEL, () -> {
            var view = models.createModel(result -> callbacks.applied(
                    SettingsCategory.MODEL, result.message(), result.runtimeRefreshRequired()),
                    callbacks::runtimeConfigurationChanged);
            return panel(SettingsCategory.MODEL, view.root(),
                    SettingsPanelActions.asyncSaveAndTest(
                            (success, failure) -> view.controller().save(ignored -> success.run(), failure),
                            callbacks::modelSavedTip,
                            () -> view.controller().probe(
                                    result -> callbacks.testFinished(SettingsCategory.MODEL,
                                            result.message(), result.succeeded()),
                                    failure -> callbacks.testFinished(SettingsCategory.MODEL,
                                            "连接失败: " + SettingsFieldSupport.failureMessage(failure), false)),
                            "测试连接"), view.controller()::reload,
                    view.controller()::deactivate, view);
        }));
        values.add(definition(SettingsCategory.TIERED_MODEL, () -> {
            var view = models.createTiers(result -> callbacks.applied(
                    SettingsCategory.TIERED_MODEL, result.message(), result.runtimeRefreshRequired()),
                    callbacks::runtimeConfigurationChanged);
            return panel(SettingsCategory.TIERED_MODEL, view.root(),
                    SettingsPanelActions.asyncSave(
                            (success, failure) -> view.controller().save(ignored -> success.run(), failure),
                            callbacks::modelSavedTip), view.controller()::reload,
                    view.controller()::deactivate, view);
        }));
        values.add(definition(SettingsCategory.EMBEDDING, () -> {
            var view = models.createEmbedding(result -> callbacks.applied(
                    SettingsCategory.EMBEDDING, result.message(), result.runtimeRefreshRequired()),
                    callbacks::runtimeConfigurationChanged);
            return panel(SettingsCategory.EMBEDDING, view.root(),
                    SettingsPanelActions.asyncSaveAndTest(
                            (success, failure) -> view.controller().save(ignored -> success.run(), failure),
                            callbacks::modelSavedTip,
                            () -> view.controller().probe(
                                    result -> callbacks.testFinished(SettingsCategory.EMBEDDING,
                                            result.message(), result.succeeded()),
                                    failure -> callbacks.testFinished(SettingsCategory.EMBEDDING,
                                            "嵌入测试失败: "
                                                    + SettingsFieldSupport.failureMessage(failure), false)),
                            "测试嵌入"), view.controller()::reload,
                    view.controller()::deactivate, view);
        }));
        values.add(definition(SettingsCategory.AGENT, () -> {
            var view = agents.create(callbacks::runtimeConfigurationChanged);
            return panel(SettingsCategory.AGENT, view.root(), SettingsPanelActions.none(),
                    view::activate, view::deactivate, view);
        }));
    }

    private void addBehaviorPanels(
            List<SettingsPanelCatalog.Definition> values, Callbacks callbacks) {
        values.add(definition(SettingsCategory.GEPA, () -> {
            var view = behavior.createGepa(result -> callbacks.applied(
                    SettingsCategory.GEPA, result.message(), result.runtimeRefreshRequired()));
            return panel(SettingsCategory.GEPA, view.root(), SettingsPanelActions.asyncSave(
                    (success, failure) -> view.controller().save(ignored -> success.run(), failure),
                    callbacks::modelSavedTip), view.controller()::reload,
                    view.controller()::deactivate, view);
        }));
        values.add(definition(SettingsCategory.SKILL_EVOLUTION, () -> {
            var view = behavior.createSkillEvolution(result -> callbacks.applied(
                    SettingsCategory.SKILL_EVOLUTION, result.message(), result.runtimeRefreshRequired()));
            return panel(SettingsCategory.SKILL_EVOLUTION, view.root(), SettingsPanelActions.asyncSave(
                    (success, failure) -> view.controller().save(ignored -> success.run(), failure),
                    () -> "✓ 已保存，下一轮对话生效"), view.controller()::reload,
                    view.controller()::deactivate, view);
        }));
        values.add(definition(SettingsCategory.GENERAL, () -> {
            var view = behavior.createGeneral(result -> callbacks.applied(
                    SettingsCategory.GENERAL, result.message(), result.runtimeRefreshRequired()));
            return panel(SettingsCategory.GENERAL, view.root(), SettingsPanelActions.asyncSave(
                    (success, failure) -> view.controller().save(ignored -> success.run(), failure),
                    () -> "✓ 已保存"), view.controller()::reload,
                    view.controller()::deactivate, view);
        }));
    }

    private void addIntegrationPanels(
            List<SettingsPanelCatalog.Definition> values, Callbacks callbacks) {
        values.add(definition(SettingsCategory.MCP, () -> {
            var view = mcp.createPanel(callbacks::runtimeConfigurationChanged);
            return panel(SettingsCategory.MCP, view.root(), SettingsPanelActions.none(),
                    view::activate, view::deactivate, view);
        }));
        values.add(definition(SettingsCategory.SITE, () -> {
            var view = sites.create();
            return panel(SettingsCategory.SITE, view.root(), SettingsPanelActions.none(),
                    view::activate, view::deactivate, view);
        }));
    }

    private void addAppearancePanels(List<SettingsPanelCatalog.Definition> values) {
        values.add(definition(SettingsCategory.APPEARANCE, () -> {
            var view = appearance.createAppearance();
            return panel(SettingsCategory.APPEARANCE, view.root(), SettingsPanelActions.none(),
                    view.controller()::reload, view);
        }));
        values.add(definition(SettingsCategory.FONT, () -> {
            var view = appearance.createFonts();
            return panel(SettingsCategory.FONT, view.root(), SettingsPanelActions.none(),
                    view.controller()::reload, view);
        }));
    }

    private void addMaintenancePanel(List<SettingsPanelCatalog.Definition> values) {
        values.add(definition(SettingsCategory.TEST_DATA, () -> {
            var view = maintenance.createTestDataMaintenance();
            return panel(SettingsCategory.TEST_DATA, view.root(), SettingsPanelActions.none(), null, view);
        }));
    }

    private void addCommunicationPanels(
            List<SettingsPanelCatalog.Definition> values, Callbacks callbacks) {
        values.add(definition(SettingsCategory.EMAIL, () -> {
            var view = communication.createEmail(result -> callbacks.applied(
                    SettingsCategory.EMAIL, result.message(), false));
            return panel(SettingsCategory.EMAIL, view.root(), SettingsPanelActions.asyncSaveAndTest(
                    (success, failure) -> view.controller().save(ignored -> success.run(), failure),
                    () -> "✓ 已保存",
                    () -> view.controller().probe(
                            result -> callbacks.testFinished(SettingsCategory.EMAIL,
                                    result.message(), result.succeeded()),
                            failure -> callbacks.testFinished(SettingsCategory.EMAIL,
                                    "邮件测试失败: " + SettingsFieldSupport.failureMessage(failure), false)),
                    "测试收发"), view.controller()::reload,
                    view.controller()::deactivate, view);
        }));
        values.add(definition(SettingsCategory.NOTIFICATION, () -> {
            var view = communication.createNotifications(result -> callbacks.applied(
                    SettingsCategory.NOTIFICATION, result.message(), false));
            return panel(SettingsCategory.NOTIFICATION, view.root(), SettingsPanelActions.asyncSave(
                    (success, failure) -> view.controller().save(ignored -> success.run(), failure),
                    () -> "✓ 已保存"), view.controller()::reload,
                    view.controller()::deactivate, view);
        }));
    }

    private static SettingsPanelCatalog.Definition definition(
            SettingsCategory category, Supplier<SettingsPanelCatalog.Panel> factory) {
        return new SettingsPanelCatalog.Definition(category, () -> {
            SettingsPanelCatalog.Panel panel = factory.get();
            constrainContent(panel.category(), panel.root());
            return panel;
        });
    }

    private static SettingsPanelCatalog.Panel panel(
            SettingsCategory category,
            Node root,
            SettingsPanelActions actions,
            Runnable reload,
            AutoCloseable resource) {
        return new SettingsPanelCatalog.Panel(category, root, actions, reload, resource);
    }

    private static SettingsPanelCatalog.Panel panel(
            SettingsCategory category,
            Node root,
            SettingsPanelActions actions,
            Runnable reload,
            Runnable deactivate,
            AutoCloseable resource) {
        return new SettingsPanelCatalog.Panel(
                category, root, actions, reload, deactivate, resource);
    }

    private static void constrainContent(SettingsCategory category, Node panel) {
        if (category == SettingsCategory.AGENT) return;
        if (panel instanceof ScrollPane scrollPane && scrollPane.getContent() instanceof Region content) {
            content.setMaxWidth(760);
        }
    }
}
