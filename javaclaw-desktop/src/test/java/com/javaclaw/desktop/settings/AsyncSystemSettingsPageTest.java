package com.javaclaw.desktop.settings;

import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import javafx.scene.Scene;
import javafx.scene.control.Label;
import org.junit.jupiter.api.Test;

import com.javaclaw.api.DiagnosticsSnapshot;
import com.javaclaw.desktop.FxTestSupport;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AsyncSystemSettingsPageTest {
    @Test
    void 后台诊断完成只在FxThread渲染() {
        CompletableFuture<DiagnosticsSnapshot> pending = new CompletableFuture<>();
        DiagnosticsSettingsPage[] page = new DiagnosticsSettingsPage[1];
        FxTestSupport.run(() -> {
            page[0] = new DiagnosticsSettingsPage(gateway(pending));
            new Scene(page[0], 900, 700);
            page[0].activate();
            assertTrue(labels(page[0]).contains("正在读取诊断"));
        });

        CompletableFuture.runAsync(() -> pending.complete(snapshot())).join();

        FxTestSupport.run(() -> {
            assertTrue(labels(page[0]).contains("构建与数据"));
            assertFalse(labels(page[0]).contains("正在读取诊断"));
        });
    }

    @Test
    void 新Epoch与Dispose都丢弃迟到诊断结果() {
        CompletableFuture<DiagnosticsSnapshot> old = new CompletableFuture<>();
        CompletableFuture<DiagnosticsSnapshot> current = CompletableFuture.completedFuture(snapshot());
        DiagnosticsSettingsPage[] page = new DiagnosticsSettingsPage[1];
        FxTestSupport.run(() -> {
            page[0] = new DiagnosticsSettingsPage(gateway(old, current));
            new Scene(page[0], 900, 700);
            page[0].activate();
            page[0].activate();
            assertTrue(labels(page[0]).contains("构建与数据"));
        });

        CompletableFuture.runAsync(() -> old.completeExceptionally(new IllegalStateException("迟到失败")))
                .join();
        FxTestSupport.run(() -> assertFalse(labels(page[0]).stream().anyMatch(value -> value.contains("迟到失败"))));

        CompletableFuture<DiagnosticsSnapshot> afterDispose = new CompletableFuture<>();
        FxTestSupport.run(() -> {
            page[0] = new DiagnosticsSettingsPage(gateway(afterDispose));
            new Scene(page[0], 900, 700);
            page[0].activate();
            page[0].dispose();
        });
        CompletableFuture.runAsync(() -> afterDispose.complete(snapshot())).join();
        FxTestSupport.run(() -> assertFalse(labels(page[0]).contains("构建与数据")));
    }

    @SafeVarargs
    private static CoreSettingsGateway gateway(CompletableFuture<DiagnosticsSnapshot>... responses) {
        Deque<CompletableFuture<DiagnosticsSnapshot>> pending = new ArrayDeque<>(List.of(responses));
        return (CoreSettingsGateway) Proxy.newProxyInstance(
                CoreSettingsGateway.class.getClassLoader(),
                new Class<?>[] {CoreSettingsGateway.class},
                (proxy, method, arguments) -> {
                    if ("diagnostics".equals(method.getName())) {
                        return pending.removeFirst();
                    }
                    throw new AssertionError("未预期的设置调用: " + method.getName());
                });
    }

    private static DiagnosticsSnapshot snapshot() {
        return new TestCoreSettingsGateway().diagnostics().toCompletableFuture().join();
    }

    private static List<String> labels(DiagnosticsSettingsPage page) {
        return page.lookupAll(".label").stream()
                .filter(Label.class::isInstance)
                .map(Label.class::cast)
                .map(Label::getText)
                .toList();
    }
}
