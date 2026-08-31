package com.javaclaw.desktop;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

import javafx.scene.control.ListView;
import javafx.scene.layout.BorderPane;

import com.javaclaw.sdk.model.NetworkGrantInfo;

/** 准确端点授权表单；不能用通配网段或“信任全部网络”取代明确范围。 */
final class NetworkGrantPane extends BorderPane {
    private final ManagementViewModel model;
    private final ListView<NetworkGrantInfo> grants = ManagementForms.list(value ->
            value.purpose() + " · " + value.origin() + "\n" + (value.enabled() ? "有效至 " + value.expiresAt() : "已撤销"));
    private final BorderPane detail = new BorderPane();

    NetworkGrantPane(ManagementViewModel model) {
        this.model = model;
        setCenter(ManagementForms.split(grants, detail));
        setTop(ManagementForms.actions(
                ManagementForms.command("＋ 精确授权", UiActionKind.PRIMARY, model, () -> edit(null)),
                ManagementForms.command("刷新", UiActionKind.GHOST, model, this::reload)));
        grants.getSelectionModel().selectedItemProperty().addListener((ignored, old, value) -> {
            if (value != null) {
                edit(value);
            }
        });
        detail.setCenter(ManagementForms.form(
                ManagementForms.hint("默认拒绝私网。授权必须同时匹配工作区、用途、准确源、解析 IP 和到期时间。云元数据、本机控制与链路本地地址永远不可授权。")));
        reload();
    }

    private void reload() {
        String workspace = model.workspaceId();
        model.execute(
                "读取私网授权",
                sdk -> sdk.extensions().listNetworkGrants(workspace),
                values -> grants.getItems().setAll(values));
    }

    private void edit(NetworkGrantInfo original) {
        var purpose = ManagementForms.choices(
                List.of("BROWSER", "WEB", "MCP", "OAUTH"),
                value -> value,
                original == null ? "BROWSER" : original.purpose());
        var origin = ManagementForms.text(
                original == null ? "" : original.origin().toString(), "https://intranet.example:8443");
        var addresses = ManagementForms.area(original == null ? "" : String.join("\n", original.addresses()), 4);
        var expires = ManagementForms.text(
                original == null
                        ? Instant.now().plusSeconds(3600).toString()
                        : original.expiresAt().toString(),
                "ISO-8601，最多三十天");
        origin.setEditable(original == null);
        purpose.setDisable(original != null);
        var save = ManagementForms.command("确认授权", UiActionKind.PRIMARY, model, () -> {
            try {
                var value = new NetworkGrantInfo(
                        original == null ? null : original.id(),
                        model.workspaceId(),
                        purpose.getValue(),
                        URI.create(origin.getText().strip()),
                        addresses
                                .getText()
                                .lines()
                                .map(String::strip)
                                .filter(line -> !line.isBlank())
                                .collect(Collectors.toSet()),
                        Instant.parse(expires.getText().strip()),
                        true,
                        original == null ? 0 : original.revision(),
                        null);
                if (!ManagementForms.confirm(
                        this,
                        model,
                        "批准准确私网端点",
                        value.purpose() + "\n" + value.origin() + "\n" + value.addresses() + "\n有效至 "
                                + value.expiresAt() + "\n\n仅对当前工作区生效，不开放子进程原始网络。")) {
                    return;
                }
                model.execute(
                        "保存私网授权",
                        sdk -> sdk.extensions().saveNetworkGrant(value, true, ManagementViewModel.key("network-grant")),
                        saved -> {
                            reload();
                            edit(saved);
                        });
            } catch (IllegalArgumentException invalid) {
                ManagementForms.showText(this, "授权格式无效", "检查端点、准确 IP 和 ISO-8601 有效期。");
            }
        });
        var revoke = ManagementForms.command("立即撤销", UiActionKind.DANGER, model, () -> {
            if (original != null && ManagementForms.confirm(this, model, "撤销网络授权", "下一次连接将重新校验并拒绝使用此授权。")) {
                model.execute(
                        "撤销授权",
                        sdk -> sdk.extensions()
                                .disableNetworkGrant(
                                        original.id(), original.revision(), ManagementViewModel.key("network-revoke")),
                        ignored -> reload());
            }
        });
        revoke.setVisible(original != null);
        revoke.setManaged(original != null);
        detail.setCenter(ManagementForms.scroll(ManagementForms.form(
                ManagementForms.field("用途", purpose),
                ManagementForms.field("准确来源", origin),
                ManagementForms.field("准确私网 IP（每行一个）", addresses),
                ManagementForms.field("到期时间", expires),
                ManagementForms.actions(save, revoke))));
    }
}
