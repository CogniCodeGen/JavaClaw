package com.javaclaw.desktop.settings;

import java.util.Optional;

import javafx.scene.Node;

import com.javaclaw.api.Workspace;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.builtin.contracts.BuiltinExtensionIds;
import com.javaclaw.builtin.contracts.SiteContracts;
import com.javaclaw.builtin.contracts.SiteRegistrationContracts;
import com.javaclaw.desktop.view.ViewCommandInvocation;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.ExtensionRpcContracts;

/** 网站设置只拥有一个权威选择；声明式配置、账号与隔离登录共享草稿和作用域保护。 */
final class SiteSettingsPage implements ManagedSettingsPage {
    private final ViewSchemaSettingsPage views;
    private final ExtensionSettingsGateway extensions;
    private Optional<WorkspaceId> workspace = Optional.empty();
    private SiteRegistrationDialog registration;
    private final SiteCredentialSettingsSection credentials;
    private final SiteAccountSettingsSection accounts;
    private final SiteLoginSettingsSection login;
    private final SiteCredentialSettingsDialog credentialDialog;
    private final SiteSettingsLayout layout;
    private boolean active;
    private boolean accountsExpanded;

    SiteSettingsPage(CoreSettingsGateway core, ExtensionSettingsGateway extensions) {
        this.extensions = extensions;
        views = new ViewSchemaSettingsPage(
                BuiltinExtensionIds.SITE,
                "网站会话",
                "选择网站，配置访问范围、账号和登录会话。",
                BuiltinExtensionIds.SITE + ".management",
                extensions);
        credentials = new SiteCredentialSettingsSection(core, views::refreshAuthoritativeState);
        accounts = new SiteAccountSettingsSection(extensions, views::refreshAuthoritativeState);
        login = new SiteLoginSettingsSection(extensions, this::loginChanged);
        credentialDialog = new SiteCredentialSettingsDialog(credentials);
        layout = new SiteSettingsLayout(
                new SiteSettingsActions(
                        views::confirmContextChange,
                        this::dirty,
                        this::pending,
                        this::discardDraft,
                        new SiteSettingsActions.Dialogs(this::registerAddress, this::manageCredentials),
                        this::selectedSite,
                        this::accountsExpanded),
                accounts.content(),
                login.content());
        views.configurePresentation(layout, this::platformDirty, this::platformPending, this::discardPlatformDrafts);
        views.onCommandSucceeded(this::commandSucceeded);
        views.onStateChanged(this::viewStateChanged);
        accounts.setContextGuard(
                () -> views.dirty() || credentials.dirty() || registrationDirty(),
                () -> views.pending() || credentials.pending() || login.pending() || registrationPending(),
                views::confirmContextChange);
        login.setContextGuard(
                () -> views.dirty() || accounts.dirty() || credentials.dirty() || registrationDirty(),
                () -> views.pending() || accounts.pending() || credentials.pending() || registrationPending());
        accounts.setStateChanged(this::accountStateChanged);
        credentials.setStateChanged(this::viewStateChanged);
        login.onStateChanged(this::viewStateChanged);
    }

    @Override
    public Node content() {
        return views.content();
    }

    @Override
    public void activate() {
        active = true;
        views.activate();
        accountsExpanded(accountsExpanded);
    }

    @Override
    public void invalidateCache() {
        views.invalidateCache();
        accounts.invalidateCache();
        login.invalidateCache();
    }

    @Override
    public void deactivate() {
        active = false;
        disposeRegistration();
        credentialDialog.dispose();
        accounts.deactivate();
        login.deactivate();
        views.deactivate();
    }

    @Override
    public boolean dirty() {
        return platformDirty() || views.dirty();
    }

    @Override
    public boolean pending() {
        return platformPending() || views.pending();
    }

    @Override
    public void workspaceChanged(Optional<Workspace> workspace) {
        Optional<WorkspaceId> next = workspace.map(Workspace::id);
        if (!this.workspace.equals(next)) {
            disposeRegistration();
            this.workspace = next;
        }
        credentialDialog.dispose();
        accounts.workspaceChanged(workspace);
        login.workspaceChanged(workspace);
        views.workspaceChanged(workspace);
    }

    @Override
    public void warnUnsavedChanges() {
        if (registrationDirty()) {
            registration.warnUnsavedChanges();
        }
        if (accounts.dirty()) {
            accounts.warnUnsavedChanges();
        }
        if (credentials.dirty()) {
            credentials.warnUnsavedChanges();
        }
        if (views.dirty()) {
            views.warnUnsavedChanges();
        }
    }

    @Override
    public void discardDraft() {
        discardPlatformDrafts();
        views.discardDraft();
    }

    @Override
    public void dispose() {
        active = false;
        disposeRegistration();
        credentialDialog.dispose();
        accounts.dispose();
        login.dispose();
        views.dispose();
    }

    private boolean platformDirty() {
        return accounts.dirty() || credentials.dirty() || login.dirty() || registrationDirty();
    }

    private boolean platformPending() {
        return accounts.pending() || credentials.pending() || login.pending() || registrationPending();
    }

    private void discardPlatformDrafts() {
        disposeRegistration();
        credentials.discardDraft();
        accounts.discardDraft();
        login.discardDraft();
    }

    private void selectedSite(Optional<SiteContracts.Projection> selected) {
        accounts.setSite(selected);
        login.setSite(selected);
    }

    private void accountsExpanded(boolean expanded) {
        accountsExpanded = expanded;
        if (active && expanded) {
            accounts.activate();
            login.activate();
        } else {
            accounts.deactivate();
            login.deactivate();
        }
    }

    private boolean registrationDirty() {
        return registration != null && registration.dirty();
    }

    private boolean registrationPending() {
        return registration != null && registration.pending();
    }

    private void registerAddress() {
        if (!active || workspace.isEmpty() || !views.confirmContextChange()) {
            return;
        }
        WorkspaceId target = workspace.orElseThrow();
        registration = new SiteRegistrationDialog(
                extensions, target, result -> registrationCompleted(target, result), this::viewStateChanged);
        registration.show(content());
    }

    private void registrationCompleted(WorkspaceId target, SiteRegistrationContracts.Completed result) {
        if (active && workspace.filter(target::equals).isPresent()) {
            registration = null;
            accounts.invalidateCache();
            login.invalidateCache();
            layout.created();
            views.selectSource("documents", Optional.of(result.siteId()));
        }
    }

    private void disposeRegistration() {
        SiteRegistrationDialog previous = registration;
        registration = null;
        if (previous != null) {
            previous.dispose();
        }
    }

    private void manageCredentials() {
        if (views.confirmContextChange()) {
            credentialDialog.show(content());
        }
    }

    private void viewStateChanged() {
        accounts.refreshContext();
        login.refreshContext();
        layout.refreshActions();
    }

    private void accountStateChanged() {
        login.refreshContext();
        layout.refreshActions();
    }

    private void loginChanged() {
        accounts.invalidateCache();
        if (active && accountsExpanded) {
            accounts.activate();
        }
        views.refreshAuthoritativeState();
    }

    private void commandSucceeded(ViewCommandInvocation invocation, ExtensionRpcContracts.CallResult result) {
        accounts.invalidateCache();
        login.invalidateCache();
        if (invocation.operation().equals("site/create")) {
            SiteContracts.Projection created =
                    new CanonicalJson().decode(result.payload(), SiteContracts.Projection.class);
            layout.created();
            views.selectSource("documents", Optional.of(created.id()));
        }
    }
}
