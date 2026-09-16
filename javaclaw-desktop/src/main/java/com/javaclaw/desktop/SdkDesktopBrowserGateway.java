package com.javaclaw.desktop;

import java.net.URI;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

import com.javaclaw.builtin.contracts.BrowserCommands;
import com.javaclaw.builtin.contracts.BrowserContracts;
import com.javaclaw.builtin.contracts.BrowserGrantContracts;
import com.javaclaw.builtin.contracts.BuiltinExtensionIds;
import com.javaclaw.builtin.contracts.SiteAccountContracts;
import com.javaclaw.client.CommandOptions;
import com.javaclaw.client.extension.BrowserClient;

/** 复用 Presenter 连接代次与后台请求执行器的浏览器 SDK 适配。 */
public final class SdkDesktopBrowserGateway implements DesktopBrowserGateway {
    private final DesktopPresenter presenter;

    /** @param presenter 当前 Desktop 共享的 SDK 连接与 UI 调度器 */
    public SdkDesktopBrowserGateway(DesktopPresenter presenter) {
        this.presenter = Objects.requireNonNull(presenter, "presenter");
    }

    @Override
    public CompletableFuture<BrowserCommands.Status> status(Scope scope) {
        return presenter.submitSettingsRequest(
                client -> client.builtins().sites().browser().status(scope.workspace(), scope.thread()));
    }

    @Override
    public CompletableFuture<BrowserCommands.Status> control(
            Scope scope, BrowserClient.Control control, long generation) {
        CommandOptions options = CommandOptions.create(generation);
        return presenter.submitSettingsRequest(client ->
                client.builtins().sites().browser().control(scope.workspace(), scope.thread(), control, options));
    }

    @Override
    public CompletableFuture<BrowserCommands.LoginForms> loginForms(Scope scope, long generation) {
        return presenter.submitSettingsRequest(client -> client.builtins()
                .sites()
                .browser()
                .loginForms(scope.workspace(), scope.thread(), CommandOptions.create(generation)));
    }

    @Override
    public CompletableFuture<SiteAccountContracts.AccountProjection> capture(
            Scope scope, long generation, BrowserContracts.CredentialsTarget target) {
        return presenter.submitSettingsRequest(client -> client.builtins()
                .sites()
                .browser()
                .capture(scope.workspace(), scope.thread(), target, CommandOptions.create(generation)));
    }

    @Override
    public CompletableFuture<SiteAccountContracts.AccountProjection> saveLogin(Scope scope, long generation) {
        return presenter.submitSettingsRequest(client -> client.builtins()
                .sites()
                .browser()
                .saveLogin(scope.workspace(), scope.thread(), CommandOptions.create(generation)));
    }

    @Override
    public CompletableFuture<BrowserGrantContracts.GrantList> grants(Scope scope) {
        return presenter.submitSettingsRequest(
                client -> client.builtins().sites().browser().grants(scope.workspace(), scope.thread()));
    }

    @Override
    public CompletableFuture<BrowserGrantContracts.Preview> previewGrant(Scope scope, URI origin) {
        return presenter.submitSettingsRequest(
                client -> client.builtins().sites().browser().previewGrant(scope.workspace(), scope.thread(), origin));
    }

    @Override
    public CompletableFuture<BrowserGrantContracts.Grant> confirmGrant(
            Scope scope, BrowserGrantContracts.Preview preview) {
        CommandOptions options = CommandOptions.create(0);
        return presenter.submitSettingsRequest(client ->
                client.builtins().sites().browser().confirmGrant(scope.workspace(), scope.thread(), preview, options));
    }

    @Override
    public CompletableFuture<BrowserGrantContracts.Grant> revokeGrant(Scope scope, BrowserGrantContracts.Grant grant) {
        CommandOptions options = CommandOptions.create(grant.revision());
        return presenter.submitSettingsRequest(client -> client.builtins()
                .sites()
                .browser()
                .revokeGrant(scope.workspace(), scope.thread(), grant.id(), options));
    }

    @Override
    public DesktopNotificationSubscription subscribe(Scope scope, Runnable invalidated) {
        return presenter.subscribeExtensionEvents(
                scope.workspace(), BuiltinExtensionIds.SITE, ignored -> invalidated.run());
    }
}
