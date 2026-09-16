package com.javaclaw.desktop.shell;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.javaclaw.builtin.contracts.BrowserCommands;
import com.javaclaw.builtin.contracts.BrowserContracts;
import com.javaclaw.builtin.contracts.BrowserGrantContracts;
import com.javaclaw.builtin.contracts.SiteAccountContracts;
import com.javaclaw.client.extension.BrowserClient;
import com.javaclaw.desktop.DesktopBrowserGateway;
import com.javaclaw.desktop.DesktopNotificationSubscription;

/** 授权窗口的异步 SDK 夹具；控制回执先后顺序，不使用真实账号或网络。 */
final class BrowserGrantGatewayFixture implements DesktopBrowserGateway {
    final List<CompletableFuture<BrowserGrantContracts.GrantList>> lists = new ArrayList<>();
    final CompletableFuture<BrowserGrantContracts.Preview> previewed = new CompletableFuture<>();
    final CompletableFuture<BrowserGrantContracts.Grant> confirmed = new CompletableFuture<>();
    final CompletableFuture<BrowserGrantContracts.Grant> revoked = new CompletableFuture<>();
    Scope receivedScope;
    URI receivedOrigin;
    BrowserGrantContracts.Preview receivedPreview;
    BrowserGrantContracts.Grant receivedGrant;
    int previews;
    int confirmations;
    int revocations;

    @Override
    public CompletableFuture<BrowserGrantContracts.GrantList> grants(Scope scope) {
        receivedScope = scope;
        var future = new CompletableFuture<BrowserGrantContracts.GrantList>();
        lists.add(future);
        return future;
    }

    @Override
    public CompletableFuture<BrowserGrantContracts.Preview> previewGrant(Scope scope, URI origin) {
        previews++;
        receivedScope = scope;
        receivedOrigin = origin;
        return previewed;
    }

    @Override
    public CompletableFuture<BrowserGrantContracts.Grant> confirmGrant(
            Scope scope, BrowserGrantContracts.Preview preview) {
        confirmations++;
        receivedScope = scope;
        receivedPreview = preview;
        return confirmed;
    }

    @Override
    public CompletableFuture<BrowserGrantContracts.Grant> revokeGrant(Scope scope, BrowserGrantContracts.Grant grant) {
        revocations++;
        receivedScope = scope;
        receivedGrant = grant;
        return revoked;
    }

    @Override
    public CompletableFuture<BrowserCommands.Status> status(Scope scope) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException());
    }

    @Override
    public CompletableFuture<BrowserCommands.Status> control(
            Scope scope, BrowserClient.Control control, long generation) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException());
    }

    @Override
    public CompletableFuture<BrowserCommands.LoginForms> loginForms(Scope scope, long generation) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException());
    }

    @Override
    public CompletableFuture<SiteAccountContracts.AccountProjection> capture(
            Scope scope, long generation, BrowserContracts.CredentialsTarget target) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException());
    }

    @Override
    public CompletableFuture<SiteAccountContracts.AccountProjection> saveLogin(Scope scope, long generation) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException());
    }

    @Override
    public DesktopNotificationSubscription subscribe(Scope scope, Runnable invalidated) {
        return () -> {};
    }
}
