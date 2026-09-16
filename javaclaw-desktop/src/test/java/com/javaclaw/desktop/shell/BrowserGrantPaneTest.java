package com.javaclaw.desktop.shell;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import org.junit.jupiter.api.Test;

import com.javaclaw.api.SecurityGrantState;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.builtin.contracts.BrowserGrantContracts;
import com.javaclaw.desktop.DesktopBrowserGateway;
import com.javaclaw.desktop.DesktopStylesheets;
import com.javaclaw.desktop.FxTestSupport;
import com.javaclaw.desktop.NativeUiEvidence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrowserGrantPaneTest {
    private static final URI ORIGIN = URI.create("https://example.com");
    private static final DesktopBrowserGateway.Scope SCOPE =
            new DesktopBrowserGateway.Scope(WorkspaceId.random(), ThreadId.random());

    @Test
    void 用户看到精确来源后显式确认且重复点击不重复授权() {
        FxTestSupport.run(() -> {
            var gateway = new BrowserGrantGatewayFixture();
            AtomicInteger changes = new AtomicInteger();
            try (var pane = pane(gateway, new AtomicBoolean(true), changes)) {
                gateway.lists.getFirst().complete(new BrowserGrantContracts.GrantList(List.of()));
                origin(pane).setText("https://example.com:443");
                button(pane, "browserGrantPreview").fire();
                button(pane, "browserGrantPreview").fire();
                assertEquals(1, gateway.previews);
                assertEquals(0, gateway.confirmations);
                assertEquals(ORIGIN, gateway.receivedOrigin);
                var preview = preview(SCOPE, Instant.now().plusSeconds(300));
                gateway.previewed.complete(preview);
                assertTrue(((Label) pane.lookup("#browserGrantPreviewText"))
                        .getText()
                        .contains(ORIGIN.toString()));
                button(pane, "browserGrantConfirm").fire();
                button(pane, "browserGrantConfirm").fire();
                assertEquals(1, gateway.confirmations);
                assertEquals(SCOPE, gateway.receivedScope);
                assertSame(preview, gateway.receivedPreview);
                var grant = grant(1, SecurityGrantState.ACTIVE);
                gateway.confirmed.complete(grant);
                gateway.lists.getLast().complete(new BrowserGrantContracts.GrantList(List.of(grant)));
                assertEquals(1, changes.get());
                assertTrue(button(pane, "browserGrantConfirm").isDisabled());
                NativeUiEvidence.capture(pane, "browser-origin-grants.png");
            }
        });
    }

    @Test
    void 非HTTPS路径通配符与其他对话的预览均不能进入确认() {
        FxTestSupport.run(() -> {
            var gateway = new BrowserGrantGatewayFixture();
            try (var pane = pane(gateway, new AtomicBoolean(true), new AtomicInteger())) {
                gateway.lists.getFirst().complete(new BrowserGrantContracts.GrantList(List.of()));
                for (String invalid :
                        List.of("http://example.com", "https://example.com/path", "https://*.example.com")) {
                    origin(pane).setText(invalid);
                    button(pane, "browserGrantPreview").fire();
                    assertTrue(button(pane, "browserGrantConfirm").isDisabled());
                }
                assertEquals(0, gateway.previews);
                origin(pane).setText(ORIGIN.toString());
                button(pane, "browserGrantPreview").fire();
                var other = new DesktopBrowserGateway.Scope(SCOPE.workspace(), ThreadId.random());
                gateway.previewed.complete(preview(other, Instant.now().plusSeconds(300)));
                assertTrue(button(pane, "browserGrantConfirm").isDisabled());
                assertEquals(0, gateway.confirmations);
            }
        });
    }

    @Test
    void 过期预览以及对话切换后的晚回执不能触发授权() {
        FxTestSupport.run(() -> {
            var gateway = new BrowserGrantGatewayFixture();
            AtomicBoolean current = new AtomicBoolean(true);
            try (var pane = pane(gateway, current, new AtomicInteger())) {
                gateway.lists.getFirst().complete(new BrowserGrantContracts.GrantList(List.of()));
                origin(pane).setText(ORIGIN.toString());
                button(pane, "browserGrantPreview").fire();
                gateway.previewed.complete(preview(SCOPE, Instant.now().minusSeconds(1)));
                button(pane, "browserGrantConfirm").fire();
                assertEquals(0, gateway.confirmations);
                assertTrue(button(pane, "browserGrantConfirm").isDisabled());
            }
            var late = new BrowserGrantGatewayFixture();
            try (var pane = pane(late, current, new AtomicInteger())) {
                late.lists.getFirst().complete(new BrowserGrantContracts.GrantList(List.of()));
                origin(pane).setText(ORIGIN.toString());
                button(pane, "browserGrantPreview").fire();
                current.set(false);
                late.previewed.complete(preview(SCOPE, Instant.now().plusSeconds(300)));
                button(pane, "browserGrantConfirm").fire();
                assertEquals(0, late.confirmations);
                assertTrue(button(pane, "browserGrantConfirm").isDisabled());
            }
        });
    }

    @Test
    void 撤销冻结用户看到的授权版本且切换对话后不接纳旧撤销回执() {
        FxTestSupport.run(() -> {
            var gateway = new BrowserGrantGatewayFixture();
            AtomicBoolean current = new AtomicBoolean(true);
            AtomicInteger changes = new AtomicInteger();
            try (var pane = pane(gateway, current, changes)) {
                var original = grant(3, SecurityGrantState.ACTIVE);
                gateway.lists.getFirst().complete(new BrowserGrantContracts.GrantList(List.of(original)));
                pane.applyCss();
                pane.layout();
                button(pane, "browserGrantRevoke").fire();
                button(pane, "browserGrantRevoke").fire();
                assertEquals(1, gateway.revocations);
                assertSame(original, gateway.receivedGrant);
                assertEquals(SCOPE, gateway.receivedScope);
                current.set(false);
                gateway.revoked.complete(new BrowserGrantContracts.Grant(
                        original.id(),
                        4,
                        SecurityGrantState.REVOKED,
                        SCOPE.workspace(),
                        SCOPE.thread(),
                        ORIGIN,
                        Instant.now()));
                assertEquals(0, changes.get());
                assertEquals(1, gateway.lists.size());
            }
        });
    }

    private static BrowserGrantPane pane(
            BrowserGrantGatewayFixture gateway, AtomicBoolean current, AtomicInteger changes) {
        BrowserGrantPane pane = new BrowserGrantPane(gateway, SCOPE, current::get, changes::incrementAndGet);
        pane.setStyle("-fx-padding: 16;");
        new Scene(pane, 560, 480);
        DesktopStylesheets.applyTo(pane);
        pane.reload();
        pane.applyCss();
        pane.layout();
        return pane;
    }

    private static TextField origin(BrowserGrantPane pane) {
        return (TextField) pane.lookup("#browserGrantOrigin");
    }

    private static Button button(BrowserGrantPane pane, String id) {
        return (Button) pane.lookup("#" + id);
    }

    private static BrowserGrantContracts.Preview preview(DesktopBrowserGateway.Scope scope, Instant expiry) {
        return new BrowserGrantContracts.Preview(scope.workspace(), scope.thread(), ORIGIN, expiry, "a".repeat(64));
    }

    private static BrowserGrantContracts.Grant grant(long revision, SecurityGrantState state) {
        return new BrowserGrantContracts.Grant(
                UUID.randomUUID().toString(),
                revision,
                state,
                SCOPE.workspace(),
                SCOPE.thread(),
                ORIGIN,
                Instant.now());
    }
}
