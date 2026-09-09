package com.javaclaw.desktop;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import javafx.application.Platform;
import javafx.scene.image.WritableImage;

import com.javaclaw.protocol.CanonicalJson;

/**
 * 真实主聊天 Scene 的低干扰回放；输入经 JavaFX 事件进入控件，滚动经 WebKit 原生事件路径。
 *
 * <p>测量阶段不截图。指标为事件排队至布局完成、活动 Pulse 间隔和进程 CPU，不等同光学可见延迟； 合成服务不访问用户数据、不调用模型。按相同模块启动方式对比相同夹具，不能外推到其他机器。
 */
public final class DesktopChatPerformanceReplay {
    private static final int INPUTS = 30;
    private static final String MARKER = "REPLAY_FINAL_MARKER";

    private DesktopChatPerformanceReplay() {}

    /**
     * @param arguments 输出目录，可选第二参数为单个场景名称
     * @throws Exception 窗口、模块、回放或输出不可用
     */
    public static void main(String[] arguments) throws Exception {
        if (arguments.length < 1 || arguments.length > 2) {
            throw new IllegalArgumentException("需要输出目录，可选指定场景名称");
        }
        Path directory = Path.of(arguments[0]);
        Files.createDirectories(directory);
        List<Map<String, Object>> results = new ArrayList<>();
        try {
            for (Scenario scenario : scenarios()) {
                if (arguments.length == 1 || arguments[1].equals(scenario.name())) {
                    Map<String, Object> result = measure(scenario, directory);
                    results.add(result);
                    String json = new CanonicalJson().encode(result).json();
                    Files.writeString(directory.resolve(scenario.name() + ".json"), json);
                    System.out.println(json);
                }
            }
            if (results.isEmpty()) {
                throw new IllegalArgumentException("未知性能场景");
            }
            Files.writeString(
                    directory.resolve("results.json"),
                    new CanonicalJson().encode(Map.of("scenarios", results)).json());
        } finally {
            Platform.exit();
        }
    }

    private static List<Scenario> scenarios() {
        String paragraph = "保持原有风格，验证中文输入、段落排版和滚动响应。".repeat(8) + "\n\n";
        String markdown = "## 示例段落\n\n" + paragraph.repeat(4) + "- 列表内容\n- 另一条内容\n";
        String code = "```java\n" + "System.out.println(\"中文代码\");\n".repeat(45) + "```\n\n";
        return List.of(
                new Scenario("short-idle", 2, 0, paragraph.repeat(3)),
                new Scenario("markdown-32-20hz", 32, 20, markdown),
                new Scenario("history-500-20hz", 500, 20, paragraph),
                new Scenario("code-32-60hz", 32, 60, code + code),
                new Scenario("history-500-60hz", 500, 60, paragraph),
                new Scenario("history-500-traverse", 500, 0, paragraph));
    }

    private static Map<String, Object> measure(Scenario scenario, Path directory) throws Exception {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("scenario", scenario.name());
        result.put("recordedAt", Instant.now().toString());
        result.put("java", System.getProperty("java.version"));
        result.put("os", System.getProperty("os.name") + " " + System.getProperty("os.arch"));
        result.put("namedModule", DesktopChatPerformanceReplay.class.getModule().isNamed());
        result.put("messages", scenario.count());
        result.put("streamHz", scenario.hertz());
        try (DesktopChatReplayFixture shell =
                new DesktopChatReplayFixture(scenario.count(), scenario.body(), scenario.hertz() > 0)) {
            long opened = System.nanoTime();
            shell.open();
            result.put("openToReadyMs", elapsed(opened));
            idle(result);
            active(shell, scenario, result);
            // 仅在测量结束后留一帧，避免截图缓冲和像素回读污染 CPU/分配指标。
            save(FxTestSupport.call(() -> shell.scene().snapshot(null)), directory.resolve(scenario.name() + ".png"));
        }
        result.put("measurement", "JavaFX事件入队至布局完成；Pulse非光学帧；合成SDK，无模型调用，测量期间不截图");
        return result;
    }

    private static void idle(Map<String, Object> result) throws InterruptedException {
        Thread.sleep(3000);
        result.put("idleStartedAt", Instant.now().toString());
        long cpu = cpu();
        long started = System.nanoTime();
        Thread.sleep(2500);
        result.put("idleCpuOneCorePercent", (cpu() - cpu) * 100.0 / (System.nanoTime() - started));
        result.put("idleEndedAt", Instant.now().toString());
    }

    private static void active(DesktopChatReplayFixture shell, Scenario scenario, Map<String, Object> result)
            throws InterruptedException {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        DesktopReplayMetrics metrics = FxTestSupport.call(() -> new DesktopReplayMetrics(shell.scene()));
        FxTestSupport.run(() -> shell.web().getEngine().executeScript("""
                window.replayWindows = new Set();
                new MutationObserver(() => {
                    var first = document.querySelector('article');
                    if (first) replayWindows.add(first.dataset.id);
                }).observe(document.getElementById('surface'), {childList:true});
                """));
        long cpu = cpu();
        long started = System.nanoTime();
        result.put("inputStartedAt", Instant.now().toString());
        Thread stream = Thread.ofVirtual().start(() -> produce(shell, scenario.hertz(), failure));
        for (int index = 0; index < INPUTS; index++) {
            long queued = System.nanoTime();
            FxTestSupport.run(() -> metrics.type(shell.composer(), queued));
            FxTestSupport.await(() -> FxTestSupport.call(metrics::inputCompleted));
            Thread.sleep(25);
        }
        result.put("inputEndedAt", Instant.now().toString());
        result.put("scrollStartedAt", Instant.now().toString());
        for (int index = 0; index < 90; index++) {
            double distance = scenario.name().endsWith("traverse") ? 720 : 90;
            double direction = index < 60 ? distance : -distance;
            FxTestSupport.run(() -> DesktopReplayMetrics.wheel(shell.web(), direction));
            Thread.sleep(16);
        }
        result.put("scrollEndedAt", Instant.now().toString());
        stream.join();
        result.put("activeCpuOneCorePercent", (cpu() - cpu) * 100.0 / (System.nanoTime() - started));
        FxTestSupport.run(() -> collect(shell, metrics, result));
        double budget = scenario.hertz() == 0 ? 50 : 100;
        result.put("inputBudgetMs", budget);
        result.put("inputWithinBudget", ((Number) result.get("inputQueueToLayoutP95Ms")).doubleValue() <= budget);
        if (scenario.name().endsWith("traverse") && ((Number) result.get("visitedWindows")).intValue() < 2) {
            throw new AssertionError("滚动回放未跨越虚拟窗口");
        }
        if (failure.get() != null) {
            throw new AssertionError("流重放失败", failure.get());
        }
        if (scenario.hertz() > 0) {
            FxTestSupport.run(
                    () -> shell.web()
                            .getEngine()
                            .executeScript(
                                    "var b=document.querySelector('.new-messages');if(b)b.click();else window.scrollTo(0,document.documentElement.scrollHeight)"));
            shell.awaitTail(MARKER);
        }
    }

    private static void collect(
            DesktopChatReplayFixture shell, DesktopReplayMetrics metrics, Map<String, Object> result) {
        metrics.close();
        List<Double> input = metrics.inputs();
        List<Double> pulses = metrics.pulses();
        result.put("inputSamples", input.size());
        result.put("inputQueueToLayoutP95Ms", DesktopReplayMetrics.percentile(input, 0.95));
        result.put("pulseP95Ms", DesktopReplayMetrics.percentile(pulses, 0.95));
        result.put("pulseMaxMs", DesktopReplayMetrics.percentile(pulses, 1));
        result.put(
                "pulseOver100Ms", pulses.stream().filter(value -> value > 100).count());
        result.put("draftPreserved", shell.composer().getText().equals("字".repeat(INPUTS)));
        result.put(
                "mountedArticles",
                shell.web().getEngine().executeScript("document.querySelectorAll('article').length"));
        result.put(
                "cachedMessages",
                shell.web()
                        .getEngine()
                        .executeScript("Number(document.getElementById('surface').dataset.messageCacheSize)"));
        result.put("visitedWindows", shell.web().getEngine().executeScript("replayWindows.size"));
        if (input.size() != INPUTS || !Boolean.TRUE.equals(result.get("draftPreserved"))) {
            throw new AssertionError("输入事件没有完整进入生产编辑器");
        }
    }

    private static void produce(DesktopChatReplayFixture shell, int hertz, AtomicReference<Throwable> failure) {
        if (hertz == 0) {
            return;
        }
        try {
            shell.startStream();
            shell.append("## 长回复\n\n" + "用于验证长正文中的输入和滚动。".repeat(2200));
            for (int index = 0; index < hertz * 3; index++) {
                shell.append("\n追加内容 " + index);
                Thread.sleep(1000 / hertz);
            }
            shell.append("\n" + MARKER);
        } catch (Exception problem) {
            failure.set(problem);
        }
    }

    private static long cpu() {
        return ProcessHandle.current().info().totalCpuDuration().orElseThrow().toNanos();
    }

    private static double elapsed(long started) {
        return (System.nanoTime() - started) / 1_000_000.0;
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

    private record Scenario(String name, int count, int hertz, String body) {}
}
