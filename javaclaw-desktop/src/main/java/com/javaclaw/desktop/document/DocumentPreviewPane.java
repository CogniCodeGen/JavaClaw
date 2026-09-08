package com.javaclaw.desktop.document;

import java.net.URI;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import com.javaclaw.api.DocumentPreview;
import com.javaclaw.api.DocumentReference;
import com.javaclaw.desktop.component.PlatformComponentFactory;
import com.javaclaw.desktop.component.PlatformComponentFactory.ActionSize;
import com.javaclaw.desktop.component.PlatformComponentFactory.ActionStyle;
import com.javaclaw.desktop.web.MarkdownLinkTarget;
import com.javaclaw.desktop.web.WebSurfaceHost;

/**
 * 只读文档面板；原生元信息和分页动作独立于 WebKit，正文失败时仍可阅读安全文本。
 *
 * <p>每次打开、连接切换和关闭推进 epoch；迟到的 resolve 必须关闭句柄。可见时每60秒续租，隐藏不续租， 过期或撤权后清除页面。后台只等待 SDK Future，不在 FX 线程读取、解码或解析文件。
 */
public final class DocumentPreviewPane extends VBox implements AutoCloseable {
    private final DocumentPreviewGateway gateway;
    private final Consumer<URI> external;
    private final ExecutorService worker =
            Executors.newSingleThreadExecutor(Thread.ofVirtual().factory());
    private final Label title = new Label("文档");
    private final Label metadata = new Label("选择对话中的文件或代码引用");
    private final TextArea plain = new TextArea();
    private final WebSurfaceHost surface;
    private final Button previous;
    private final Button next;
    private final Button reload;
    private final Timeline lease;
    private volatile long epoch;
    private volatile boolean closed;
    private Future<?> task;
    private DocumentPreview version;
    private DocumentReference reference;
    private DocumentPreviewRoute route;
    private DocumentPreviewLoader loader;
    private DocumentPreviewLoader.Content content;
    private boolean busy;
    private Renewal renewal;
    private long lastRenewed;

    /**
     * 创建可复用面板；调用者在 Workspace 或连接改变时调用 clear。
     *
     * @param gateway 唯一内容访问边界
     * @param external 外部 HTTP(S) 链接交系统浏览器的动作
     */
    public DocumentPreviewPane(DocumentPreviewGateway gateway, Consumer<URI> external) {
        this.gateway = gateway;
        this.external = external;
        PlatformComponentFactory components = new PlatformComponentFactory();
        title.getStyleClass().add("thinking-panel-title");
        metadata.getStyleClass().add("sec-hint");
        metadata.setWrapText(true);
        plain.setEditable(false);
        plain.setWrapText(false);
        plain.setAccessibleText("文档简版正文");
        surface = new WebSurfaceHost("document", plain, this::action);
        previous = components.action("上一页", ActionStyle.GHOST, ActionSize.COMPACT);
        next = components.action("下一页", ActionStyle.GHOST, ActionSize.COMPACT);
        reload = components.action("重新读取", ActionStyle.GHOST, ActionSize.COMPACT);
        Button simple = components.action("简版", ActionStyle.GHOST, ActionSize.COMPACT);
        Button close = components.action("关闭", ActionStyle.GHOST, ActionSize.COMPACT);
        previous.setOnAction(event -> page(false));
        next.setOnAction(event -> page(true));
        reload.setOnAction(event -> openRoute(route));
        simple.setOnAction(event -> surface.useFallback());
        close.setOnAction(event -> clear());
        FlowPane actions = new FlowPane(4, 4, previous, next, reload, simple, close);
        // 窄侧栏按原控件样式换行，不能把操作名称压成省略号。
        actions.getChildren().forEach(node -> ((Button) node).setMinWidth(Region.USE_PREF_SIZE));
        getChildren().addAll(title, metadata, actions, surface);
        VBox.setVgrow(surface, Priority.ALWAYS);
        setSpacing(8);
        getStyleClass().add("platform-page");
        buttons();
        lease = new Timeline(new KeyFrame(Duration.seconds(5), event -> renewVisible()));
        lease.setCycleCount(Timeline.INDEFINITE);
        lease.play();
    }

    /** @param source 已由聊天投影确定来源的类型化引用；不接收任意宿主路径 */
    public void open(DocumentReference source) {
        if (source == null || closed) {
            return;
        }
        openRoute(new DocumentPreviewRoute(source, java.util.List.of()));
    }

    private void openRoute(DocumentPreviewRoute target) {
        if (target == null || closed) {
            return;
        }
        clear();
        route = target;
        reference = target.source();
        busy = true;
        metadata.setText("正在读取引用版本…");
        buttons();
        long expected = epoch;
        target.resolve(gateway).whenComplete((value, failure) -> {
            if (value != null && (closed || expected != epoch)) {
                gateway.close(value.handleId());
                return;
            }
            // 完成回调直接安排所有权移交；不能把句柄关闭责任放在可能尚未启动就被取消的 worker 中。
            Platform.runLater(() -> {
                if (value == null) {
                    failed(expected, failure == null ? new IllegalStateException("引用没有返回有效版本") : failure);
                    return;
                }
                DocumentPreviewLoader reader = new DocumentPreviewLoader(gateway, value);
                if (acceptVersion(expected, value, reader)) {
                    loadFirst(expected, reader);
                }
            });
        });
    }

    private void loadFirst(long expected, DocumentPreviewLoader reader) {
        task = worker.submit(() -> {
            try {
                var loaded = reader.first();
                Platform.runLater(() -> acceptContent(expected, loaded));
            } catch (Exception failure) {
                Platform.runLater(() -> failed(expected, failure));
            }
        });
    }

    private boolean acceptVersion(long expected, DocumentPreview value, DocumentPreviewLoader reader) {
        if (expected != epoch || closed) {
            // resolve 回调到 FX 接收之间仍可能切换页面；close 幂等，不能只依赖 Future 完成时的检查。
            gateway.close(value.handleId());
            return false;
        }
        version = value;
        loader = reader;
        renewal = null;
        lastRenewed = System.nanoTime();
        title.setText(value.fileName());
        metadata.setText((value.origin() == DocumentPreview.Origin.CURRENT_FILE ? "当前文件" : "引用版本") + " · "
                + value.sizeBytes() + " 字节" + (value.changed() ? " · 内容已变化" : ""));
        return true;
    }

    private void acceptContent(long expected, DocumentPreviewLoader.Content value) {
        if (expected != epoch || closed || version == null) {
            return;
        }
        content = value;
        busy = false;
        plain.setText(value.plain());
        surface.show(version.handleId(), value.json());
        buttons();
    }

    private void page(boolean forward) {
        if (busy || loader == null || content == null) {
            return;
        }
        busy = true;
        buttons();
        long expected = epoch;
        DocumentPreviewLoader reader = loader;
        int target = Math.max(0, content.page() - 1);
        task = worker.submit(() -> {
            try {
                var value = forward ? reader.next() : reader.previous(target);
                Platform.runLater(() -> acceptContent(expected, value));
            } catch (Exception failure) {
                Platform.runLater(() -> failed(expected, failure));
            }
        });
    }

    private void action(String action, String value) {
        if (!action.equals("link") || content == null || version == null || !value.startsWith("doc:")) {
            return;
        }
        try {
            String href = content.links().get(Integer.parseInt(value.substring(4)));
            URI uri = MarkdownLinkTarget.parse(href);
            if ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme())) {
                external.accept(uri);
            } else if (DocumentPreviewLoader.relative(href)) {
                openResource(href);
            }
        } catch (IllegalArgumentException | IndexOutOfBoundsException ignored) {
            metadata.setText("引用不可用");
        }
    }

    private void openResource(String href) {
        DocumentPreviewRoute target = route.append(href);
        String parent = version.handleId();
        long expected = ++epoch;
        // 相对导航转移版本所有权；旧续租不能阻止子版本续租，也不能把父版本重新写回。
        renewal = null;
        if (task != null) {
            task.cancel(true);
            task = null;
        }
        busy = true;
        buttons();
        var resolved = gateway.resource(parent, href);
        resolved.whenComplete((value, failure) -> Platform.runLater(() -> {
            if (value == null) {
                failed(expected, failure);
            } else if (expected != epoch || closed) {
                gateway.close(value.handleId());
            } else {
                gateway.close(parent);
                route = target;
                content = null;
                DocumentPreviewLoader reader = new DocumentPreviewLoader(gateway, value);
                acceptVersion(expected, value, reader);
                task = worker.submit(() -> {
                    try {
                        var loaded = reader.first();
                        Platform.runLater(() -> acceptContent(expected, loaded));
                    } catch (Exception problem) {
                        Platform.runLater(() -> failed(expected, problem));
                    }
                });
            }
        }));
    }

    private void renewVisible() {
        if (version == null
                || renewal != null
                || busy
                || closed
                || !visibleNow()
                || System.nanoTime() - lastRenewed < 60_000_000_000L) {
            return;
        }
        Renewal request = new Renewal(epoch, version.handleId());
        renewal = request;
        gateway.renew(request.handle())
                .whenComplete((value, failure) -> Platform.runLater(() -> acceptRenewal(request, value, failure)));
    }

    private void acceptRenewal(Renewal request, DocumentPreview value, Throwable failure) {
        if (renewal != request
                || request.epoch() != epoch
                || closed
                || version == null
                || !request.handle().equals(version.handleId())) {
            return;
        }
        renewal = null;
        if (failure != null) {
            failed(request.epoch(), failure);
        } else {
            version = value;
            lastRenewed = System.nanoTime();
        }
    }

    private boolean visibleNow() {
        if (getScene() == null
                || getScene().getWindow() == null
                || !getScene().getWindow().isShowing()) {
            return false;
        }
        for (Node node = this; node != null; node = node.getParent()) {
            if (!node.isVisible()) {
                return false;
            }
        }
        return true;
    }

    private void failed(long expected, Throwable failure) {
        if (expected == epoch && !closed) {
            DocumentReference retry = reference;
            DocumentPreviewRoute retryRoute = route;
            DocumentPreview verified = version;
            clear();
            reference = retry;
            route = retryRoute;
            String detail = "预览不可用：" + failureDetail(failure);
            if (verified != null) {
                // 只保留服务端已解析的名称与大小；正文、租约和解码器仍由 clear 完整释放。
                title.setText(verified.fileName());
                metadata.setText(verified.sizeBytes() + " 字节 · " + detail);
            } else {
                metadata.setText(detail);
            }
            buttons();
        }
    }

    private static String failureDetail(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getMessage() == null ? "读取失败，可重新读取引用" : cause.getMessage();
    }

    private void buttons() {
        previous.setDisable(busy || content == null || content.page() == 0);
        next.setDisable(busy || content == null || content.complete());
        reload.setDisable(busy || reference == null);
    }

    /**
     * @param handle 服务端撤回的精确句柄
     * @param reason 稳定失效原因；只清除相同版本，不影响随后打开的文档
     */
    public void invalidate(String handle, String reason) {
        if (version == null || !version.handleId().equals(handle)) {
            return;
        }
        DocumentReference retry = reference;
        DocumentPreviewRoute retryRoute = route;
        String name = title.getText();
        clear();
        reference = retry;
        route = retryRoute;
        title.setText(name);
        metadata.setText(
                switch (reason) {
                    case "EXPIRED" -> "预览版本已过期，请重新读取";
                    case "REVOKED" -> "读取权限已变化，预览已关闭";
                    case "CORRUPT" -> "文档校验失败，预览已关闭";
                    default -> "预览版本已关闭";
                });
        buttons();
    }

    /** 释放当前版本、正文与后台任务；连接或 Workspace 变化时调用，迟到结果不能重现旧文件。 */
    public void clear() {
        epoch++;
        if (task != null) {
            task.cancel(true);
            task = null;
        }
        if (version != null) {
            gateway.close(version.handleId());
        }
        version = null;
        reference = null;
        route = null;
        loader = null;
        content = null;
        busy = false;
        renewal = null;
        title.setText("文档");
        metadata.setText("选择对话中的文件或代码引用");
        plain.clear();
        // 撤权必须拆掉旧纹理；空 DOM 的 ACK 可能早于 WebKit 清帧，不能复显原 WebView。
        surface.suspend();
        surface.show("empty:" + epoch, "{}");
        surface.resume();
        buttons();
    }

    /** 关闭计时器、页面和后台执行器；幂等释放租约。 */
    @Override
    public void close() {
        clear();
        closed = true;
        lease.stop();
        surface.close();
        worker.shutdownNow();
    }

    /**
     * 单次续租身份；对象身份区分同一版本的不同请求。
     *
     * @param epoch 发起时的页面代次
     * @param handle 发起时的版本句柄，不可空；与 epoch 共同限定结果所有权
     */
    private record Renewal(long epoch, String handle) {}
}
