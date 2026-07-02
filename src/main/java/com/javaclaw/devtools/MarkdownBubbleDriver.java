package com.javaclaw.devtools;

import com.javaclaw.chat.MarkdownBubble;
import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
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

/**
 * MarkdownBubble（2B 纯节点版）端到端验证驱动：模拟 LLM 流式输出喂给真实的
 * MarkdownBubble（走 100ms 节流 + 块级增量渲染主链路），加载 chat.css 真实主题，
 * 输出中途/最终两张截图供人工核对。不参与正式构建。
 *
 * <p>运行：mvn compile 后
 * {@code java -cp target/classes:$(cat cp.txt) com.javaclaw.devtools.MarkdownBubbleDriver <输出目录>}</p>
 */
public class MarkdownBubbleDriver {

    private static Path outDir;

    /** 覆盖全部渲染路径的样例（标题/样式/链接/列表/代码/表格/引用/分隔线/emoji） */
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
                System.out.println("你好，纯节点渲染");
            }
            ```

            | 维度 | 结论 | 备注 |
            |------|------|------|
            | 表格 | GridPane | 斑马纹 |
            | 高亮 | 后置 | tm4e |

            > 引用块：验证缩进与弱化配色的渲染效果。

            ---

            收尾段落：以上内容经流式分块追加完成渲染。
            """;

    public static void main(String[] args) throws Exception {
        outDir = Path.of(args.length > 0 ? args[0] : "poc-out");
        Files.createDirectories(outDir);
        Application.launch(DriverApp.class, args);
    }

    public static class DriverApp extends Application {

        @Override
        public void start(Stage stage) {
            // 注册打包字体（Cascadia Code 等），与 JavaClawApp 启动时一致
            com.javaclaw.ui.javafx.theme.FontManager.loadBundledFonts();
            MarkdownBubble bubble = new MarkdownBubble(520);
            VBox root = new VBox(bubble.getView());
            root.setStyle("-fx-background-color: -jc-surface-page; -fx-padding: 14;");
            ScrollPane sp = new ScrollPane(root);
            sp.setFitToWidth(true);
            Scene scene = new Scene(sp, 580, 860);
            scene.getStylesheets().add(getClass().getResource("/css/chat.css").toExternalForm());
            stage.setScene(scene);
            stage.setTitle("MarkdownBubble 2B Driver");
            stage.show();

            // 模拟流式：每 40ms 追加约 24 字符
            final int chunkSize = 24;
            final int totalTicks = (SAMPLE.length() + chunkSize - 1) / chunkSize;
            Timeline feeder = new Timeline();
            feeder.setCycleCount(totalTicks);
            final int[] offset = {0};
            long t0 = System.nanoTime();
            feeder.getKeyFrames().add(new KeyFrame(Duration.millis(40), e -> {
                int end = Math.min(offset[0] + chunkSize, SAMPLE.length());
                bubble.appendText(SAMPLE.substring(offset[0], end));
                offset[0] = end;
            }));
            feeder.setOnFinished(e -> {
                // 中途快照（节流窗内，尾块仍在增量重建）
                snapshotQuiet(root, "driver-midstream.png");
                PauseTransition settle = new PauseTransition(Duration.millis(600));
                settle.setOnFinished(ev -> {
                    try {
                        System.out.println("流式喂入 " + SAMPLE.length() + " 字符 · 总耗时 "
                                + (System.nanoTime() - t0) / 1_000_000 + "ms（含 40ms/块的模拟节奏）");
                        System.out.println("最终气泡高度: " + bubble.getView().getHeight() + "px");
                        snapshot(root, "driver-final.png");
                        System.out.println("getText 长度: " + bubble.getLength()
                                + "（应等于样例长度 " + SAMPLE.length() + "）");
                        bubble.dispose();
                        System.out.println("dispose 后再次 appendText 无异常: 验证中");
                        bubble.appendText("dispose 后追加应被忽略");
                        System.out.println("驱动完成，截图目录: " + outDir.toAbsolutePath());
                    } catch (Throwable t) {
                        t.printStackTrace();
                    } finally {
                        Platform.exit();
                        System.exit(0);
                    }
                });
                settle.play();
            });
            feeder.play();
        }

        private static void snapshotQuiet(javafx.scene.Node node, String name) {
            try {
                snapshot(node, name);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        private static void snapshot(javafx.scene.Node node, String name) throws Exception {
            WritableImage img = node.snapshot(new SnapshotParameters(), null);
            File f = outDir.resolve(name).toFile();
            ImageIO.write(SwingFXUtils.fromFXImage(img, null), "png", f);
            System.out.println("截图: " + f.getAbsolutePath()
                    + " (" + (int) img.getWidth() + "x" + (int) img.getHeight() + ")");
        }
    }
}
