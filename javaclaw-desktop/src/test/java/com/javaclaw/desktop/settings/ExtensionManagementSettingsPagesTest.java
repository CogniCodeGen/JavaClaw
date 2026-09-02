package com.javaclaw.desktop.settings;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.stage.Window;
import org.junit.jupiter.api.Test;

import com.javaclaw.desktop.FxTestSupport;
import com.javaclaw.protocol.BundleRpcContracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExtensionManagementSettingsPagesTest {
    @Test
    void bundle页从权威快照执行健康检查并启用可选扩展() {
        FxTestSupport.run(() -> {
            TestBundleSettingsGateway gateway = new TestBundleSettingsGateway();
            gateway.bundles.add(TestBundleSettingsGateway.bundle("com.example.docs", "1.0.0", 3, "DISABLED"));
            BundleSettingsPage page = attach(new BundleSettingsPage(gateway));

            page.activate();
            assertTrue(texts(page).stream().anyMatch(value -> value.contains("com.example.docs")));
            button(page, "健康检查").fire();
            assertTrue(texts(page).contains("HEALTHY"));
            button(page, "启用").fire();
            assertTrue(texts(page).contains("ENABLED"));
            assertFalse(page.dirty());

            page.warnUnsavedChanges();
            page.discardDraft();
            assertTrue(page.content() == page);
        });
    }

    @Test
    void bundle空目录与多条目选择保持动作边界() {
        FxTestSupport.run(() -> {
            TestBundleSettingsGateway gateway = new TestBundleSettingsGateway();
            BundleSettingsPage empty = attach(new BundleSettingsPage(gateway));
            empty.activate();
            assertTrue(button(empty, "健康检查").isDisabled());
            assertTrue(button(empty, "启用").isDisabled());

            BundleRpcContracts.Bundle disabled =
                    TestBundleSettingsGateway.bundle("com.example.one", "1.0.0", 1, "DISABLED");
            BundleRpcContracts.Bundle quarantined =
                    TestBundleSettingsGateway.bundle("com.example.two", "2.0.0", 4, "QUARANTINED");
            gateway.bundles.addAll(List.of(disabled, quarantined));
            button(empty, "健康检查");
            empty.activate();
            list(empty, BundleRpcContracts.Bundle.class).getSelectionModel().select(quarantined);
            assertTrue(texts(empty).stream().anyMatch(value -> value.contains("com.example.two")));
            assertFalse(button(empty, "健康检查").isDisabled());
        });
    }

    @Test
    void trustKey页区分活动与已撤销密钥并保护未完成草稿() {
        FxTestSupport.run(() -> {
            TestBundleSettingsGateway gateway = new TestBundleSettingsGateway();
            BundleRpcContracts.TrustKey active = TestBundleSettingsGateway.activeKey();
            gateway.trustKeys.add(active);
            gateway.trustKeys.add(new BundleRpcContracts.TrustKey(
                    "retired-key",
                    "e".repeat(64),
                    "f".repeat(64),
                    2,
                    BundleRpcContracts.TrustState.REVOKED,
                    TestBundleSettingsGateway.NOW,
                    TestBundleSettingsGateway.NOW.plusSeconds(1)));
            TrustKeySettingsPage page = attach(new TrustKeySettingsPage(gateway));

            page.activate();
            assertTrue(texts(page).contains("ACTIVE"));
            TextField keyId = field(page, "Trust Key 标识");
            keyId.setText("draft-key");
            assertTrue(page.dirty());
            page.warnUnsavedChanges();
            page.discardDraft();
            assertFalse(page.dirty());

            select(page, BundleRpcContracts.TrustKey.class, value -> value.id().equals("retired-key"));
            assertTrue(texts(page).contains("REVOKED"));
            assertTrue(button(page, "撤销并禁用关联 Bundle").isDisabled());
        });
    }

    @Test
    void trash页恢复条目后立即禁止重复文件动作() {
        FxTestSupport.run(() -> {
            TestBundleSettingsGateway gateway = new TestBundleSettingsGateway();
            BundleRpcContracts.Bundle removed =
                    TestBundleSettingsGateway.bundle("com.example.removed", "1.0.0", 1, "DISABLED");
            gateway.trash.add(TestBundleSettingsGateway.trash(removed, "trash-pages"));
            BundleTrashSettingsPage page = attach(new BundleTrashSettingsPage(gateway));

            page.activate();
            assertFalse(button(page, "恢复为新 revision").isDisabled());
            button(page, "恢复为新 revision").fire();
            assertTrue(texts(page).contains("RESTORED"));
            assertTrue(button(page, "恢复为新 revision").isDisabled());
            assertTrue(button(page, "永久清除").isDisabled());
            button(page, "刷新").fire();
            assertFalse(page.dirty());
        });
    }

    @Test
    void bundle卸载要求逐字危险确认并进入Trash() {
        FxTestSupport.run(() -> {
            TestBundleSettingsGateway gateway = new TestBundleSettingsGateway();
            BundleRpcContracts.Bundle bundle =
                    TestBundleSettingsGateway.bundle("com.example.removable", "1.0.0", 3, "DISABLED");
            gateway.bundles.add(bundle);
            BundleSettingsPage page = attach(new BundleSettingsPage(gateway));
            page.activate();

            completeTextDialog("not-confirmed");
            button(page, "卸载 Bundle").fire();
            assertEquals(List.of(bundle), gateway.bundles);

            completeTextDialog("UNINSTALL com.example.removable");
            button(page, "卸载 Bundle").fire();
            assertTrue(gateway.bundles.isEmpty());
            assertEquals("trash-com.example.removable", gateway.trash.getFirst().trashId());
        });
    }

    @Test
    void trustKey撤销确认后立即投影为已撤销() {
        FxTestSupport.run(() -> {
            TestBundleSettingsGateway gateway = new TestBundleSettingsGateway();
            gateway.trustKeys.add(TestBundleSettingsGateway.activeKey());
            TrustKeySettingsPage page = attach(new TrustKeySettingsPage(gateway));
            page.activate();

            completeTextDialog("REVOKE release-key");
            button(page, "撤销并禁用关联 Bundle").fire();

            assertEquals(
                    BundleRpcContracts.TrustState.REVOKED,
                    gateway.trustKeys.getFirst().state());
            assertTrue(texts(page).contains("REVOKED"));
            assertTrue(button(page, "撤销并禁用关联 Bundle").isDisabled());
        });
    }

    @Test
    void trash永久清除要求精确条目标识() {
        FxTestSupport.run(() -> {
            TestBundleSettingsGateway gateway = new TestBundleSettingsGateway();
            BundleRpcContracts.Bundle removed =
                    TestBundleSettingsGateway.bundle("com.example.purge", "1.0.0", 5, "DISABLED");
            gateway.trash.add(TestBundleSettingsGateway.trash(removed, "trash-purge"));
            BundleTrashSettingsPage page = attach(new BundleTrashSettingsPage(gateway));
            page.activate();

            completeTextDialog("PURGE trash-purge");
            button(page, "永久清除").fire();

            assertEquals(
                    BundleRpcContracts.TrashState.PURGED,
                    gateway.trash.getFirst().state());
            assertTrue(texts(page).contains("PURGED"));
            assertTrue(button(page, "永久清除").isDisabled());
        });
    }

    private static <T extends Parent> T attach(T page) {
        new Scene(page, 1_040, 720);
        page.applyCss();
        return page;
    }

    private static Button button(Parent root, String text) {
        return nodes(root, Button.class).stream()
                .filter(value -> text.equals(value.getText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("缺少按钮: " + text));
    }

    private static TextField field(Parent root, String accessibleText) {
        return nodes(root, TextField.class).stream()
                .filter(value -> accessibleText.equals(value.getAccessibleText()))
                .findFirst()
                .orElseThrow();
    }

    private static List<String> texts(Parent root) {
        return nodes(root, Label.class).stream().map(Label::getText).toList();
    }

    private static <T> ListView<T> list(Parent root, Class<T> type) {
        ListView<?> found = nodes(root, ListView.class).stream()
                .filter(value -> !value.getItems().isEmpty())
                .filter(value -> type.isInstance(value.getItems().getFirst()))
                .findFirst()
                .orElseThrow();
        return castList(found);
    }

    private static <T> void select(Parent root, Class<T> type, java.util.function.Predicate<T> predicate) {
        ListView<T> values = list(root, type);
        T selected = values.getItems().stream().filter(predicate).findFirst().orElseThrow();
        values.getSelectionModel().select(selected);
    }

    private static void completeTextDialog(String confirmation) {
        Platform.runLater(() -> Window.getWindows().stream()
                .map(Window::getScene)
                .filter(java.util.Objects::nonNull)
                .map(Scene::getRoot)
                .filter(DialogPane.class::isInstance)
                .map(DialogPane.class::cast)
                .findFirst()
                .ifPresent(dialog -> {
                    Node editor = dialog.lookup(".text-field");
                    if (editor instanceof TextField field) {
                        field.setText(confirmation);
                    }
                    Node accept = dialog.lookupButton(ButtonType.OK);
                    if (accept instanceof Button button) {
                        button.fire();
                    }
                }));
    }

    @SuppressWarnings("unchecked")
    private static <T> ListView<T> castList(ListView<?> value) {
        return (ListView<T>) value;
    }

    private static <T extends Node> List<T> nodes(Parent root, Class<T> type) {
        ArrayList<T> result = new ArrayList<>();
        Queue<Node> pending = new ArrayDeque<>();
        pending.add(root);
        while (!pending.isEmpty()) {
            Node current = pending.remove();
            if (type.isInstance(current)) {
                result.add(type.cast(current));
            }
            if (current instanceof Parent parent) {
                pending.addAll(parent.getChildrenUnmodifiable());
            }
        }
        return List.copyOf(result);
    }
}
