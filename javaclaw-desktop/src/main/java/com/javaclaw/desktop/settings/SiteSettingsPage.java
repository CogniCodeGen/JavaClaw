package com.javaclaw.desktop.settings;

import java.util.Optional;

import javafx.scene.Node;

import com.javaclaw.api.Workspace;
import com.javaclaw.builtin.contracts.BuiltinExtensionIds;

/** 在同一个 Site 导航入口中组合平台 Secret 管理与扩展 ViewSchema v2 页面。 */
final class SiteSettingsPage implements ManagedSettingsPage {
    private final ViewSchemaSettingsPage views;
    private final SiteCredentialSettingsSection credentials;

    SiteSettingsPage(CoreSettingsGateway core, ExtensionSettingsGateway extensions) {
        views = new ViewSchemaSettingsPage(BuiltinExtensionIds.SITE, "网站", "受控站点、会话和凭据", extensions);
        credentials = new SiteCredentialSettingsSection(core, views::refreshAuthoritativeState);
        views.addPlatformSection(credentials.content());
    }

    @Override
    public Node content() {
        return views.content();
    }

    @Override
    public void activate() {
        credentials.activate();
        views.activate();
    }

    @Override
    public void deactivate() {
        views.deactivate();
    }

    @Override
    public boolean dirty() {
        return credentials.dirty() || views.dirty();
    }

    @Override
    public boolean pending() {
        return credentials.pending() || views.pending();
    }

    @Override
    public void workspaceChanged(Optional<Workspace> workspace) {
        views.workspaceChanged(workspace);
    }

    @Override
    public void warnUnsavedChanges() {
        if (credentials.dirty()) {
            credentials.warnUnsavedChanges();
        }
        if (views.dirty()) {
            views.warnUnsavedChanges();
        }
    }

    @Override
    public void discardDraft() {
        credentials.discardDraft();
        if (views.dirty()) {
            views.discardDraft();
        }
    }

    @Override
    public void dispose() {
        views.dispose();
    }
}
