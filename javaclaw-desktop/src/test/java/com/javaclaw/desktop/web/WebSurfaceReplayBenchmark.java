package com.javaclaw.desktop.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.image.WritableImage;
import javafx.scene.web.WebView;
import javafx.stage.Stage;

import com.javaclaw.api.DocumentReference;
import com.javaclaw.api.ItemHistoryEntry;
import com.javaclaw.api.ItemId;
import com.javaclaw.api.MessageRole;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.desktop.DesktopStylesheets;
import com.javaclaw.desktop.FxTestSupport;
import com.javaclaw.desktop.view.ChatSurface;
import com.javaclaw.protocol.CanonicalJson;

/**
 * 无付费模型的本机 WebKit 重放入口；必须在真实桌面运行，不能作为跨平台性能结论。
 *
 * <p>接收点是 ChatSurface.show；每个可见样本同时校验 DOM 标记、宿主确认与非空 JavaFX snapshot。 这不是 OS 合成器的光学显示测量，也不覆盖服务端网络延迟。10,000 条夹具只按 100
 * 条首屏、500 条缓存输入渲染器。
 */
public final class WebSurfaceReplayBenchmark {
    private static final int UPDATES = 120;
    private final WorkspaceId workspace = WorkspaceId.random();
    private final List<ItemHistoryEntry> window =
            fixtures(workspace).stream().skip(9500).toList();
    private final Map<Integer, Long> receipts = new ConcurrentHashMap<>();
    private final List<Double> visibleMillis = new ArrayList<>();
    private final List<Double> fxMillis = new ArrayList<>();
    private final Map<String, Object> report = new LinkedHashMap<>();
    private ChatSurface chat;
    private Stage stage;
    private WebView web;

    private WebSurfaceReplayBenchmark() {}

    /**
     * @param arguments 一个输出目录；写入 JSON 测量及最后一帧 PNG，不连接服务端或模型
     * @throws Exception 桌面不可用、渲染失败或输出不可写
     */
    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 1) {
            throw new IllegalArgumentException("提供一个 benchmark 输出目录");
        }
        var benchmark = new WebSurfaceReplayBenchmark();
        try {
            benchmark.run(Path.of(arguments[0]));
        } finally {
            FxTestSupport.run(() -> {
                if (benchmark.chat != null) {
                    benchmark.chat.close();
                    benchmark.stage.close();
                }
            });
            Platform.exit();
        }
    }

    private void run(Path directory) throws Exception {
        Files.createDirectories(directory);
        report.put("recordedAt", Instant.now().toString());
        report.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version"));
        report.put("arch", System.getProperty("os.arch"));
        report.put("java", System.getProperty("java.version"));
        report.put("javafx", "26.0.2");
        report.put("historyFixtureCount", 10_000);
        report.put("firstPageCount", 100);
        report.put("cacheWindowCount", window.size());
        long opened = System.nanoTime();
        FxTestSupport.run(this::open);
        FxTestSupport.await(() -> FxTestSupport.call(() -> chat.node().acknowledged() && contains("历史消息 9999")));
        WritableImage first = awaitPaint(directory);
        report.put("firstSnapshotMs", millis(opened));
        save(first, directory.resolve("first.png"));
        FxTestSupport.run(() -> chat.show("replay", workspace, List.of(), window, List.of(), true));
        FxTestSupport.await(() -> FxTestSupport.call(() -> chat.node().acknowledged()));
        scroll(directory);
        long cpu = cpuNanos();
        long started = System.nanoTime();
        stream();
        report.put("streamWallMs", millis(started));
        report.put("processCpuOneCorePercent", (cpuNanos() - cpu) * 100.0 / (System.nanoTime() - started));
        report.put("emittedUpdates", UPDATES);
        report.put("visibleSamples", visibleMillis.size());
        report.put("receiveToSnapshotP50Ms", percentile(visibleMillis, 0.50));
        report.put("receiveToSnapshotP95Ms", percentile(visibleMillis, 0.95));
        report.put("fxQueueRoundTripP95Ms", percentile(fxMillis, 0.95));
        report.put(
                "heapUsedBytes",
                Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory());
        report.put("processRssKiB", rss());
        report.put("mountedArticles", FxTestSupport.call(() -> articles()));
        report.put(
                "limitations",
                List.of(
                        "本机真实 Stage/WebKit，不代表五平台",
                        "入口为展示层，不含 SDK/服务器延迟",
                        "snapshot 佐证渲染，不是 OS 屏幕光学可见时刻",
                        "未与旧 JavaFX UI 对照；RSS 包含 WebKit/JVM/夹具"));
        save(FxTestSupport.call(() -> web.snapshot(null, null)), directory.resolve("stream.png"));
        Files.writeString(
                directory.resolve("results.json"),
                new CanonicalJson().encode(report).json());
        System.out.println(new CanonicalJson().encode(report).json());
        if (percentile(visibleMillis, 0.95) > 250) {
            throw new AssertionError("接收至实际 snapshot 的 P95 超过 250 ms 目标，指标和帧已保留供复核");
        }
    }

    private WritableImage awaitPaint(Path directory) throws IOException, InterruptedException {
        long deadline = System.nanoTime() + 10_000_000_000L;
        WritableImage frame = null;
        while (System.nanoTime() < deadline) {
            frame = FxTestSupport.call(() -> web.snapshot(null, null));
            if (hasPixels(frame) && FxTestSupport.call(() -> markerVisible("历史消息 9999"))) {
                return frame;
            }
            Thread.sleep(10);
        }
        save(frame, directory.resolve("failed-first.png"));
        Files.writeString(
                directory.resolve("failed-first-dom.txt"),
                FxTestSupport.call(() -> web.getEngine()
                        .executeScript("JSON.stringify({y:scrollY,height:innerHeight,body:document.body.innerHTML})")
                        .toString()));
        throw new AssertionError("首帧未在期限内同时出现视口内正文与非纯色 snapshot");
    }

    private void open() {
        chat = new ChatSurface(new Label("简版"), ignored -> {}, ignored -> {}, () -> {}, ignored -> {});
        Scene scene = new Scene(chat.node(), 900, 650);
        DesktopStylesheets.apply(scene);
        stage = new Stage();
        stage.setScene(scene);
        stage.setTitle("JavaClaw WebView replay — no model");
        stage.show();
        chat.show("replay", workspace, List.of(), window.subList(400, 500), List.of(), true);
    }

    private void scroll(Path directory) throws IOException, InterruptedException {
        List<Double> samples = new ArrayList<>();
        for (int index = 0; index < 12; index++) {
            long started = System.nanoTime();
            int position = index % 2;
            FxTestSupport.run(() -> web.getEngine()
                    .executeScript("window.scrollTo(0,"
                            + (position == 0 ? "0" : "document.documentElement.scrollHeight") + ")"));
            awaitViewport(position == 0 ? "历史消息 9500" : "历史消息 9999", directory);
            samples.add(millis(started));
        }
        FxTestSupport.run(() -> {
            web.getEngine().executeScript("window.scrollTo(0,document.documentElement.scrollHeight)");
        });
        awaitViewport("历史消息 9999", directory);
        report.put("scrollCommandToSnapshotP95Ms", percentile(samples, 0.95));
    }

    private void stream() throws InterruptedException {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread producer = Thread.ofVirtual().start(() -> {
            try {
                StringBuilder text = new StringBuilder("## 持续输出\n\n");
                for (int index = 0; index < UPDATES; index++) {
                    text.append("这是一段用于重放的 Markdown 文本。\n\nBENCH_MARKER_")
                            .append(index)
                            .append('\n');
                    receipts.put(index, System.nanoTime());
                    chat.show(
                            "replay",
                            workspace,
                            List.of(),
                            window,
                            List.of(new ChatSurface.TemporaryMessage("streaming", text.toString(), false)),
                            true);
                    Thread.sleep(50);
                }
            } catch (Throwable problem) {
                failure.set(problem);
            }
        });
        int previous = -1;
        long deadline = System.nanoTime() + 20_000_000_000L;
        while ((producer.isAlive() || previous < UPDATES - 1) && System.nanoTime() < deadline) {
            long queued = System.nanoTime();
            int observed = FxTestSupport.call(this::visibleVersion);
            fxMillis.add(millis(queued));
            if (observed > previous && receipts.containsKey(observed)) {
                visibleMillis.add(millis(receipts.get(observed)));
                previous = observed;
            }
            Thread.sleep(5);
        }
        producer.join();
        if (failure.get() != null || previous != UPDATES - 1) {
            throw new AssertionError("流式最后一帧未完成真实渲染", failure.get());
        }
    }

    private int visibleVersion() {
        Object value = web.getEngine()
                .executeScript("(function(){var n=document.querySelector('[data-id=streaming]');if(!n)return '';"
                        + "var box=n.getBoundingClientRect();if(box.top>=innerHeight||box.bottom<=0)return '';"
                        + "var walker=document.createTreeWalker(n,NodeFilter.SHOW_TEXT),node,last,index;"
                        + "while(node=walker.nextNode()){var i=node.textContent.lastIndexOf('BENCH_MARKER_');"
                        + "if(i>=0){last=node;index=i;}}if(!last)return '';"
                        + "var range=document.createRange();range.setStart(last,index);"
                        + "range.setEnd(last,last.textContent.length);var r=range.getBoundingClientRect();"
                        + "return r.bottom>0&&r.top<innerHeight&&r.right>0&&r.left<innerWidth"
                        + "?last.textContent.substring(index):'';})()");
        String text = value.toString();
        int marker = text.lastIndexOf("BENCH_MARKER_");
        if (marker < 0) {
            return -1;
        }
        if (!hasPixels(web.snapshot(null, null))) {
            return -1;
        }
        return Integer.parseInt(text.substring(marker + 13).strip());
    }

    private void awaitViewport(String marker, Path directory) throws IOException, InterruptedException {
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (FxTestSupport.call(() -> markerVisible(marker) && hasPixels(web.snapshot(null, null)))) {
                return;
            }
            Thread.sleep(10);
        }
        save(FxTestSupport.call(() -> web.snapshot(null, null)), directory.resolve("failed-scroll.png"));
        throw new AssertionError("滚动未在期限内呈现目标正文：" + marker);
    }

    private boolean markerVisible(String marker) {
        return Boolean.TRUE.equals(web.getEngine()
                .executeScript(
                        "(function(){var walker=document.createTreeWalker(document.body,NodeFilter.SHOW_TEXT),node;"
                                + "while(node=walker.nextNode()){var i=node.textContent.indexOf('" + marker + "');"
                                + "if(i<0)continue;var r=document.createRange();r.setStart(node,i);r.setEnd(node,i+"
                                + marker.length() + ");var b=r.getBoundingClientRect();"
                                + "if(b.bottom>0&&b.top<innerHeight)return true;}return false;})()"));
    }

    private boolean contains(String marker) {
        web = chat.node().getChildren().stream()
                .filter(WebView.class::isInstance)
                .map(WebView.class::cast)
                .findFirst()
                .orElseThrow();
        return Boolean.TRUE.equals(
                web.getEngine().executeScript("document.body.textContent.indexOf('" + marker + "')>=0"));
    }

    private int articles() {
        int count = ((Number) web.getEngine().executeScript("document.querySelectorAll('article').length")).intValue();
        if (count > 128) {
            throw new AssertionError("DOM 超出 128 条窗口");
        }
        return count;
    }

    private static List<ItemHistoryEntry> fixtures(WorkspaceId workspace) {
        TurnId turn = TurnId.random();
        return IntStream.range(0, 10_000)
                .mapToObj(index -> {
                    ItemId id = ItemId.random();
                    return new ItemHistoryEntry(
                            id,
                            turn,
                            index + 1,
                            "message",
                            Optional.of(MessageRole.ASSISTANT),
                            "### 历史消息 " + index + "\n\n正文 **Markdown**、中文、emoji 🙂。\n\n```java\nint value = " + index
                                    + ";\n```",
                            Optional.of(DocumentReference.message(workspace, id, "body")),
                            false,
                            Instant.EPOCH,
                            List.of(),
                            List.of());
                })
                .toList();
    }

    private static boolean hasPixels(WritableImage image) {
        int first = image.getPixelReader().getArgb(10, 10);
        for (int y = 10; y < image.getHeight(); y += 13) {
            for (int x = 10; x < image.getWidth(); x += 13) {
                if (image.getPixelReader().getArgb(x, y) != first) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void save(WritableImage image, Path file) throws IOException {
        var output = new java.awt.image.BufferedImage(
                (int) image.getWidth(), (int) image.getHeight(), java.awt.image.BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < output.getHeight(); y++) {
            for (int x = 0; x < output.getWidth(); x++) {
                output.setRGB(x, y, image.getPixelReader().getArgb(x, y));
            }
        }
        javax.imageio.ImageIO.write(output, "png", file.toFile());
    }

    private static double millis(long start) {
        return (System.nanoTime() - start) / 1_000_000.0;
    }

    private static double percentile(List<Double> values, double fraction) {
        var sorted = values.stream().sorted().toList();
        return sorted.get(Math.min(sorted.size() - 1, (int) Math.ceil(sorted.size() * fraction) - 1));
    }

    private static long cpuNanos() {
        return ProcessHandle.current().info().totalCpuDuration().orElseThrow().toNanos();
    }

    private static long rss() {
        try {
            Process command = new ProcessBuilder(
                            "ps",
                            "-o",
                            "rss=",
                            "-p",
                            Long.toString(ProcessHandle.current().pid()))
                    .start();
            String output = new String(command.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            return command.waitFor() == 0 ? Long.parseLong(output) : -1;
        } catch (IOException | InterruptedException | NumberFormatException unavailable) {
            if (unavailable instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return -1;
        }
    }
}
