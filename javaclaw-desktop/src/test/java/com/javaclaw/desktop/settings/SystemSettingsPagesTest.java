package com.javaclaw.desktop.settings;

import java.util.List;

import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.Test;

import com.javaclaw.desktop.FxTestSupport;
import com.javaclaw.protocol.ProtocolVersion;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SystemSettingsPagesTest {
    @Test
    void 连接页恢复说明与当前协商协议常量一致() {
        FxTestSupport.run(() -> {
            ConnectionSettingsPage page = new ConnectionSettingsPage(new TestCoreSettingsGateway());
            VBox root = attach(page);
            page.activate();
            root.applyCss();

            List<String> displayed = labels(root);
            assertTrue(displayed.contains("应用协议 v" + ProtocolVersion.CURRENT + " / JSON-RPC 2.0"));
            assertTrue(displayed.stream()
                    .anyMatch(text -> text.startsWith("“重新连接”会关闭旧会话，并重新协商第 " + ProtocolVersion.CURRENT + " 版协议。")));
            assertFalse(displayed.stream().anyMatch(text -> text.contains("重新协商第 2 版协议")));
        });
    }

    @Test
    void 诊断页渲染全部脱敏子系统且刷新保留可用操作() {
        FxTestSupport.run(() -> {
            DiagnosticsSettingsPage page = new DiagnosticsSettingsPage(new TestCoreSettingsGateway());
            VBox root = attach(page);
            Scene scene = root.getScene();
            page.activate();
            root.applyCss();

            List<String> labels = labels(page);
            assertTrue(labels.contains("构建与数据"));
            assertTrue(labels.contains("模型与密钥库"));
            assertTrue(labels.contains("连接与隔离运行器"));
            assertTrue(labels.contains("发行生命周期"));
            Button refresh = button(root, "刷新");
            refresh.fire();
            assertFalse(button(root, "复制摘要").isDisabled());
            assertFalse(button(root, "导出 JSON").isDisabled());
            assertTrue(scene.getRoot() == root);
        });
    }

    @Test
    void 生命周期页展示固定退出策略并按定时任务权威状态修复() {
        FxTestSupport.run(() -> {
            LifecycleSettingsPage page = new LifecycleSettingsPage(new TestCoreSettingsGateway());
            VBox root = attach(page);
            page.activate();
            root.applyCss();

            List<String> labels = labels(page);
            assertTrue(labels.contains("60 秒后退出（固定）"));
            assertTrue(labels.contains("已同步"));
            assertTrue(labels.contains("IDEA 调试未配置 launcher supervisor"));
            Button repair = button(root, "修复启动项");
            assertFalse(repair.isDisabled());
            repair.fire();
            assertTrue(labels(page).contains("登录启动项已按定时任务权威状态修复"));
        });
    }

    private static List<String> labels(Parent page) {
        Parent searchRoot = page.getScene() == null ? page : page.getScene().getRoot();
        return searchRoot.lookupAll(".label").stream()
                .filter(Label.class::isInstance)
                .map(Label.class::cast)
                .map(Label::getText)
                .toList();
    }

    private static VBox attach(ManagedSettingsPage page) {
        VBox root = new VBox(page.content());
        page.actionContent().ifPresent(root.getChildren()::add);
        new Scene(root, 900, 700);
        return root;
    }

    private static Button button(VBox page, String text) {
        return page.lookupAll(".button").stream()
                .filter(Button.class::isInstance)
                .map(Button.class::cast)
                .filter(value -> text.equals(value.getText()))
                .findFirst()
                .orElseThrow();
    }
}
