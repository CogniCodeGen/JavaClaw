package com.javaclaw.devtools;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.WritableImage;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.stage.Stage;
import jfx.incubator.scene.control.richtext.RichTextArea;
import jfx.incubator.scene.control.richtext.TextPos;
import jfx.incubator.scene.control.richtext.model.RichParagraph;
import jfx.incubator.scene.control.richtext.model.RichTextModel;
import jfx.incubator.scene.control.richtext.model.StyleAttributeMap;
import jfx.incubator.scene.control.richtext.model.StyledTextModel;

import javax.imageio.ImageIO;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 2B 方案 POC：JavaFX 25 孵化 RichTextArea 作为聊天气泡渲染基座的可行性验证。
 *
 * <p>不参与正式构建，本地验证用（与 SddHeadlessDriver 同范式）。核心验证：
 * 自定义 StyledTextModel（自持 RichParagraph 列表 + 手动 fireChangeEvent）能否同时满足
 * 「流式增量追加 + 尾段重写 + 嵌入任意 Region（代码块/表格）+ 选中复制 + CSS 换肤 + 高度自适应」。</p>
 *
 * <p>已证伪路线（保留记录）：可写 RichTextModel 的 insertParagraph 在 JavaFX 25 直接抛
 * UnsupportedOperationException，「可写模型 + Region 段落」不可行。</p>
 *
 * <p>运行：mvn compile 后
 * {@code java -cp target/classes:$(cat cp.txt) com.javaclaw.devtools.RichTextAreaPoc <输出目录>}</p>
 */
public class RichTextAreaPoc {

    private static Path outDir;

    /** 与 app.Launcher 同款：普通类入口绕过 JavaFX 非模块化启动检查 */
    public static void main(String[] args) throws Exception {
        outDir = Path.of(args.length > 0 ? args[0] : "poc-out");
        Files.createDirectories(outDir);
        Application.launch(PocApp.class, args);
    }

    /**
     * 聊天气泡文档模型：自持段落列表的只读模型（isWritable=false 只是禁用户编辑，
     * 程序侧通过 append/rewriteTail 变更并手动 fireChangeEvent 驱动视图增量刷新）。
     * 这是 2B 的核心机制验证对象。
     */
    static class ChatDocModel extends StyledTextModel {
        private final List<RichParagraph> paragraphs = new ArrayList<>();

        ChatDocModel() {
            paragraphs.add(RichParagraph.builder().build()); // 起始空段
        }

        @Override public boolean isWritable() { return false; }
        @Override public int size() { return paragraphs.size(); }
        @Override public RichParagraph getParagraph(int index) { return paragraphs.get(index); }
        @Override public String getPlainText(int index) { return paragraphs.get(index).getPlainText(); }
        @Override public int getParagraphLength(int index) { return getPlainText(index).length(); }
        @Override public StyleAttributeMap getStyleAttributeMap(
                jfx.incubator.scene.control.richtext.StyleResolver resolver, TextPos pos) {
            return StyleAttributeMap.EMPTY;
        }

        // 只读模型不走 replace() 协议，以下协定方法不会被调用
        @Override protected int insertTextSegment(int i, int o, String t, StyleAttributeMap a) { throw new UnsupportedOperationException(); }
        @Override protected void insertLineBreak(int i, int o) { throw new UnsupportedOperationException(); }
        @Override protected void insertParagraph(int i, java.util.function.Supplier<Region> f) { throw new UnsupportedOperationException(); }
        @Override protected void removeRange(TextPos s, TextPos e) { throw new UnsupportedOperationException(); }
        @Override protected void setParagraphStyle(int i, StyleAttributeMap a) { throw new UnsupportedOperationException(); }
        @Override protected void applyStyle(int s, int o1, int o2, StyleAttributeMap a, boolean m) { throw new UnsupportedOperationException(); }

        /** 末尾追加一批段落并通知视图（末段被替换重建：模拟流式尾段增长） */
        void replaceTail(int fromIndex, List<RichParagraph> newTail) {
            TextPos start = TextPos.ofLeading(fromIndex, 0);
            TextPos oldEnd = getDocumentEnd();
            while (paragraphs.size() > fromIndex) paragraphs.remove(paragraphs.size() - 1);
            paragraphs.addAll(newTail);
            if (paragraphs.isEmpty()) paragraphs.add(RichParagraph.builder().build());
            int lastLen = getParagraphLength(paragraphs.size() - 1);
            fireChangeEvent(start, oldEnd, 0, Math.max(0, newTail.size() - 1), lastLen);
        }

        /** 整段追加（已定稿的块：段落只增不改） */
        void appendParagraphs(List<RichParagraph> ps) {
            replaceTail(paragraphs.size(), ps);
        }
    }

    public static class PocApp extends Application {

    @Override
    public void start(Stage stage) throws Exception {
        System.out.println("=== P1 环境检查 ===");
        System.out.println("RichTextArea 来源: " + RichTextArea.class.getProtectionDomain()
                .getCodeSource().getLocation());
        System.out.println("所在模块: " + RichTextArea.class.getModule());
        System.out.println("JavaFX 版本: " + System.getProperty("javafx.runtime.version"));

        // ---------- P2 静态渲染：CSS 样式类 + Region 嵌入 + emoji ----------
        ChatDocModel model = new ChatDocModel();
        model.appendParagraphs(List.of(
                RichParagraph.builder().addWithStyleNames("标题：孵化 RichTextArea 渲染验证", "md-h1").build(),
                RichParagraph.builder()
                        .addWithStyleNames("这是一段中文正文，混排 English、", "md-body")
                        .addWithStyleNames("行内代码 someMethod()", "md-inline-code")
                        .addWithStyleNames(" 与 ", "md-body")
                        .addWithStyleNames("粗体", "md-bold")
                        .addWithStyleNames("，以及彩色表情 😊🚀 的渲染效果。", "md-body")
                        .build(),
                RichParagraph.builder().addWithStyleNames("• 列表项一：验证选中复制", "md-body").build(),
                RichParagraph.builder().addWithStyleNames("• 列表项二：验证高度自适应", "md-body").build(),
                RichParagraph.of(RichTextAreaPoc::buildCodeBlockCard),
                RichParagraph.of(RichTextAreaPoc::buildTableRegion),
                RichParagraph.builder().addWithStyleNames("表格与代码块之后的收尾段落。", "md-body").build()));

        RichTextArea rta = new RichTextArea(model);
        rta.setEditable(false);
        rta.setUseContentHeight(true);
        rta.setWrapText(true);
        rta.getStyleClass().add("md-bubble");

        VBox root = new VBox(8, rta);
        root.setStyle("-fx-background-color: #FBFAF6; -fx-padding: 12;");
        root.setPrefWidth(560);
        Scene scene = new Scene(root, 560, 820);
        // P9 CSS 换肤：外部样式表控制气泡背景与文字样式类（对应 -jc-* 令牌机制）
        Path css = outDir.resolve("poc.css");
        Files.writeString(css, """
                .md-bubble { -fx-background-color: #F3F1EB; -fx-background-radius: 10; }
                .md-h1 { -fx-font-size: 18; -fx-font-weight: bold; -fx-fill: #27251F; }
                .md-body { -fx-font-size: 13.5; -fx-fill: #27251F; }
                .md-bold { -fx-font-size: 13.5; -fx-font-weight: bold; -fx-fill: #27251F; }
                .md-inline-code { -fx-font-family: Menlo; -fx-font-size: 12.5; -fx-fill: #1F7E54; }
                """);
        scene.getStylesheets().add(css.toUri().toString());
        stage.setScene(scene);
        stage.setTitle("RichTextArea POC");
        stage.show();

        Platform.runLater(() -> {
            try {
                runPhases(stage, rta, model, root);
            } catch (Throwable t) {
                t.printStackTrace();
                Platform.exit();
                System.exit(1);
            }
        });
    }

    private void runPhases(Stage stage, RichTextArea rta, ChatDocModel model, VBox root) throws Exception {
        root.applyCss();
        root.layout();

        System.out.println("\n=== P2 静态渲染（CSS 类 + Region 段落）===");
        System.out.println("文档段落数: " + model.size() + "，气泡高度: " + rta.getHeight()
                + "px（useContentHeight 自适应）");
        snapshot(rta, "poc-static.png");

        // ---------- P3 live 增量：挂上视图后继续 append + fire，验证视图刷新 ----------
        System.out.println("\n=== P3 live 增量追加（自定义模型 + fireChangeEvent）===");
        double hBefore = rta.getHeight();
        model.appendParagraphs(List.of(
                RichParagraph.builder().addWithStyleNames("live 追加的新段落（视图应自动刷新且高度增长）", "md-bold").build()));
        root.applyCss();
        root.layout();
        System.out.println("追加前高度 " + hBefore + "px → 追加后 " + rta.getHeight()
                + "px（增长则机制成立）");

        // ---------- P4 流式追加性能：自定义模型逐段/尾段重写 ----------
        System.out.println("\n=== P4 流式尾段重写性能（模拟 100ms 节流的增量渲染）===");
        ChatDocModel sm = new ChatDocModel();
        RichTextArea srta = new RichTextArea(sm);
        srta.setEditable(false);
        srta.setUseContentHeight(true);
        srta.setWrapText(true);
        root.getChildren().add(srta);
        String seed = "流式追加的正文内容，模拟 LLM 逐字输出的场景，混排 English words 与标点。";
        StringBuilder tail = new StringBuilder();
        List<Long> times = new ArrayList<>();
        int stableCount = 1; // 尾段之前的稳定段落数
        for (int i = 0; i < 200; i++) {
            tail.append(seed);
            long t0 = System.nanoTime();
            if (i % 10 == 9) {
                // 每 10 次模拟一个块定稿：尾段转正 + 新开尾段
                sm.replaceTail(stableCount, List.of(
                        RichParagraph.builder().addWithStyleNames(tail.toString(), "md-body").build()));
                stableCount = sm.size();
                tail.setLength(0);
            } else {
                sm.replaceTail(stableCount, List.of(
                        RichParagraph.builder().addWithStyleNames(tail.toString(), "md-body").build()));
            }
            root.applyCss();
            root.layout();
            times.add(System.nanoTime() - t0);
        }
        report("尾段重写+layout", times, seed.length() * 200);

        // ---------- P5 纯文本 appendText（可写 RichTextModel 对照组）----------
        System.out.println("\n=== P5 对照：可写 RichTextModel.appendText（无 Region 场景）===");
        RichTextArea plain = new RichTextArea(new RichTextModel());
        plain.setEditable(false);
        plain.setUseContentHeight(true);
        plain.setWrapText(true);
        root.getChildren().add(plain);
        StyleAttributeMap attrs = StyleAttributeMap.builder().setFontSize(13.5).build();
        times.clear();
        for (int i = 0; i < 200; i++) {
            long t0 = System.nanoTime();
            plain.appendText(seed, attrs);
            if (i % 7 == 0) plain.appendText("\n", attrs);
            root.applyCss();
            root.layout();
            times.add(System.nanoTime() - t0);
        }
        report("appendText+layout", times, seed.length() * 200);
        System.out.println("editable=false 下 appendText 是否生效: "
                + (plain.getModel().getDocumentEnd().index() > 0 ? "是（程序侧写入不受限）" : "否"));

        // ---------- P6 整模型重建 + setModel（兜底策略成本）----------
        System.out.println("\n=== P6 整模型重建 + setModel（10k 字 + 2 Region）===");
        times.clear();
        for (int i = 0; i < 20; i++) {
            long t0 = System.nanoTime();
            ChatDocModel fresh = new ChatDocModel();
            List<RichParagraph> ps = new ArrayList<>();
            for (int p = 0; p < 40; p++) {
                ps.add(RichParagraph.builder().addWithStyleNames(seed + seed + seed + seed + seed + seed + seed, "md-body").build());
            }
            ps.add(RichParagraph.of(RichTextAreaPoc::buildCodeBlockCard));
            ps.add(RichParagraph.of(RichTextAreaPoc::buildTableRegion));
            fresh.appendParagraphs(ps);
            srta.setModel(fresh);
            root.applyCss();
            root.layout();
            times.add(System.nanoTime() - t0);
        }
        report("整模型重建+setModel+layout", times, seed.length() * 7 * 40);

        // ---------- P7 选中复制 ----------
        System.out.println("\n=== P7 selectAll + copy 剪贴板 ===");
        Clipboard cb = Clipboard.getSystemClipboard();
        String saved = cb.getString(); // 保存用户剪贴板，结束后恢复
        rta.selectAll();
        rta.copy();
        String copied = cb.getString();
        System.out.println("复制取回字符数: " + (copied == null ? -1 : copied.length())
                + "，前 80 字: " + (copied == null ? "null"
                : copied.substring(0, Math.min(80, copied.length())).replace("\n", "\\n")));
        if (saved != null) {
            ClipboardContent restore = new ClipboardContent();
            restore.putString(saved);
            cb.setContent(restore);
        }

        // ---------- P8 30 气泡 VBox+ScrollPane ----------
        System.out.println("\n=== P8 30 个气泡嵌 ScrollPane ===");
        VBox list = new VBox(10);
        long t0 = System.nanoTime();
        for (int i = 0; i < 30; i++) {
            ChatDocModel m = new ChatDocModel();
            List<RichParagraph> ps = new ArrayList<>();
            ps.add(RichParagraph.builder().addWithStyleNames(
                    "气泡 #" + i + "：一段中等长度的消息正文，验证多实例场景下的创建与布局成本。", "md-body").build());
            ps.add(RichParagraph.builder().addWithStyleNames("第二行内容。", "md-body").build());
            if (i % 5 == 0) ps.add(RichParagraph.of(RichTextAreaPoc::buildCodeBlockCard));
            m.appendParagraphs(ps);
            RichTextArea b = new RichTextArea(m);
            b.setEditable(false);
            b.setUseContentHeight(true);
            b.setWrapText(true);
            b.getStyleClass().add("md-bubble");
            list.getChildren().add(b);
        }
        ScrollPane sp = new ScrollPane(list);
        sp.setFitToWidth(true);
        Scene s2 = new Scene(sp, 560, 800);
        s2.getStylesheets().addAll(stage.getScene().getStylesheets());
        Stage st2 = new Stage();
        st2.setScene(s2);
        st2.show();
        sp.applyCss();
        sp.layout();
        long createMs = (System.nanoTime() - t0) / 1_000_000;
        System.out.println("30 气泡（6 个含代码块 Region）创建+布局总耗时: " + createMs + "ms");

        // ---------- P9 高度异步结算复核：useContentHeight 依赖渲染脉冲，等 400ms 后复测 ----------
        javafx.animation.PauseTransition settle = new javafx.animation.PauseTransition(
                javafx.util.Duration.millis(400));
        settle.setOnFinished(e -> {
            try {
                System.out.println("\n=== P9 渲染脉冲结算后复核 ===");
                System.out.println("P3 live 追加气泡高度（400ms 后）: " + rta.getHeight()
                        + "px（此前同帧读数 274px；增长则说明高度异步结算、机制成立）");
                snapshot(rta, "poc-live.png");
                sp.layout();
                snapshot(sp, "poc-list.png");
                st2.close();
                System.out.println("\nPOC 完成，截图输出目录: " + outDir.toAbsolutePath());
            } catch (Throwable t) {
                t.printStackTrace();
            } finally {
                Platform.exit();
                System.exit(0);
            }
        });
        settle.play();
    }
    }

    /** 代码块卡片：深底圆角 VBox + 等宽 Text（模拟高亮后的多色 token） */
    private static Region buildCodeBlockCard() {
        TextFlow flow = new TextFlow(
                mono("public ", "#C586C0"), mono("void ", "#569CD6"),
                mono("hello", "#DCDCAA"), mono("() {\n", "#D4D4D4"),
                mono("    System.out.println(", "#D4D4D4"),
                mono("\"你好，RichTextArea\"", "#CE9178"), mono(");\n}", "#D4D4D4"));
        VBox card = new VBox(flow);
        card.setStyle("-fx-background-color: #27251F; -fx-background-radius: 10; -fx-padding: 12;");
        return card;
    }

    private static Text mono(String s, String color) {
        Text t = new Text(s);
        t.setStyle("-fx-font-family: Menlo; -fx-font-size: 12.5; -fx-fill: " + color + ";");
        return t;
    }

    /** 表格 Region：GridPane 斑马纹 */
    private static Region buildTableRegion() {
        GridPane grid = new GridPane();
        grid.setStyle("-fx-border-color: #E8E4DB; -fx-padding: 1;");
        String[][] rows = {{"维度", "结论"}, {"表格", "GridPane 嵌入"}, {"高亮", "Text 节点着色"}};
        for (int r = 0; r < rows.length; r++) {
            for (int c = 0; c < 2; c++) {
                javafx.scene.control.Label cell = new javafx.scene.control.Label(rows[r][c]);
                cell.setStyle("-fx-padding: 5 10; -fx-font-size: 12.5;"
                        + (r == 0 ? "-fx-font-weight: bold; -fx-background-color: #F3F1EB;"
                                  : (r % 2 == 0 ? "-fx-background-color: #FAF9F4;" : "")));
                cell.setMaxWidth(Double.MAX_VALUE);
                grid.add(cell, c, r);
            }
        }
        return grid;
    }

    private static void report(String label, List<Long> nanos, int totalChars) {
        List<Long> sorted = new ArrayList<>(nanos);
        sorted.sort(Long::compare);
        long p50 = sorted.get(sorted.size() / 2) / 1_000;
        long p95 = sorted.get((int) (sorted.size() * 0.95)) / 1_000;
        long max = sorted.get(sorted.size() - 1) / 1_000;
        long first10 = nanos.subList(0, 10).stream().mapToLong(Long::longValue).sum() / 10_000;
        long last10 = nanos.subList(nanos.size() - 10, nanos.size()).stream().mapToLong(Long::longValue).sum() / 10_000;
        System.out.println(label + ": p50=" + p50 + "µs p95=" + p95 + "µs max=" + max + "µs"
                + " | 前10次均值=" + first10 + "µs 后10次均值=" + last10 + "µs（增长斜率参考）"
                + (totalChars > 0 ? " | 最终文档约 " + totalChars + " 字" : ""));
    }

    private static void snapshot(javafx.scene.Node node, String name) throws Exception {
        WritableImage img = node.snapshot(new SnapshotParameters(), null);
        File f = outDir.resolve(name).toFile();
        ImageIO.write(SwingFXUtils.fromFXImage(img, null), "png", f);
        System.out.println("截图: " + f.getAbsolutePath() + " (" + (int) img.getWidth() + "x" + (int) img.getHeight() + ")");
    }
}
