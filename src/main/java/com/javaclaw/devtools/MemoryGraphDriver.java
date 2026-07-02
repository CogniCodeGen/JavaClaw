package com.javaclaw.devtools;

import com.javaclaw.memory.graph.MemoryGraph;
import com.javaclaw.ui.javafx.memory.MemoryGraphView;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.image.WritableImage;
import javafx.stage.Stage;
import javafx.util.Duration;

import javax.imageio.ImageIO;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * MemoryGraphView（纯 JavaFX Canvas 版）验证驱动：构造样例图谱（三类节点 + 三类边），
 * 渲染并等力导向收敛后截图，验证去 WebView 化后的绘制/图例/HUD/主题派生。不参与正式构建。
 *
 * <p>运行：mvn compile 后
 * {@code java -cp target/classes:$(cat cp.txt) com.javaclaw.devtools.MemoryGraphDriver <输出目录>}</p>
 */
public class MemoryGraphDriver {

    private static Path outDir;

    public static void main(String[] args) throws Exception {
        outDir = Path.of(args.length > 0 ? args[0] : "poc-out");
        Files.createDirectories(outDir);
        Application.launch(DriverApp.class, args);
    }

    public static class DriverApp extends Application {

        @Override
        public void start(Stage stage) {
            MemoryGraphView view = new MemoryGraphView();
            view.setOnNodeSelected(d -> System.out.println(
                    "选中回调: " + (d == null ? "清除" : d.label() + " related=" + d.related())));

            Scene scene = new Scene(view.getView(), 860, 640);
            stage.setScene(scene);
            stage.setTitle("MemoryGraph Canvas Driver");
            stage.show();

            view.render(sampleGraph());

            // 等力导向收敛（alpha 0.985^N 冷却，约 2.5s 足够）后截图
            PauseTransition settle = new PauseTransition(Duration.millis(2500));
            settle.setOnFinished(e -> {
                try {
                    snapshot(view.getView(), "graph-native.png");
                    // 过滤：只看事实+实体
                    view.setVisibleTypes(true, false, true);
                    snapshot(view.getView(), "graph-filtered.png");
                    view.dispose();
                    System.out.println("驱动完成，截图目录: " + outDir.toAbsolutePath());
                } catch (Throwable t) {
                    t.printStackTrace();
                } finally {
                    Platform.exit();
                    System.exit(0);
                }
            });
            settle.play();
        }

        private static MemoryGraph sampleGraph() {
            List<MemoryGraph.Node> nodes = new ArrayList<>();
            List<MemoryGraph.Edge> edges = new ArrayList<>();
            String[] facts = {"用户偏好深色主题", "项目使用 JavaFX 25", "记忆库基于 EclipseStore",
                    "气泡渲染已去 WebView", "嵌入模型维度 1024", "工作区按目录隔离"};
            for (int i = 0; i < facts.length; i++) {
                nodes.add(new MemoryGraph.Node("fact:" + i, facts[i], "fact", "偏好", facts[i] + "（详情）", 2 + i % 4));
            }
            for (int i = 0; i < 3; i++) {
                nodes.add(new MemoryGraph.Node("ep:" + i, "对话轮 #" + i, "episode", "会话A", "情景详情 " + i, 1));
            }
            String[] entities = {"JavaFX", "EclipseStore", "JavaClaw"};
            for (int i = 0; i < entities.length; i++) {
                nodes.add(new MemoryGraph.Node("ent:" + i, entities[i], "entity", "tool", entities[i] + " 实体", 3));
            }
            for (int i = 0; i < facts.length; i++) {
                edges.add(new MemoryGraph.Edge("fact:" + i, "ep:" + (i % 3), "source", 1));
                edges.add(new MemoryGraph.Edge("fact:" + i, "ent:" + (i % 3), "about", 1));
            }
            edges.add(new MemoryGraph.Edge("fact:0", "fact:3", "semantic", 0.85));
            edges.add(new MemoryGraph.Edge("fact:1", "fact:2", "semantic", 0.78));
            edges.add(new MemoryGraph.Edge("fact:4", "fact:5", "semantic", 0.8));
            return new MemoryGraph(nodes, edges);
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
