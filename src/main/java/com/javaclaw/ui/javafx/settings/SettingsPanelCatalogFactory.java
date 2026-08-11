package com.javaclaw.ui.javafx.settings;

import com.javaclaw.ui.javafx.agent.AgentSettingsPanel;
import com.javaclaw.ui.javafx.agent.AgentSettingsPanelFactory;
import com.javaclaw.ui.javafx.mcp.McpCenterView;
import com.javaclaw.ui.javafx.mcp.McpCenterViewFactory;
import com.javaclaw.ui.javafx.site.SiteCredentialPanel;
import com.javaclaw.ui.javafx.site.SiteCredentialPanelFactory;
import javafx.scene.Node;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.Region;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** 创建设置窗口的完整分区目录，集中封装具体 FXML Controller 的适配。 */
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
        List<SettingsPanelCatalog.Panel> panels = new ArrayList<>();
        List<AutoCloseable> resources = new ArrayList<>();
        try {
            addModelPanels(panels, resources, callbacks);
            addBehaviorPanels(panels, resources, callbacks);
            addIntegrationPanels(panels, resources, callbacks);
            addAppearancePanels(panels, resources);
            addMaintenancePanel(panels, resources);
            addCommunicationPanels(panels, resources, callbacks);
            panels.forEach(panel -> constrainContent(panel.category(), panel.root()));
            return new SettingsPanelCatalog(panels, resources);
        } catch (RuntimeException | Error failure) {
            closeCreated(resources, failure);
            throw failure;
        }
    }

    private void addModelPanels(List<SettingsPanelCatalog.Panel> panels,
                                List<AutoCloseable> resources, Callbacks callbacks) {
        var model = track(resources, models.createModel(result -> callbacks.applied(
                SettingsCategory.MODEL, result.message(), result.runtimeRefreshRequired())));
        panels.add(panel(SettingsCategory.MODEL, model.root(),
                SettingsPanelActions.asyncSaveAndTest(
                        (success, failure) -> model.controller().save(ignored -> success.run(), failure),
                        callbacks::modelSavedTip,
                        () -> model.controller().probe(
                                result -> callbacks.testFinished(SettingsCategory.MODEL,
                                        result.message(), result.succeeded()),
                                failure -> callbacks.testFinished(SettingsCategory.MODEL,
                                        "连接失败: " + SettingsFieldSupport.failureMessage(failure), false)),
                        "测试连接"), model.controller()::reload));

        var tiers = track(resources, models.createTiers(result -> callbacks.applied(
                SettingsCategory.TIERED_MODEL, result.message(), result.runtimeRefreshRequired())));
        panels.add(panel(SettingsCategory.TIERED_MODEL, tiers.root(),
                SettingsPanelActions.asyncSave(
                        (success, failure) -> tiers.controller().save(ignored -> success.run(), failure),
                        callbacks::modelSavedTip), tiers.controller()::reload));

        var embedding = track(resources, models.createEmbedding(result -> callbacks.applied(
                SettingsCategory.EMBEDDING, result.message(), result.runtimeRefreshRequired())));
        panels.add(panel(SettingsCategory.EMBEDDING, embedding.root(),
                SettingsPanelActions.asyncSaveAndTest(
                        (success, failure) -> embedding.controller().save(
                                ignored -> success.run(), failure),
                        callbacks::modelSavedTip,
                        () -> embedding.controller().probe(
                                result -> callbacks.testFinished(SettingsCategory.EMBEDDING,
                                        result.message(), result.succeeded()),
                                failure -> callbacks.testFinished(SettingsCategory.EMBEDDING,
                                        "嵌入测试失败: "
                                                + SettingsFieldSupport.failureMessage(failure), false)),
                        "测试嵌入"), embedding.controller()::reload));

        AgentSettingsPanel agent = track(resources,
                agents.create(callbacks::runtimeConfigurationChanged));
        panels.add(panel(SettingsCategory.AGENT, agent.root(),
                SettingsPanelActions.none(), null));
    }

    private void addBehaviorPanels(List<SettingsPanelCatalog.Panel> panels,
                                   List<AutoCloseable> resources, Callbacks callbacks) {
        var gepa = track(resources, behavior.createGepa(result -> callbacks.applied(
                SettingsCategory.GEPA, result.message(), result.runtimeRefreshRequired())));
        panels.add(panel(SettingsCategory.GEPA, gepa.root(),
                SettingsPanelActions.asyncSave(
                        (success, failure) -> gepa.controller().save(ignored -> success.run(), failure),
                        callbacks::modelSavedTip), gepa.controller()::reload));

        var evolution = track(resources, behavior.createSkillEvolution(result -> callbacks.applied(
                SettingsCategory.SKILL_EVOLUTION, result.message(),
                result.runtimeRefreshRequired())));
        panels.add(panel(SettingsCategory.SKILL_EVOLUTION, evolution.root(),
                SettingsPanelActions.asyncSave(
                        (success, failure) -> evolution.controller().save(
                                ignored -> success.run(), failure),
                        () -> "✓ 已保存，下一轮对话生效"), evolution.controller()::reload));

        var general = track(resources, behavior.createGeneral(result -> callbacks.applied(
                SettingsCategory.GENERAL, result.message(), result.runtimeRefreshRequired())));
        panels.add(panel(SettingsCategory.GENERAL, general.root(),
                SettingsPanelActions.asyncSave(
                        (success, failure) -> general.controller().save(
                                ignored -> success.run(), failure),
                        () -> "✓ 已保存"), general.controller()::reload));
    }

    private void addIntegrationPanels(List<SettingsPanelCatalog.Panel> panels,
                                      List<AutoCloseable> resources, Callbacks callbacks) {
        McpCenterView mcpPanel = track(resources,
                mcp.createPanel(callbacks::runtimeConfigurationChanged));
        panels.add(panel(SettingsCategory.MCP, mcpPanel.root(), SettingsPanelActions.none(), null));

        SiteCredentialPanel sitePanel = track(resources, sites.create());
        panels.add(panel(SettingsCategory.SITE, sitePanel.root(), SettingsPanelActions.none(), null));
    }

    private void addAppearancePanels(List<SettingsPanelCatalog.Panel> panels,
                                     List<AutoCloseable> resources) {
        var themes = track(resources, appearance.createAppearance());
        panels.add(panel(SettingsCategory.APPEARANCE, themes.root(),
                SettingsPanelActions.none(), themes.controller()::reload));

        var fonts = track(resources, appearance.createFonts());
        panels.add(panel(SettingsCategory.FONT, fonts.root(),
                SettingsPanelActions.none(), fonts.controller()::reload));
    }

    private void addMaintenancePanel(List<SettingsPanelCatalog.Panel> panels,
                                     List<AutoCloseable> resources) {
        var data = track(resources, maintenance.createTestDataMaintenance());
        panels.add(panel(SettingsCategory.TEST_DATA, data.root(), SettingsPanelActions.none(), null));
    }

    private void addCommunicationPanels(List<SettingsPanelCatalog.Panel> panels,
                                        List<AutoCloseable> resources, Callbacks callbacks) {
        var email = track(resources, communication.createEmail(result -> callbacks.applied(
                SettingsCategory.EMAIL, result.message(), false)));
        panels.add(panel(SettingsCategory.EMAIL, email.root(),
                SettingsPanelActions.asyncSaveAndTest(
                        (success, failure) -> email.controller().save(ignored -> success.run(), failure),
                        () -> "✓ 已保存",
                        () -> email.controller().probe(
                                result -> callbacks.testFinished(SettingsCategory.EMAIL,
                                        result.message(), result.succeeded()),
                                failure -> callbacks.testFinished(SettingsCategory.EMAIL,
                                        "邮件测试失败: "
                                                + SettingsFieldSupport.failureMessage(failure), false)),
                        "测试收发"), email.controller()::reload));

        var notifications = track(resources, communication.createNotifications(
                result -> callbacks.applied(SettingsCategory.NOTIFICATION,
                        result.message(), false)));
        panels.add(panel(SettingsCategory.NOTIFICATION, notifications.root(),
                SettingsPanelActions.asyncSave(
                        (success, failure) -> notifications.controller().save(
                                ignored -> success.run(), failure),
                        () -> "✓ 已保存"), notifications.controller()::reload));
    }

    private static SettingsPanelCatalog.Panel panel(
            SettingsCategory category, Node root, SettingsPanelActions actions, Runnable reload) {
        return new SettingsPanelCatalog.Panel(category, root, actions, reload);
    }

    private static <T extends AutoCloseable> T track(List<AutoCloseable> resources, T resource) {
        resources.add(resource);
        return resource;
    }

    private static void constrainContent(SettingsCategory category, Node panel) {
        if (category == SettingsCategory.AGENT) return;
        if (panel instanceof ScrollPane scrollPane && scrollPane.getContent() instanceof Region content) {
            content.setMaxWidth(760);
        }
    }

    private static void closeCreated(List<AutoCloseable> resources, Throwable failure) {
        for (int i = resources.size() - 1; i >= 0; i--) {
            try {
                resources.get(i).close();
            } catch (Throwable closeFailure) {
                failure.addSuppressed(closeFailure);
            }
        }
    }
}
