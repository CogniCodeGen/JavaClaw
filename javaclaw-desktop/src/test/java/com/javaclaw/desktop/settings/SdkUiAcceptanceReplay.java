package com.javaclaw.desktop.settings;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Labeled;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.web.WebView;
import javafx.stage.Stage;
import javafx.stage.Window;

import com.javaclaw.api.TurnStatus;
import com.javaclaw.desktop.FxTestSupport;
import com.javaclaw.desktop.acceptance.chatdocument.AcceptanceCapture;
import com.javaclaw.desktop.appearance.AppearancePreferences;
import com.javaclaw.desktop.appearance.AppearanceTheme;
import com.javaclaw.desktop.appearance.FontScale;
import com.javaclaw.desktop.appearance.InterfaceDensity;
import com.javaclaw.desktop.web.WebSurfaceHost;
import com.javaclaw.extension.spi.ExtensionExecutionReceipt;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.ExtensionRpcContracts;

/** 在真实 SDK 会话上重放生产控件事件；截图只作验收证据，不写入 Golden 基线。 */
final class SdkUiAcceptanceReplay {
    private SdkUiAcceptanceReplay() {}

    static void run(SdkUiAcceptanceDesktop desktop, Path output) throws Exception {
        System.setProperty("javaclaw.acceptance.output", output.toString());
        await(() -> desktop.state.threads().workspaces().size() == 2, "SDK 工作区列表");
        FxTestSupport.run(() -> desktop.presenter.selectWorkspace(desktop.state.threads().workspaces().stream()
                .filter(value -> value.name().equals("界面功能验收"))
                .findFirst()
                .orElseThrow()));
        await(() -> !desktop.state.threads().threads().isEmpty(), "SDK 对话目录");
        FxTestSupport.run(() -> desktop.presenter.selectThread(desktop.state.threads().threads().stream()
                .filter(value -> value.title().equals("记忆事实：办公地点上海"))
                .findFirst()
                .orElseThrow()));
        await(() -> !desktop.state.transcript().history().isEmpty(), "SDK 历史消息");
        ready(desktop.stage.getScene());
        String latest = new CanonicalJson()
                .encode(Map.of(
                        "id",
                        desktop.state.transcript().history().getLast().id().toString()))
                .json();
        await(
                () -> FxTestSupport.call(() -> Boolean.TRUE.equals(web(desktop.stage.getScene())
                        .getEngine()
                        .executeScript(
                                "Array.from(document.querySelectorAll('article')).some(article => article.dataset.id === ("
                                        + latest + ").id)"))),
                "权威历史消息实际绘制");
        save(desktop.stage.getScene(), "sdk-chat-history", output);
        SdkUiReviewAcceptance.history(desktop, output);
        sendAndPreview(desktop, output);
        pages(desktop, output);
        System.out.println("SDK_UI_ACCEPTANCE_PASSED");
    }

    private static void sendAndPreview(SdkUiAcceptanceDesktop desktop, Path output) throws Exception {
        sendMessage(desktop.stage.getScene());
        await(() -> desktop.state.transcript().stream().isPresent(), "App Server 增量通知");
        ready(desktop.stage.getScene());
        save(desktop.stage.getScene(), "sdk-chat-stream", output);
        SdkUiReviewAcceptance.returnWhileRunning(desktop, output);
        await(
                () -> desktop.state.transcript().stream()
                        .flatMap(value -> value.terminal())
                        .map(status -> status == TurnStatus.COMPLETED)
                        .orElse(false),
                "流式 Turn 完成");
        await(
                () -> desktop.state.threads().activeTurn().isEmpty()
                        && !desktop.state.interaction().busy(),
                "Turn 终态投影与输入恢复");
        SdkUiReviewAcceptance.singleSubmission(desktop);
        await(
                () -> FxTestSupport.call(() -> web(desktop.stage.getScene())
                        .getEngine()
                        .executeScript("Array.from(document.querySelectorAll('a[data-link]')).some(link => "
                                + "link.textContent.includes('验收文档') && !link.dataset.link.startsWith('active:'))")
                        .equals(Boolean.TRUE)),
                "完整正文与引用");
        ready(desktop.stage.getScene());
        save(desktop.stage.getScene(), "sdk-chat-completed", output);
        FxTestSupport.run(() -> web(desktop.stage.getScene()).getEngine().executeScript("""
                Array.from(document.querySelectorAll('a')).find(link => link.textContent.includes('验收文档')).click()
                """));
        await(() -> FxTestSupport.call(() -> texts(desktop.stage.getScene()).contains("guide.md")), "文档引用打开");
        await(
                () -> FxTestSupport.call(
                        () -> descendants(desktop.stage.getScene().getRoot()).stream()
                                .filter(WebView.class::isInstance)
                                .map(WebView.class::cast)
                                .anyMatch(
                                        value -> Boolean.TRUE.equals(
                                                value.getEngine()
                                                        .executeScript(
                                                                "document.body.textContent.includes('文档展示验收') && "
                                                                        + "Array.from(document.images).some(image => image.complete && image.naturalWidth > 0)")))),
                "文档正文与相对图片实际加载");
        ready(desktop.stage.getScene());
        save(desktop.stage.getScene(), "sdk-document-reference", output);
    }

    private static void sendMessage(Scene scene) throws Exception {
        FxTestSupport.run(() -> ((TextArea) scene.lookup("#composer")).setText("请展示代码与验收文档"));
        // 历史绘制与配置预览独立完成；同一 FX 事件中检查并点击，避免对禁用按钮空操作或重复提交。
        await(
                () -> FxTestSupport.call(() -> {
                    Button send = (Button) scene.lookup("#sendButton");
                    if (send.isDisabled() || !send.isVisible()) {
                        return false;
                    }
                    send.fire();
                    return true;
                }),
                "配置预览就绪后提交一次消息");
    }

    private static void pages(SdkUiAcceptanceDesktop desktop, Path output) throws Exception {
        for (var mode : List.of("standard", "dark-minimum")) {
            FxTestSupport.run(() -> {
                boolean dark = mode.startsWith("dark");
                desktop.appearance.preview(new AppearancePreferences(
                        dark ? AppearanceTheme.MIDNIGHT : AppearanceTheme.EMERALD,
                        dark ? FontScale.EXTRA_LARGE : FontScale.STANDARD,
                        dark ? InterfaceDensity.COMPACT : InterfaceDensity.STANDARD));
            });
            for (String view : List.of("graph", "conflicts", "background-learning")) {
                openPage(desktop, "memory", "javaclaw.memory." + view);
                Scene scene = centerScene();
                resize(scene, mode);
                ready(scene);
                save(scene, "sdk-memory-" + view.replace('.', '-') + "-" + mode, output);
                detail(scene, view, mode, output);
                if (view.equals("background-learning")) {
                    SdkUiReviewAcceptance.learningReopened(desktop, mode, output);
                }
            }
            openPage(desktop, "schedule", "javaclaw.schedule.managed");
            resize(centerScene(), mode);
            ready(centerScene());
            save(centerScene(), "sdk-schedule-" + mode, output);
            FxTestSupport.run(() -> desktop.center.show(desktop.stage, "jobs"));
            jobsLoaded();
            save(centerScene(), "sdk-jobs-" + mode, output);
        }
    }

    private static void jobsLoaded() throws Exception {
        await(
                () -> FxTestSupport.call(() -> centerScene().lookup("#automationJobList") instanceof ListView<?> list
                        && list.getItems().size() >= 2),
                "后台任务列表完成加载");
        FxTestSupport.run(() -> {
            var list = (ListView<?>) centerScene().lookup("#automationJobList");
            for (int index = 0; index < list.getItems().size(); index++) {
                if (list.getItems().get(index) instanceof ExtensionExecutionReceipt job
                        && job.jobType().equals("conversation-learning")) {
                    list.getSelectionModel().select(index);
                    return;
                }
            }
            throw new AssertionError("后台任务列表应包含真实学习作业");
        });
        await(
                () -> FxTestSupport.call(() -> texts(centerScene()).contains("已完成")
                        && texts(centerScene()).contains("后台任务已创建")
                        && !texts(centerScene()).contains("正在读取…")),
                "学习作业终态和原生详情");
    }

    private static void detail(Scene scene, String view, String mode, Path output) throws Exception {
        FxTestSupport.run(() -> {
            Node target = descendants(scene.getRoot()).stream()
                    .filter(node -> {
                        if (view.equals("graph")) {
                            return node instanceof WebSurfaceHost;
                        }
                        return node instanceof Labeled label
                                && label.getText() != null
                                && label.getText().startsWith(view.equals("conflicts") ? "人工决议" : "批次审计");
                    })
                    .findFirst()
                    .orElseThrow();
            scrollTo(scene, target);
        });
        ready(scene);
        if (view.equals("graph")) {
            FxTestSupport.run(() -> {
                Object count = web(scene)
                        .getEngine()
                        .executeScript("document.getElementById('graph')._cyreg.cy.nodes().length");
                if (!(count instanceof Number number) || number.intValue() != 1) {
                    throw new AssertionError("真实图谱应包含一个已确认记忆节点");
                }
                web(scene)
                        .getEngine()
                        .executeScript("document.getElementById('graph')._cyreg.cy.nodes().first().emit('tap')");
            });
        }
        save(scene, "sdk-memory-" + view + "-detail-" + mode, output);
    }

    static void scrollTo(Scene scene, Node target) {
        for (Node node : descendants(scene.getRoot())) {
            if (node instanceof ScrollPane scroll
                    && scroll.getContent() != null
                    && descendants(scroll.getContent()).contains(target)) {
                var content = scroll.getContent();
                var bounds = content.sceneToLocal(target.localToScene(target.getBoundsInLocal()));
                double range = content.getLayoutBounds().getHeight()
                        - scroll.getViewportBounds().getHeight();
                scroll.setVvalue(range > 0 ? Math.max(0, Math.min(1, (bounds.getMinY() - 12) / range)) : 0);
            }
        }
    }

    static void openPage(SdkUiAcceptanceDesktop desktop, String page, String view) throws Exception {
        FxTestSupport.run(() -> desktop.center.show(desktop.stage, page));
        await(() -> FxTestSupport.call(() -> findDocumentChoice(centerScene(), view) != null), "页面目录 " + view);
        FxTestSupport.run(() -> {
            ComboBox<?> choice = findDocumentChoice(centerScene(), view);
            for (int index = 0; index < choice.getItems().size(); index++) {
                if (choice.getItems().get(index) instanceof ExtensionRpcContracts.ViewDocument document
                        && document.viewId().equals(view)) {
                    choice.getSelectionModel().select(index);
                    return;
                }
            }
        });
        await(() -> FxTestSupport.call(() -> !texts(centerScene()).contains("正在读取")), "页面数据 " + view);
    }

    private static ComboBox<?> findDocumentChoice(Scene scene, String view) {
        return descendants(scene.getRoot()).stream()
                .filter(ComboBox.class::isInstance)
                .map(value -> (ComboBox<?>) value)
                .filter(choice -> choice.getItems().stream()
                        .anyMatch(item -> item instanceof ExtensionRpcContracts.ViewDocument document
                                && document.viewId().equals(view)))
                .findFirst()
                .orElse(null);
    }

    private static void resize(Scene scene, String mode) {
        FxTestSupport.run(() -> {
            Stage window = (Stage) scene.getWindow();
            window.setWidth(mode.startsWith("dark") ? 880 : 1040);
            window.setHeight(mode.startsWith("dark") ? 620 : 740);
        });
    }

    static void ready(Scene scene) throws Exception {
        await(
                () -> FxTestSupport.call(() -> descendants(scene.getRoot()).stream()
                        .filter(WebSurfaceHost.class::isInstance)
                        .map(WebSurfaceHost.class::cast)
                        .filter(Node::isVisible)
                        .allMatch(WebSurfaceHost::acknowledged)),
                "实际 WebKit ACK");
    }

    static WebView web(Scene scene) {
        return descendants(scene.getRoot()).stream()
                .filter(WebView.class::isInstance)
                .map(WebView.class::cast)
                .filter(Node::isVisible)
                .findFirst()
                .orElseThrow();
    }

    static Scene centerScene() {
        return Window.getWindows().stream()
                .filter(Stage.class::isInstance)
                .map(Stage.class::cast)
                .filter(stage -> stage.getTitle().equals("JavaClaw 设置与管理中心"))
                .findFirst()
                .orElseThrow()
                .getScene();
    }

    static void save(Scene scene, String name, Path output) throws Exception {
        AcceptanceCapture.save(
                scene,
                name,
                Map.of("boundary", "Production FXML → SDK → UDS → App Server → H2", "model", "固定模型，无外部请求"));
        Files.writeString(output.resolve(name + ".txt"), FxTestSupport.call(() -> texts(scene)));
        System.out.println("SDK_UI_CAPTURE " + name);
    }

    private static String texts(Scene scene) {
        return descendants(scene.getRoot()).stream()
                .filter(Labeled.class::isInstance)
                .map(Labeled.class::cast)
                .map(Labeled::getText)
                .reduce("", (before, value) -> before + "\n" + value);
    }

    static List<Node> descendants(Node root) {
        List<Node> result = new ArrayList<>();
        if (root.isVisible()) {
            result.add(root);
            if (root instanceof Parent parent) {
                parent.getChildrenUnmodifiable().forEach(child -> result.addAll(descendants(child)));
            }
        }
        return result;
    }

    static void await(BooleanSupplier ready, String label) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(35).toNanos();
        while (!ready.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("验收未到达：" + label);
            }
            Thread.sleep(25);
        }
    }
}
