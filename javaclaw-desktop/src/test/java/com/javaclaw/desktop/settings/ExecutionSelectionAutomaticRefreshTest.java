package com.javaclaw.desktop.settings;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import javafx.scene.Scene;
import javafx.scene.control.ComboBox;
import org.junit.jupiter.api.Test;

import com.javaclaw.api.ExecutionConfiguration;
import com.javaclaw.api.ExecutionOverrides;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.api.ReasoningPreference;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.desktop.DesktopConfigurationChange;
import com.javaclaw.desktop.DesktopTestFixtures;
import com.javaclaw.desktop.FxTestSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutionSelectionAutomaticRefreshTest {
    @Test
    void 隐藏面板仅累计失效并在下次显示时读取最新配置() {
        FxTestSupport.run(() -> {
            ExecutionSelectionTestGateway gateway = new ExecutionSelectionTestGateway();
            put(gateway, ReasoningPreference.LOW, 1);
            try (ExecutionSelectionPanel panel = panel(gateway)) {
                panel.setRefreshActive(false);
                ExecutionConfiguration latest = put(gateway, ReasoningPreference.HIGH, 4);
                publish(gateway, DesktopConfigurationChange.Kind.EXECUTION);
                publish(gateway, DesktopConfigurationChange.Kind.PROVIDERS);
                assertEquals(1, gateway.catalogReads);

                panel.setRefreshActive(true);

                assertEquals(2, gateway.catalogReads);
                assertEquals(latest.overrides(), panel.execution());
            }
        });
    }

    @Test
    void 模型失效只更新脏草稿目录而保留精确模型引用和配置版本() {
        FxTestSupport.run(() -> {
            ExecutionSelectionTestGateway gateway = new ExecutionSelectionTestGateway();
            put(gateway, ReasoningPreference.LOW, 1);
            try (ExecutionSelectionPanel panel = panel(gateway)) {
                ExecutionSelectionControl control =
                        (ExecutionSelectionControl) panel.getChildren().getFirst();
                ProviderRef original = new ProviderRef("provider-main", 1, "fake-model");
                control.setValue(new ExecutionOverrides(
                        Optional.empty(),
                        Optional.of(original),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of(ReasoningPreference.HIGH)));
                gateway.providerRevision = 2;
                put(gateway, ReasoningPreference.MEDIUM, 7);
                int configurationReads = gateway.executionReads;

                publish(gateway, DesktopConfigurationChange.Kind.PROVIDERS);

                assertEquals(2, gateway.catalogReads);
                assertEquals(configurationReads, gateway.executionReads, "脏表单刷新不能重读并推进配置基线");
                assertEquals(original, panel.execution().provider().orElseThrow());
                assertTrue(models(panel).getItems().stream().allMatch(value -> value.endpointRevision() == 2));
                panel.save();
                assertEquals(1, gateway.writes.getFirst().expectedRevision());
                assertTrue(panel.dirty());
                assertFalse(panel.canSave());
                publish(gateway, DesktopConfigurationChange.Kind.PROVIDERS);
                assertFalse(panel.canSave(), "目录更新不能解除保存冲突");
                assertEquals(original, panel.execution().provider().orElseThrow());
            }
        });
    }

    @Test
    void 失效后的明确丢弃采用最新执行基线() {
        FxTestSupport.run(() -> {
            ExecutionSelectionTestGateway gateway = new ExecutionSelectionTestGateway();
            put(gateway, ReasoningPreference.LOW, 1);
            try (ExecutionSelectionPanel panel = panel(gateway)) {
                reasoning(panel).setValue(ReasoningPreference.HIGH);
                put(gateway, ReasoningPreference.MEDIUM, 5);
                publish(gateway, DesktopConfigurationChange.Kind.EXECUTION);
                assertEquals(
                        ReasoningPreference.HIGH, panel.execution().reasoning().orElseThrow());

                panel.discard();

                assertEquals(
                        ReasoningPreference.MEDIUM,
                        panel.execution().reasoning().orElseThrow());
                reasoning(panel).setValue(ReasoningPreference.HIGH);
                panel.save();
                assertEquals(5, gateway.writes.getFirst().expectedRevision());
            }
        });
    }

    @Test
    void 在途读取失效合并补读并拒绝旧快照覆盖() {
        FxTestSupport.run(() -> {
            ExecutionSelectionTestGateway gateway = new ExecutionSelectionTestGateway();
            put(gateway, ReasoningPreference.LOW, 1);
            try (ExecutionSelectionPanel panel = panel(gateway)) {
                CompletableFuture<Optional<ExecutionConfiguration>> delayed = new CompletableFuture<>();
                gateway.workspaceReads.add(delayed);
                panel.refreshAutomatically();
                publish(gateway, DesktopConfigurationChange.Kind.PROVIDERS);
                publish(gateway, DesktopConfigurationChange.Kind.EXECUTION);
                assertEquals(2, gateway.catalogReads);
                ExecutionConfiguration updated = put(gateway, ReasoningPreference.HIGH, 4);

                delayed.complete(Optional.empty());

                assertEquals(3, gateway.catalogReads);
                assertEquals(updated.overrides(), panel.execution());
                assertFalse(panel.pending());
            }
        });
    }

    @Test
    void 写入期间失效等待权威成功回执后刷新() {
        FxTestSupport.run(() -> {
            ExecutionSelectionTestGateway gateway = new ExecutionSelectionTestGateway();
            put(gateway, ReasoningPreference.LOW, 1);
            try (ExecutionSelectionPanel panel = panel(gateway)) {
                reasoning(panel).setValue(ReasoningPreference.HIGH);
                CompletableFuture<ExecutionConfiguration> writing = new CompletableFuture<>();
                gateway.writeResponse = writing;
                panel.save();
                publish(gateway, DesktopConfigurationChange.Kind.EXECUTION);
                publish(gateway, DesktopConfigurationChange.Kind.PROVIDERS);
                assertEquals(1, gateway.catalogReads);
                ExecutionConfiguration saved = put(gateway, ReasoningPreference.HIGH, 2);

                writing.complete(saved);

                assertEquals(2, gateway.catalogReads);
                assertEquals(saved.overrides(), panel.execution());
                assertFalse(panel.dirty());
                assertFalse(panel.pending());
            }
        });
    }

    @Test
    void 其他作用域不触发刷新且关闭后取消订阅并废弃迟到读取() {
        FxTestSupport.run(() -> {
            ExecutionSelectionTestGateway gateway = new ExecutionSelectionTestGateway();
            ExecutionConfiguration original = put(gateway, ReasoningPreference.LOW, 1);
            ExecutionSelectionPanel panel = panel(gateway);
            gateway.configurationEvents.publish(new DesktopConfigurationChange(
                    DesktopConfigurationChange.Kind.EXECUTION, Optional.of(WorkspaceId.random()), Optional.empty()));
            gateway.configurationEvents.publish(new DesktopConfigurationChange(
                    DesktopConfigurationChange.Kind.EXECUTION,
                    Optional.of(gateway.workspace.id()),
                    Optional.of(DesktopTestFixtures.thread().id())));
            assertEquals(1, gateway.catalogReads);
            CompletableFuture<Optional<ExecutionConfiguration>> delayed = new CompletableFuture<>();
            gateway.workspaceReads.add(delayed);
            panel.refreshAutomatically();
            ExecutionConfiguration updated = put(gateway, ReasoningPreference.HIGH, 9);

            panel.close();
            delayed.complete(Optional.of(updated));
            publish(gateway, DesktopConfigurationChange.Kind.PROVIDERS);

            assertEquals(2, gateway.catalogReads);
            assertEquals(original.overrides(), panel.execution());
        });
    }

    private static ExecutionSelectionPanel panel(ExecutionSelectionTestGateway gateway) {
        ExecutionSelectionPanel panel = new ExecutionSelectionPanel(gateway);
        new Scene(panel);
        panel.bind(Optional.of(gateway.workspace), Optional.empty(), true);
        return panel;
    }

    private static ExecutionConfiguration put(
            ExecutionSelectionTestGateway gateway, ReasoningPreference reasoning, long revision) {
        ExecutionOverrides value = new ExecutionOverrides(
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(reasoning));
        ExecutionConfiguration configuration =
                ExecutionSelectionTestGateway.configuration(Optional.of(gateway.workspace.id()), value, revision);
        gateway.defaults.put(Optional.of(gateway.workspace.id()), configuration);
        return configuration;
    }

    private static void publish(ExecutionSelectionTestGateway gateway, DesktopConfigurationChange.Kind kind) {
        gateway.configurationEvents.publish(new DesktopConfigurationChange(kind, Optional.empty(), Optional.empty()));
    }

    @SuppressWarnings("unchecked")
    private static ComboBox<ReasoningPreference> reasoning(ExecutionSelectionPanel panel) {
        return (ComboBox<ReasoningPreference>) panel.lookup("#executionReasoning");
    }

    @SuppressWarnings("unchecked")
    private static ComboBox<ProviderRef> models(ExecutionSelectionPanel panel) {
        return (ComboBox<ProviderRef>) panel.lookup("#executionModel");
    }
}
