package com.javaclaw.desktop.settings;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.TableView;

import com.javaclaw.api.ConversationThread;
import com.javaclaw.api.MessageRole;
import com.javaclaw.desktop.FxTestSupport;
import com.javaclaw.desktop.appearance.AppearancePreferences;
import com.javaclaw.desktop.appearance.AppearanceTheme;
import com.javaclaw.desktop.appearance.FontScale;
import com.javaclaw.desktop.appearance.InterfaceDensity;
import com.javaclaw.extension.spi.ViewOption;

import static com.javaclaw.desktop.settings.SdkUiAcceptanceReplay.await;
import static com.javaclaw.desktop.settings.SdkUiAcceptanceReplay.centerScene;
import static com.javaclaw.desktop.settings.SdkUiAcceptanceReplay.descendants;
import static com.javaclaw.desktop.settings.SdkUiAcceptanceReplay.openPage;
import static com.javaclaw.desktop.settings.SdkUiAcceptanceReplay.ready;
import static com.javaclaw.desktop.settings.SdkUiAcceptanceReplay.save;
import static com.javaclaw.desktop.settings.SdkUiAcceptanceReplay.scrollTo;
import static com.javaclaw.desktop.settings.SdkUiAcceptanceReplay.web;

/** R1/R2/R6 的真实 SDK 与生产控件回归；所有数据由隔离验收服务提供。 */
final class SdkUiReviewAcceptance {
    private static final String PROMPT = "请展示代码与验收文档";

    private SdkUiReviewAcceptance() {}

    static void history(SdkUiAcceptanceDesktop desktop, Path output) throws Exception {
        var original = desktop.state.threads().selectedThread().orElseThrow();
        select(desktop, thread(desktop, "历史执行记录验收"));
        for (String mode : List.of("standard", "dark-minimum")) {
            appearance(desktop, mode);
            await(
                    () -> FxTestSupport.call(() -> Boolean.TRUE.equals(
                            web(desktop.stage.getScene()).getEngine().executeScript("""
                            (() => {
                                const rows = Array.from(document.querySelectorAll('article'));
                                return rows.some(row => row.classList.contains('transcript-error-block') &&
                                    row.textContent.includes('UI_ACCEPTANCE_DENIED') && row.textContent.includes('历史错误说明')) &&
                                    rows.some(row => row.classList.contains('transcript-execution-block') &&
                                    row.textContent.includes('工具结果 · 成功') && row.textContent.includes('fixture-success')) &&
                                    rows.some(row => row.classList.contains('transcript-execution-block') &&
                                    row.textContent.includes('失败或未完成') && row.textContent.includes('fixture-failure'));
                            })()
                            """))),
                    "持久错误与工具成功/失败摘要及原有样式");
            ready(desktop.stage.getScene());
            save(desktop.stage.getScene(), "sdk-history-execution-facts-" + mode, output);
        }
        select(desktop, original);
        appearance(desktop, "standard");
    }

    static void returnWhileRunning(SdkUiAcceptanceDesktop desktop, Path output) throws Exception {
        var original = desktop.state.threads().selectedThread().orElseThrow();
        var turn = desktop.state.threads().activeTurn().orElseThrow().id();
        var other = thread(desktop, "历史执行记录验收");
        for (String mode : List.of("standard", "dark-minimum")) {
            appearance(desktop, mode);
            select(desktop, other);
            await(
                    () -> desktop.state.threads().activeTurn().isEmpty()
                            && !desktop.state.interaction().busy(),
                    "其他会话不继承原会话生成状态");
            select(desktop, original);
            await(
                    () -> desktop.state
                                    .threads()
                                    .activeTurn()
                                    .map(value -> value.id().equals(turn))
                                    .orElse(false)
                            && desktop.state.interaction().busy()
                            && desktop.state.transcript().stream().isPresent(),
                    "返回后恢复同一活动 Turn 和生成状态");
            if (!FxTestSupport.call(() -> ((Button) desktop.stage.getScene().lookup("#sendButton")).isDisabled())) {
                throw new AssertionError("返回正在生成的会话时必须禁止重复发送");
            }
            ready(desktop.stage.getScene());
            save(desktop.stage.getScene(), "sdk-chat-return-active-" + mode, output);
        }
        appearance(desktop, "standard");
    }

    static void singleSubmission(SdkUiAcceptanceDesktop desktop) {
        long messages = desktop.state.transcript().history().stream()
                .filter(value ->
                        value.role().filter(role -> role == MessageRole.USER).isPresent())
                .filter(value -> value.summary().equals(PROMPT))
                .count();
        if (messages != 1) {
            throw new AssertionError("导航恢复不应重复启动 Turn，当前请求的持久用户消息数：" + messages);
        }
    }

    static void learningReopened(SdkUiAcceptanceDesktop desktop, String mode, Path output) throws Exception {
        assertLearning();
        openPage(desktop, "schedule", "javaclaw.schedule.managed");
        openPage(desktop, "memory", "javaclaw.memory.background-learning");
        assertLearning();
        Scene scene = centerScene();
        FxTestSupport.run(() -> scrollTo(
                scene,
                descendants(scene.getRoot()).stream()
                        .filter(node -> node instanceof ComboBox<?> choice && "审批要求".equals(choice.getAccessibleText()))
                        .findFirst()
                        .orElseThrow()));
        ready(scene);
        save(scene, "sdk-learning-reopened-exact-selection-" + mode, output);
    }

    private static void assertLearning() throws Exception {
        await(
                () -> FxTestSupport.call(() -> {
                    Scene scene = centerScene();
                    return choice(scene, "审批要求", "EVERY_CALL")
                            && choice(scene, "推理强度", "HIGH")
                            && selected(scene, "role", "memory-agent", 1)
                            && selected(scene, "provider", "memory-provider/memory-model", 1)
                            && selected(scene, "permissionProfile", "ui-preview-read", 2);
                }),
                "学习配置精确引用与 EVERY_CALL/HIGH 回显");
    }

    private static boolean choice(Scene scene, String label, String expected) {
        return descendants(scene.getRoot()).stream()
                .anyMatch(node -> node instanceof ComboBox<?> choice
                        && label.equals(choice.getAccessibleText())
                        && choice.getValue() instanceof ViewOption option
                        && option.value().equals(expected));
    }

    private static boolean selected(Scene scene, String field, String id, long revision) {
        return descendants(scene.getRoot()).stream()
                .filter(TableView.class::isInstance)
                .map(TableView.class::cast)
                .map(table -> table.getSelectionModel().getSelectedItem())
                .anyMatch(value -> value instanceof Map<?, ?> row
                        && row.containsKey(field)
                        && id.equals(row.get("id"))
                        && row.get("revision") instanceof Number version
                        && version.longValue() == revision);
    }

    private static ConversationThread thread(SdkUiAcceptanceDesktop desktop, String title) {
        return desktop.state.threads().threads().stream()
                .filter(value -> value.title().equals(title))
                .findFirst()
                .orElseThrow();
    }

    private static void select(SdkUiAcceptanceDesktop desktop, ConversationThread thread) throws Exception {
        FxTestSupport.run(() -> desktop.presenter.selectThread(thread));
        await(
                () -> desktop.state
                                .threads()
                                .selectedThread()
                                .map(value -> value.id().equals(thread.id()))
                                .orElse(false)
                        && !desktop.state.transcript().history().isEmpty(),
                "切换后历史加载 " + thread.title());
        ready(desktop.stage.getScene());
    }

    private static void appearance(SdkUiAcceptanceDesktop desktop, String mode) {
        FxTestSupport.run(() -> {
            boolean dark = mode.startsWith("dark");
            desktop.appearance.preview(new AppearancePreferences(
                    dark ? AppearanceTheme.MIDNIGHT : AppearanceTheme.EMERALD,
                    dark ? FontScale.EXTRA_LARGE : FontScale.STANDARD,
                    dark ? InterfaceDensity.COMPACT : InterfaceDensity.STANDARD));
        });
    }
}
