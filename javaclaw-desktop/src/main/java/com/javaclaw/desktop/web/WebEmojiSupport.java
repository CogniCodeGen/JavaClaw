package com.javaclaw.desktop.web;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import javafx.application.Platform;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.protocol.CanonicalJson;

/**
 * 将 macOS WebKit 的彩色字形请求交给后台系统字体绘制，不读取文件或调用服务端。
 *
 * <p>一个页面最多一个在途批次；关闭不等待字体线程，迟到结果不再回到已释放的 WebKit。 字形是可跨消息复用的展示资源，不能携带链接、权限或业务动作。
 */
final class WebEmojiSupport implements AutoCloseable {
    private final CanonicalJson json = new CanonicalJson();
    private final WebEmojiRasterizer rasterizer = new WebEmojiRasterizer();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(
            Thread.ofVirtual().name("web-emoji").factory());
    private volatile boolean closed;
    private boolean pending;

    static boolean supported() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("mac");
    }

    /** 调用和回执均在 FX 线程；只在后台处理有界的字素列表，异常恢复字体显示而不使聊天降级。 */
    void request(String payload, Consumer<String> reply) {
        if (closed) {
            return;
        }
        List<String> clusters;
        try {
            if (pending || payload.length() > 12_288) {
                reply.accept("{}");
                return;
            }
            clusters = json.decode(new CanonicalPayload(payload), Request.class).clusters();
            if (clusters == null || clusters.size() > 32) {
                reply.accept("{}");
                return;
            }
            clusters = List.copyOf(clusters);
        } catch (RuntimeException invalid) {
            reply.accept("{}");
            return;
        }
        pending = true;
        List<String> requested = clusters;
        worker.submit(() -> render(requested, reply));
    }

    private void render(List<String> clusters, Consumer<String> reply) {
        String response;
        try {
            response = json.encode(rasterizer.render(clusters)).json();
        } catch (RuntimeException unavailable) {
            response = json.encode(Map.of()).json();
        }
        String result = response;
        Platform.runLater(() -> {
            pending = false;
            if (!closed) {
                reply.accept(result);
            }
        });
    }

    @Override
    public void close() {
        closed = true;
        worker.shutdownNow();
    }

    /** @param clusters 完整字素簇，每批最多32个；具体字符与像素预算由绘制器再次限制 */
    private record Request(List<String> clusters) {}
}
