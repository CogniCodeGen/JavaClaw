package com.javaclaw.desktop.settings;

import java.util.List;

import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.Test;

import com.javaclaw.desktop.FxTestSupport;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SystemSettingsPagesTest {
    @Test
    void 诊断页渲染全部脱敏子系统且刷新保留可用操作() {
        FxTestSupport.run(() -> {
            DiagnosticsSettingsPage page = new DiagnosticsSettingsPage(new TestCoreSettingsGateway());
            Scene scene = new Scene(page, 900, 700);
            page.activate();
            page.applyCss();

            List<String> labels = labels(page);
            assertTrue(labels.contains("构建与数据"));
            assertTrue(labels.contains("模型与 Vault"));
            assertTrue(labels.contains("连接与隔离 Worker"));
            assertTrue(labels.contains("发行生命周期"));
            Button refresh = button(page, "刷新");
            refresh.fire();
            assertFalse(button(page, "复制摘要").isDisabled());
            assertFalse(button(page, "导出 JSON").isDisabled());
            assertTrue(scene.getRoot() == page);
        });
    }

    @Test
    void 生命周期页展示固定退出策略并按Schedule权威状态修复() {
        FxTestSupport.run(() -> {
            LifecycleSettingsPage page = new LifecycleSettingsPage(new TestCoreSettingsGateway());
            new Scene(page, 900, 700);
            page.activate();
            page.applyCss();

            List<String> labels = labels(page);
            assertTrue(labels.contains("60 秒后退出（固定）"));
            assertTrue(labels.contains("已同步"));
            assertTrue(labels.contains("IDEA 调试未配置 launcher supervisor"));
            Button repair = button(page, "修复启动项");
            assertFalse(repair.isDisabled());
            repair.fire();
            assertTrue(labels(page).contains("生命周期状态已刷新") || labels(page).contains("登录启动项已按 Schedule 权威状态修复"));
        });
    }

    private static List<String> labels(VBox page) {
        return page.lookupAll(".label").stream()
                .filter(Label.class::isInstance)
                .map(Label.class::cast)
                .map(Label::getText)
                .toList();
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
