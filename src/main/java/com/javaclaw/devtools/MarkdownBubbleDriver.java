package com.javaclaw.devtools;

import com.javaclaw.chat.MarkdownBubble;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.util.Duration;

import javax.imageio.ImageIO;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * MarkdownBubble 端到端驱动：验证流式普通文本、异步排版阶段和最终 Markdown 视图。
 * 任一后台渲染、动画或截图错误都会使进程以非零状态退出。
 */
public final class MarkdownBubbleDriver {

    private static Path outDir;
    private static volatile int exitCode;

    private static final String SAMPLE = """
            # 渲染验证报告

            这是一段**粗体**、*斜体*、~~删除线~~与 `inline code` 混排的中文正文，\
            链接见 [JavaClaw 仓库](https://github.com/example/javaclaw)，emoji：😊🚀。

            ## 列表与嵌套

            1. 有序项一
            2. 有序项二
               - 嵌套无序项 A
               - 嵌套无序项 B

            ```java
            public static void main(String[] args) {
                System.out.println("你好，异步终态渲染");
            }
            ```

            | 维度 | 结论 | 备注 |
            |------|------|------|
            | 流式 | 普通文本 | 零解析 |
            | 终态 | RichTextArea | 后台构建 |

            > 引用块：验证缩进与弱化配色的渲染效果。

            ---

            收尾段落：以上内容只在消息完成后解析一次。
            """;

    private MarkdownBubbleDriver() {}

    public static void main(String[] args) {
        Thread.setDefaultUncaughtExceptionHandler((thread, failure) -> {
            failure.printStackTrace();
            exitCode = 1;
        });
        try {
            outDir = Path.of(args.length > 0 ? args[0] : "poc-out");
            Files.createDirectories(outDir);
            Application.launch(DriverApp.class, args);
        } catch (Throwable failure) {
            failure.printStackTrace();
            exitCode = 1;
        }
        System.exit(exitCode);
    }

    public static final class DriverApp extends Application {

        private MarkdownBubble bubble;
        private VBox root;
        private long renderDeadlineNanos;
        private boolean streamingSnapshotWritten;

        @Override
        public void start(Stage stage) {
            try {
                com.javaclaw.ui.javafx.theme.FontManager.loadBundledFonts();
                bubble = new MarkdownBubble(520);
                root = new VBox(bubble.getView());
                root.setStyle("-fx-background-color: -jc-surface-page; -fx-padding: 14;");
                ScrollPane scrollPane = new ScrollPane(root);
                scrollPane.setFitToWidth(true);
                Scene scene = new Scene(scrollPane, 580, 860);
                scene.getStylesheets().add(
                        getClass().getResource("/css/chat.css").toExternalForm());
                stage.setScene(scene);
                stage.setTitle("MarkdownBubble Async Driver");
                stage.show();
                feedStreamingContent();
            } catch (Throwable failure) {
                failAndExit(failure);
            }
        }

        private void feedStreamingContent() {
            final int chunkSize = 24;
            final int totalTicks = (SAMPLE.length() + chunkSize - 1) / chunkSize;
            final int[] offset = {0};
            long startedAt = System.nanoTime();

            Timeline feeder = new Timeline(new KeyFrame(Duration.millis(40), event -> {
                int end = Math.min(offset[0] + chunkSize, SAMPLE.length());
                bubble.appendText(SAMPLE.substring(offset[0], end));
                offset[0] = end;
                if (!streamingSnapshotWritten && offset[0] >= SAMPLE.length() / 2) {
                    writeSnapshot("driver-streaming-plain.png");
                    streamingSnapshotWritten = true;
                }
            }));
            feeder.setCycleCount(totalTicks);
            feeder.setOnFinished(event -> {
                if (!streamingSnapshotWritten) writeSnapshot("driver-streaming-plain.png");
                System.out.println("流式阶段: " + state()
                        + " · " + SAMPLE.length() + " 字符 · "
                        + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt) + "ms");

                // refresh 在流式态就是终态提交；同步返回时后台结果尚未回到 FX 队列。
                bubble.refresh();
                if (!"RENDERING".equals(state())) {
                    failAndExit(new IllegalStateException("提交后未进入 RENDERING: " + state()));
                    return;
                }
                writeSnapshot("driver-rendering.png");
                renderDeadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                pollRenderCompletion();
            });
            feeder.play();
        }

        private void pollRenderCompletion() {
            Timeline[] holder = new Timeline[1];
            Timeline poller = new Timeline(new KeyFrame(Duration.millis(25), event -> {
                String state = state();
                if ("FINAL_MARKDOWN".equals(state)) {
                    holder[0].stop();
                    verifyAndFinish();
                } else if ("PLAIN_FALLBACK".equals(state)
                        || System.nanoTime() >= renderDeadlineNanos) {
                    holder[0].stop();
                    failAndExit(new IllegalStateException(
                            "Markdown 未成功完成终态渲染: " + state));
                }
            }));
            holder[0] = poller;
            poller.setCycleCount(Timeline.INDEFINITE);
            poller.play();
        }

        private void verifyAndFinish() {
            try {
                Object renderFailure = bubble.getView().getProperties().get("markdownRenderFailure");
                Object animationFailure = bubble.getView().getProperties().get("markdownAnimationFailure");
                if (renderFailure != null) {
                    throw new IllegalStateException("后台渲染异常: " + renderFailure);
                }
                if (animationFailure != null) {
                    throw new IllegalStateException("切换动画异常: " + animationFailure);
                }
                Object transitionStart = bubble.getView().getProperties()
                        .get("markdownTransitionStartedAtNanos");
                Object transitionFinish = bubble.getView().getProperties()
                        .get("markdownTransitionFinishedAtNanos");
                if (!(transitionStart instanceof Long start)
                        || !(transitionFinish instanceof Long finish)
                        || finish - start < TimeUnit.MILLISECONDS.toNanos(120)) {
                    throw new IllegalStateException("未观察到完整的 150ms 交叉淡入");
                }
                if (bubble.getLength() != SAMPLE.length()) {
                    throw new IllegalStateException("原文长度不一致: " + bubble.getLength());
                }
                if (bubble.getView().getChildrenUnmodifiable().size() != 2) {
                    throw new IllegalStateException("交叉淡入结束后旧内容节点未清理");
                }
                writeSnapshotOrThrow("driver-final-markdown.png");
                System.out.println("终态阶段: " + state()
                        + " · 气泡高度 " + bubble.getView().getHeight() + "px");
                System.out.println("驱动完成，截图目录: " + outDir.toAbsolutePath());
                bubble.dispose();
                Platform.exit();
            } catch (Throwable failure) {
                failAndExit(failure);
            }
        }

        private String state() {
            return String.valueOf(
                    bubble.getView().getProperties().get("markdownRenderState"));
        }

        private void writeSnapshot(String name) {
            try {
                writeSnapshotOrThrow(name);
            } catch (Throwable failure) {
                failAndExit(failure);
            }
        }

        private void writeSnapshotOrThrow(String name) throws Exception {
            WritableImage image = root.snapshot(new SnapshotParameters(), null);
            File output = outDir.resolve(name).toFile();
            if (!ImageIO.write(SwingFXUtils.fromFXImage(image, null), "png", output)) {
                throw new IllegalStateException("没有可用的 PNG writer");
            }
            System.out.println("截图: " + output.getAbsolutePath()
                    + " (" + (int) image.getWidth() + "x" + (int) image.getHeight() + ")");
        }

        private void failAndExit(Throwable failure) {
            failure.printStackTrace();
            exitCode = 1;
            if (bubble != null) bubble.dispose();
            Platform.exit();
        }
    }
}
