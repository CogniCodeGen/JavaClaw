package com.javaclaw.desktop.web;

import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.concurrent.Worker;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.input.ContextMenuEvent;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.web.WebView;
import javafx.util.Duration;
import netscape.javascript.JSObject;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.desktop.component.PlatformComponentFactory;
import com.javaclaw.desktop.component.PlatformComponentFactory.ActionSize;
import com.javaclaw.desktop.component.PlatformComponentFactory.ActionStyle;
import com.javaclaw.protocol.CanonicalJson;

/**
 * 平台拥有的单页面宿主；所有方法在 FX 线程调用，页面只接收可重建的展示快照。
 *
 * <p>加载成功不等于可见：强引用桥完成 ready 后才提交快照，ack 必须匹配代次、上下文和版本。隐藏暂停超时； 一次自动重建失败后使用同一 Java 状态的原生降级，恢复绝不重执行业务。
 */
public final class WebSurfaceHost extends StackPane implements AutoCloseable {
    private static final long TIMEOUT_NANOS = 5_000_000_000L;
    private final CanonicalJson json = new CanonicalJson();
    private final String kind;
    private final Node fallback;
    private final BiConsumer<String, String> actions;
    private final WebThemeProbe theme = new WebThemeProbe();
    private final Label status = new Label("正在准备显示…");
    private final VBox feedback = new VBox(8);
    private final ContextMenu recoveryMenu = new ContextMenu();
    private final Timeline pulse;
    private WebView web;
    private WebSurfaceBridge bridge;
    private String context = "empty";
    private String payload = "{}";
    // 仅保存同一上下文的有界阅读锚点；不包含正文、SDK 句柄或可执行代码。
    private String viewState = "";
    private long revision;
    private long applied = -1;
    private long submitted = -1;
    private long generation;
    private long pendingSince;
    private long lastPulse;
    private int recoveries;
    private boolean ready;
    private boolean closed;
    private boolean simplified;
    private boolean suspended;
    private boolean pending = true;
    private Map<String, String> lastTheme = Map.of();

    /**
     * 创建按需加载的宿主；未挂载或零尺寸时不会初始化 WebKit。
     *
     * @param kind 固定应用模板 chat、document 或 graph
     * @param fallback 不访问服务端的原生展示
     * @param actions 经宿主验证后交给平台 Presenter 的白名单动作
     */
    public WebSurfaceHost(String kind, Node fallback, BiConsumer<String, String> actions) {
        if (!java.util.Set.of("chat", "document", "graph").contains(kind)) {
            throw new IllegalArgumentException("未知页面类型");
        }
        this.kind = kind;
        this.fallback = Objects.requireNonNull(fallback, "fallback");
        this.actions = Objects.requireNonNull(actions, "actions");
        getStyleClass().add("web-surface-host");
        PlatformComponentFactory components = new PlatformComponentFactory();
        Button retry = components.action("重试显示", ActionStyle.GHOST, ActionSize.COMPACT);
        Button simple = components.action("简版显示", ActionStyle.GHOST, ActionSize.COMPACT);
        retry.setOnAction(event -> retry());
        simple.setOnAction(event -> useFallback());
        status.getStyleClass().add("sec-hint");
        feedback.getStyleClass().add("platform-feedback");
        feedback.getChildren().addAll(status, retry, simple);
        getChildren().addAll(fallback, feedback, theme);
        fallback.setVisible(false);
        installRecoveryMenu();
        pulse = new Timeline(new KeyFrame(Duration.millis(50), event -> tick()));
        pulse.setCycleCount(Timeline.INDEFINITE);
        pulse.play();
    }

    private void installRecoveryMenu() {
        MenuItem simple = new MenuItem("简版显示");
        MenuItem retry = new MenuItem("重试显示");
        simple.setOnAction(event -> {
            recoveryMenu.hide();
            useFallback();
        });
        retry.setOnAction(event -> {
            recoveryMenu.hide();
            retry();
        });
        recoveryMenu.getItems().addAll(simple, retry);
        // 健康页面也可能有局部字形问题；原生入口不依赖网页桥或失败提示，且不改变页面布局。
        addEventFilter(ContextMenuEvent.CONTEXT_MENU_REQUESTED, event -> {
            if (!closed && !suspended && actuallyVisible()) {
                simple.setDisable(simplified);
                recoveryMenu.show(this, event.getScreenX(), event.getScreenY());
                event.consume();
            }
        });
    }

    /**
     * 合并最新展示；允许跳过中间绘制，不允许调用方跳过业务事件归并。
     *
     * @param identity 当前会话、文档或图谱身份
     * @param serialized 已在后台序列化的 JSON 对象
     */
    public void show(String identity, String serialized) {
        requireFx();
        if (closed) {
            return;
        }
        String checked = Objects.requireNonNull(identity, "identity");
        String data = Objects.requireNonNull(serialized, "serialized");
        if (context.equals(checked) && payload.equals(data)) {
            return;
        }
        if (!context.equals(checked)) {
            viewState = "";
            if (web != null) {
                web.setVisible(false);
            }
            fallback.setVisible(simplified);
            feedback.setVisible(!simplified);
            pendingSince = 0;
            applied = -1;
        }
        context = checked;
        payload = data;
        revision++;
        pending = true;
    }

    /** 切换为同一 Java 展示状态的原生简版，不重新发送消息或业务命令。 */
    public void useFallback() {
        requireFx();
        simplified = true;
        dropWeb();
        fallback.setVisible(true);
        feedback.setVisible(false);
    }

    /** 显式重试页面，保留当前数据和上下文；重新允许一次自动恢复。 */
    public void retry() {
        requireFx();
        if (!closed) {
            simplified = false;
            recoveries = 0;
            dropWeb();
            feedback.setVisible(true);
            status.setText("正在恢复显示…");
        }
    }

    /** 离开页面时停止计时并释放 WebKit 与桥；保留当前 Java 展示快照和用户简版选择。 */
    public void suspend() {
        requireFx();
        if (!closed) {
            recoveryMenu.hide();
            suspended = true;
            pulse.stop();
            dropWeb();
            feedback.setVisible(!simplified);
            status.setText("正在恢复显示…");
        }
    }

    /** 返回相同页面作用域时允许按需重建；恢复只重新渲染现有 Java 状态。 */
    public void resume() {
        requireFx();
        if (!closed && suspended) {
            suspended = false;
            lastPulse = 0;
            pulse.play();
        }
    }

    /** @return 页面是否已确认当前展示版本；这不证明操作系统已经合成像素 */
    public boolean acknowledged() {
        return ready && applied == revision && !simplified;
    }

    /** @return 当前宿主代次，用于诊断和生命周期验收 */
    public long generation() {
        return generation;
    }

    private void tick() {
        long now = System.nanoTime();
        long elapsed = lastPulse == 0 ? 0 : now - lastPulse;
        lastPulse = now;
        if (closed || simplified) {
            return;
        }
        if (!actuallyVisible()) {
            if (pendingSince != 0) {
                pendingSince += elapsed;
            }
            return;
        }
        tickVisible(now);
    }

    private void tickVisible(long now) {
        if (web == null) {
            createWeb(now);
        } else if (pendingSince != 0 && now - pendingSince > TIMEOUT_NANOS) {
            fail("页面未及时确认显示");
        } else if (ready) {
            try {
                applyTheme();
                if (pending && pendingSince == 0) {
                    applySnapshot(now);
                }
            } catch (RuntimeException failure) {
                fail("页面主题无法显示");
            }
        }
    }

    private boolean actuallyVisible() {
        if (getScene() == null
                || getScene().getWindow() == null
                || !getScene().getWindow().isShowing()
                || getWidth() <= 0
                || getHeight() <= 0) {
            return false;
        }
        for (Node node = this; node != null; node = node.getParent()) {
            if (!node.isVisible()) {
                return false;
            }
        }
        return true;
    }

    private void createWeb(long now) {
        try {
            web = new WebView();
            // 必须在首次 load 前绑定进程目录；默认 java/webview 会被其他 JavaFX 进程独占。
            web.getEngine().setUserDataDirectory(WebSurfaceRuntime.directory().toFile());
            web.setContextMenuEnabled(false);
            web.setPageFill(theme.background());
            web.setVisible(false);
            getChildren().addFirst(web);
            generation++;
            pendingSince = now;
            long expected = generation;
            web.getEngine().setCreatePopupHandler(features -> null);
            web.getEngine().setConfirmHandler(message -> false);
            web.getEngine().setPromptHandler(data -> null);
            web.getEngine().setOnError(event -> fail("页面显示失败"));
            web.getEngine().getLoadWorker().stateProperty().addListener((observable, previous, current) -> {
                if (expected == generation && !closed) {
                    loaded(current);
                }
            });
            web.getEngine().loadContent(WebSurfaceResources.page(kind));
        } catch (java.io.IOException | RuntimeException failure) {
            fail("页面资源无法加载");
        }
    }

    private void loaded(Worker.State state) {
        if (state == Worker.State.SUCCEEDED) {
            try {
                bridge = new WebSurfaceBridge(this::receive);
                ((JSObject) web.getEngine().executeScript("window")).setMember("javaClawBridge", bridge);
                web.getEngine().executeScript("window.JavaClawSurface.bootstrap(" + generation + ")");
            } catch (RuntimeException failure) {
                fail("页面初始化失败");
            }
        } else if (state == Worker.State.FAILED || state == Worker.State.CANCELLED) {
            fail("页面加载失败");
        }
    }

    private void receive(String message) {
        try {
            Message incoming = json.decode(new CanonicalPayload(message), Message.class);
            if (!currentGeneration(incoming)) {
                return;
            }
            if (incoming.action().equals("ready")) {
                ready = true;
                pendingSince = 0;
                pending = true;
                return;
            }
            if (!context.equals(incoming.context())) {
                return;
            }
            if (acceptViewState(incoming)) {
                return;
            }
            if (incoming.action().equals("ack") && incoming.revision() == submitted) {
                applied = submitted;
                pendingSince = 0;
                web.setVisible(true);
                fallback.setVisible(false);
                feedback.setVisible(false);
            } else if (actuallyVisible()
                    && incoming.revision() == revision
                    && java.util.Set.of("link", "preview", "copy", "history", "following", "select")
                            .contains(incoming.action())) {
                actions.accept(incoming.action(), incoming.value());
            } else if (incoming.action().equals("error")) {
                fail("页面内容无法显示");
            }
        } catch (RuntimeException invalid) {
            fail("页面响应无效");
        }
    }

    private boolean currentGeneration(Message incoming) {
        return !closed && incoming.generation() == generation;
    }

    private boolean acceptViewState(Message incoming) {
        if (incoming.action().equals("viewState")
                && kind.equals("chat")
                && incoming.revision() == revision
                && incoming.value().length() <= 2048) {
            viewState = incoming.value();
            return true;
        }
        return false;
    }

    private void applySnapshot(long now) {
        pending = false;
        pendingSince = now;
        submitted = revision;
        try {
            String identity = "(" + json.encode(Map.of("value", context)).json() + ").value";
            String restore = "(" + json.encode(Map.of("value", viewState)).json() + ").value";
            web.getEngine()
                    .executeScript("window.JavaClawSurface.apply(" + identity + ',' + revision + ',' + payload + ','
                            + restore + ')');
        } catch (RuntimeException failure) {
            fail("页面内容无法显示");
        }
    }

    private void applyTheme() {
        Map<String, String> values = theme.values();
        if (!values.equals(lastTheme)) {
            lastTheme = values;
            web.setPageFill(theme.background());
            web.getEngine()
                    .executeScript("window.JavaClawSurface.theme("
                            + json.encode(values).json() + ')');
        }
    }

    private void fail(String detail) {
        if (closed || simplified) {
            return;
        }
        status.setText(detail);
        if (recoveries++ == 0) {
            dropWeb();
            feedback.setVisible(true);
        } else {
            useFallback();
        }
    }

    private void dropWeb() {
        generation++;
        ready = false;
        pending = true;
        pendingSince = 0;
        applied = -1;
        bridge = null;
        lastTheme = Map.of();
        WebView previous = web;
        web = null;
        if (previous != null) {
            previous.getEngine().setOnError(null);
            previous.getEngine().load(null);
            getChildren().remove(previous);
        }
    }

    private static void requireFx() {
        if (!Platform.isFxApplicationThread()) {
            throw new IllegalStateException("页面宿主必须在 FX 线程操作");
        }
    }

    /** 取消宿主计时器并释放所有页面资源；幂等且不依赖不存在的 WebEngine dispose。 */
    @Override
    public void close() {
        requireFx();
        closed = true;
        recoveryMenu.hide();
        pulse.stop();
        dropWeb();
    }

    /**
     * @param action 固定动作
     * @param generation 宿主代次
     * @param context 上下文
     * @param revision 绘制版本
     * @param value 动作参数
     */
    public record Message(String action, long generation, String context, long revision, String value) {}
}
