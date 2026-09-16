package com.javaclaw.desktop.shell;

import java.net.URI;
import java.time.Instant;
import java.util.Objects;
import java.util.function.BooleanSupplier;

import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import com.javaclaw.api.SecurityGrantState;
import com.javaclaw.builtin.contracts.BrowserGrantContracts;
import com.javaclaw.desktop.DesktopBrowserGateway;
import com.javaclaw.desktop.component.PlatformComponentFactory;
import com.javaclaw.desktop.component.PlatformComponentFactory.ActionSize;
import com.javaclaw.desktop.component.PlatformComponentFactory.ActionStyle;

/** 当前对话来源授权面板；用户预览后才可确认，异步回执不能越过对话或连接代次。 */
final class BrowserGrantPane extends VBox implements AutoCloseable {
    private final DesktopBrowserGateway gateway;
    private final DesktopBrowserGateway.Scope scope;
    private final BooleanSupplier current;
    private final Runnable changed;
    private final PlatformComponentFactory components = new PlatformComponentFactory();
    private final TextField origin = new TextField();
    private final Button previewButton = components.action("预览授权", ActionStyle.SOFT, ActionSize.COMPACT);
    private final Button confirmButton = components.action("确认授权", ActionStyle.PRIMARY, ActionSize.COMPACT);
    private final Label previewText = new Label();
    private final Label feedback = new Label();
    private final VBox grants = new VBox(8);
    private BrowserGrantContracts.Preview preview;
    private long generation;
    private boolean pending;
    private boolean closed;

    BrowserGrantPane(
            DesktopBrowserGateway gateway,
            DesktopBrowserGateway.Scope scope,
            BooleanSupplier current,
            Runnable changed) {
        super(12);
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.scope = Objects.requireNonNull(scope, "scope");
        this.current = Objects.requireNonNull(current, "current");
        this.changed = Objects.requireNonNull(changed, "changed");
        Label explanation = new Label("管理当前对话浏览器允许访问的来源。授权仅用于此对话，不授予其他工具。");
        explanation.setWrapText(true);
        explanation.getStyleClass().add("sec-hint");
        origin.setId("browserGrantOrigin");
        origin.setPromptText("https://example.com");
        origin.setMinWidth(0);
        previewButton.setId("browserGrantPreview");
        confirmButton.setId("browserGrantConfirm");
        previewText.setId("browserGrantPreviewText");
        previewText.setWrapText(true);
        feedback.setId("browserGrantFeedback");
        feedback.setWrapText(true);
        HBox input = new HBox(8, origin, previewButton);
        HBox.setHgrow(origin, Priority.ALWAYS);
        ScrollPane list = new ScrollPane(grants);
        list.setFitToWidth(true);
        list.setPrefViewportHeight(180);
        list.setMaxHeight(240);
        getChildren().setAll(explanation, input, previewText, confirmButton, feedback, new Label("已授权来源"), list);
        origin.textProperty().addListener((ignored, before, after) -> clearPreview());
        previewButton.setOnAction(event -> preview());
        confirmButton.setOnAction(event -> confirm());
        renderBusy();
    }

    void reload() {
        if (!active() || pending) {
            return;
        }
        long request = begin();
        gateway.grants(scope).whenComplete((result, failure) -> {
            if (!accept(request)) {
                return;
            }
            pending = false;
            if (failure != null) {
                feedback.setText("来源列表读取失败，请关闭后重试。");
            } else if (result.grants().stream().anyMatch(grant -> !owned(grant))) {
                feedback.setText("来源列表不属于当前对话，已拒绝显示。");
                grants.getChildren().clear();
            } else {
                renderGrants(result);
            }
            renderBusy();
        });
    }

    private void preview() {
        if (!active() || pending) {
            return;
        }
        URI requested;
        try {
            requested = BrowserGrantContracts.normalizeOrigin(
                    URI.create(origin.getText().strip()));
        } catch (IllegalArgumentException invalid) {
            clearPreview();
            feedback.setText("请输入精确 HTTPS 来源，不包含路径、查询参数或通配符。");
            return;
        }
        clearPreview();
        long request = begin();
        gateway.previewGrant(scope, requested).whenComplete((value, failure) -> {
            if (!accept(request)) {
                return;
            }
            pending = false;
            if (failure != null || !owned(value) || !value.origin().equals(requested)) {
                feedback.setText("授权预览未确认，请重试。");
            } else {
                preview = value;
                previewText.setText("确认后允许当前对话浏览器访问此来源：\n" + value.origin() + "\n包括该来源下的页面和资源。");
                feedback.setText("请检查来源后点击确认授权。");
            }
            renderBusy();
        });
    }

    private void confirm() {
        if (!active() || pending || preview == null) {
            return;
        }
        BrowserGrantContracts.Preview confirmed = preview;
        if (!Instant.now().isBefore(confirmed.expiresAt())) {
            clearPreview();
            feedback.setText("授权预览已过期，请重新预览。");
            return;
        }
        long request = begin();
        gateway.confirmGrant(scope, confirmed).whenComplete((value, failure) -> {
            if (!accept(request)) {
                return;
            }
            pending = false;
            clearPreview();
            if (failure != null
                    || !owned(value)
                    || !value.origin().equals(confirmed.origin())
                    || value.state() != SecurityGrantState.ACTIVE) {
                feedback.setText("授权结果未确认，请重新读取列表后检查。");
            } else {
                feedback.setText("已授权 " + value.origin());
                changed.run();
            }
            reload();
        });
    }

    private void revoke(BrowserGrantContracts.Grant grant) {
        if (!active() || pending || !owned(grant) || grant.state() != SecurityGrantState.ACTIVE) {
            return;
        }
        long request = begin();
        gateway.revokeGrant(scope, grant).whenComplete((value, failure) -> {
            if (!accept(request)) {
                return;
            }
            pending = false;
            if (failure != null
                    || !owned(value)
                    || !value.id().equals(grant.id())
                    || value.state() != SecurityGrantState.REVOKED) {
                feedback.setText("撤销结果未确认，请重新读取列表后检查。");
            } else {
                feedback.setText("已撤销 " + grant.origin());
                changed.run();
            }
            reload();
        });
    }

    private void renderGrants(BrowserGrantContracts.GrantList result) {
        grants.getChildren().clear();
        for (BrowserGrantContracts.Grant grant : result.grants()) {
            Label label = new Label(grant.origin().toString());
            label.setMinWidth(0);
            label.setWrapText(true);
            HBox.setHgrow(label, Priority.ALWAYS);
            boolean active = grant.state() == SecurityGrantState.ACTIVE;
            Button revoke = components.action(active ? "撤销" : "已撤销", ActionStyle.GHOST, ActionSize.COMPACT);
            revoke.setId("browserGrantRevoke");
            revoke.setUserData(grant);
            revoke.setMinWidth(Region.USE_PREF_SIZE);
            revoke.setDisable(!active);
            revoke.setOnAction(event -> revoke(grant));
            grants.getChildren().add(new HBox(8, label, revoke));
        }
        if (result.grants().isEmpty()) {
            grants.getChildren().add(new Label("当前对话尚未授权任何来源。"));
        }
    }

    private long begin() {
        pending = true;
        renderBusy();
        return ++generation;
    }

    private void clearPreview() {
        preview = null;
        previewText.setText("");
        renderBusy();
    }

    private void renderBusy() {
        origin.setDisable(pending || closed);
        previewButton.setDisable(pending || closed);
        confirmButton.setDisable(pending || closed || preview == null);
        grants.setDisable(pending || closed);
    }

    private boolean active() {
        return !closed && current.getAsBoolean();
    }

    private boolean accept(long request) {
        return active() && request == generation;
    }

    private boolean owned(BrowserGrantContracts.Preview value) {
        return value != null
                && value.workspaceId().equals(scope.workspace())
                && value.threadId().equals(scope.thread());
    }

    private boolean owned(BrowserGrantContracts.Grant value) {
        return value != null
                && value.workspaceId().equals(scope.workspace())
                && value.threadId().equals(scope.thread());
    }

    @Override
    public void close() {
        closed = true;
        generation++;
        renderBusy();
    }
}
