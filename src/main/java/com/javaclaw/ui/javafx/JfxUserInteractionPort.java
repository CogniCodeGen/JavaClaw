package com.javaclaw.ui.javafx;

import com.javaclaw.api.interaction.ConfirmDecision;
import com.javaclaw.api.interaction.ConfirmKind;
import com.javaclaw.api.interaction.ConfirmRequest;
import com.javaclaw.api.interaction.ToastRequest;
import com.javaclaw.api.interaction.UserInteractionPort;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * {@link UserInteractionPort} 的 JavaFX 实现。
 *
 * <p>所有弹窗都通过 {@link Platform#runLater} 切换到 JavaFX Application Thread；
 * 调用线程阻塞等待 {@link CompletableFuture} 直到用户响应或超时。</p>
 *
 * <p>非阻塞通知的最终渲染由外部注入的 {@link #setToastHandler(Consumer) toastHandler}
 * 完成：本端口仅做线程切换、不关心 UI 形式（顶部横幅 / 侧边卡片等）。</p>
 */
public final class JfxUserInteractionPort implements UserInteractionPort {

    private static final Logger log = LoggerFactory.getLogger(JfxUserInteractionPort.class);

    /** Toast 的 UI 层渲染器；未设置时 notify 降级为日志输出 */
    private volatile Consumer<String> toastHandler;

    /** 注入 Toast 渲染器（通常由 ChatViewController 负责顶部横幅展示） */
    public void setToastHandler(Consumer<String> handler) {
        this.toastHandler = handler;
    }

    /** 返回当前 Toast 渲染器（可能为 null）；供临时接管者（如模态窗口）保存后还原。 */
    public Consumer<String> getToastHandler() {
        return toastHandler;
    }

    @Override
    public boolean confirm(ConfirmRequest request) {
        return confirmEx(request).isAllow();
    }

    @Override
    public ConfirmDecision confirmEx(ConfirmRequest request) {
        ConfirmKind kind = request.kind();
        return switch (kind) {
            case NOTIFY -> {
                notify(new ToastRequest(request.toolName(), request.description()));
                yield ConfirmDecision.ALLOW_ONCE;
            }
            case CONFIRM -> showConfirmDialog(request);
            case DOUBLE_CONFIRM -> showDoubleConfirmDialog(request);
        };
    }

    @Override
    public void notify(ToastRequest request) {
        String text = "[" + request.title() + "] " + request.message();
        Consumer<String> handler = toastHandler;
        if (handler != null) {
            Platform.runLater(() -> handler.accept(text));
        } else {
            log.info("工具通知（无 Toast 处理器）：{}", text);
        }
    }

    @Override
    public void previewImage(java.nio.file.Path imagePath) {
        if (imagePath == null) return;
        java.io.File file = imagePath.toFile();
        if (!file.isFile()) {
            log.warn("预览图片失败：文件不存在 {}", imagePath);
            return;
        }
        Platform.runLater(() -> {
            try {
                com.javaclaw.chat.ImageViewerDialog.show(null, file);
            } catch (Exception e) {
                log.warn("打开图片查看窗口失败: {}", imagePath, e);
            }
        });
    }

    @Override
    public boolean isAvailable() {
        // JavaFX Platform 已启动即视为可用；Toolkit 未初始化时 Platform.runLater 抛 IllegalStateException
        try {
            return Platform.isFxApplicationThread() || !Platform.isImplicitExit()
                    || true; // 一旦进入 start()，Platform 始终可用
        } catch (Throwable t) {
            return false;
        }
    }

    // ==================== 内部实现 ====================

    /** 标准确认按钮 — 同意 / 同意一次 / 同意全部 / 拒绝 */
    private static final ButtonType BTN_ALLOW      = new ButtonType("同意",     ButtonBar.ButtonData.OK_DONE);
    private static final ButtonType BTN_ALLOW_ONCE = new ButtonType("同意一次", ButtonBar.ButtonData.OK_DONE);
    private static final ButtonType BTN_ALLOW_ALL  = new ButtonType("同意全部", ButtonBar.ButtonData.YES);
    private static final ButtonType BTN_DENY       = new ButtonType("拒绝",     ButtonBar.ButtonData.CANCEL_CLOSE);

    private ConfirmDecision showConfirmDialog(ConfirmRequest req) {
        CompletableFuture<ConfirmDecision> future = new CompletableFuture<>();
        int timeoutSec = req.timeoutSeconds();
        // 「同意全部」白名单仅在托管任务场景生效（按 taskId 绑定）；普通聊天里它不起作用，
        // 故非托管场景只给「同意 / 拒绝」两个按钮，避免误导。
        boolean managed = req.managedTask();

        Platform.runLater(() -> {
            try {
                Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
                alert.setTitle("操作确认");
                alert.setHeaderText("是否同意执行：" + req.toolName());
                if (managed) {
                    alert.setContentText(req.description()
                            + "\n\n选择「同意全部」后，本任务内所有后续高风险操作将自动放行不再弹窗。");
                    alert.getButtonTypes().setAll(BTN_DENY, BTN_ALLOW_ONCE, BTN_ALLOW_ALL);
                } else {
                    alert.setContentText(req.description());
                    alert.getButtonTypes().setAll(BTN_DENY, BTN_ALLOW);
                }
                attachDetails(alert, req);
                alert.showAndWait().ifPresentOrElse(
                        btn -> future.complete(toDecision(btn)),
                        () -> future.complete(ConfirmDecision.DENY));
            } catch (Exception e) {
                log.error("确认对话框异常", e);
                future.complete(ConfirmDecision.DENY);
            }
        });

        return awaitDecision(future, req.toolName(), timeoutSec);
    }

    /**
     * 不可逆操作的二次确认 — 仍要求键入关键词，但同样支持「同意全部」批量授权。
     *
     * <p>关键词输入正确后两个允许按钮才点亮；「同意全部」对该任务后续同名调用直接放行
     * 不再弹窗。关键词错误或留空 = 拒绝。</p>
     */
    private ConfirmDecision showDoubleConfirmDialog(ConfirmRequest req) {
        CompletableFuture<ConfirmDecision> future = new CompletableFuture<>();
        int timeoutSec = req.timeoutSeconds();
        String keyword = req.keyword();

        Platform.runLater(() -> {
            try {
                Dialog<ConfirmDecision> dialog = new Dialog<>();
                dialog.setTitle("二次确认（不可逆操作）");
                dialog.setHeaderText("即将执行不可逆高风险操作：" + req.toolName());

                Label hint = new Label("请输入关键词「" + keyword + "」以启用允许按钮；"
                        + "选择「同意全部」后，本任务内所有后续高风险操作将自动放行不再弹窗。");
                hint.setWrapText(true);

                Label desc = new Label(req.description());
                desc.setWrapText(true);
                desc.setStyle("-fx-text-fill: -jc-text-muted;");

                TextField input = new TextField();
                input.setPromptText("输入 " + keyword);

                VBox box = new VBox(8, desc, hint, input);
                box.setPadding(new Insets(8, 4, 4, 4));
                dialog.getDialogPane().setContent(box);
                dialog.getDialogPane().getButtonTypes().setAll(BTN_DENY, BTN_ALLOW_ONCE, BTN_ALLOW_ALL);

                javafx.scene.Node btnAllowOnce = dialog.getDialogPane().lookupButton(BTN_ALLOW_ONCE);
                javafx.scene.Node btnAllowAll  = dialog.getDialogPane().lookupButton(BTN_ALLOW_ALL);
                btnAllowOnce.setDisable(true);
                btnAllowAll.setDisable(true);
                input.textProperty().addListener((obs, o, n) -> {
                    boolean ok = keyword.equals(n == null ? "" : n.trim());
                    btnAllowOnce.setDisable(!ok);
                    btnAllowAll.setDisable(!ok);
                });

                dialog.setResultConverter(this::toDecision);
                dialog.showAndWait().ifPresentOrElse(
                        future::complete,
                        () -> future.complete(ConfirmDecision.DENY));
            } catch (Exception e) {
                log.error("二次确认对话框异常", e);
                future.complete(ConfirmDecision.DENY);
            }
        });

        return awaitDecision(future, req.toolName(), timeoutSec);
    }

    private ConfirmDecision toDecision(ButtonType btn) {
        if (btn == BTN_ALLOW_ALL) return ConfirmDecision.ALLOW_ALL;
        if (btn == BTN_ALLOW_ONCE || btn == BTN_ALLOW) return ConfirmDecision.ALLOW_ONCE;
        return ConfirmDecision.DENY;
    }

    /** 配置为 0/负数时的兜底超时：与托管场景上限一致，避免 UI 线程卡死时调用线程永久阻塞 */
    private static final int FALLBACK_TIMEOUT_SEC = 600;

    private ConfirmDecision awaitDecision(CompletableFuture<ConfirmDecision> future,
                                          String toolName, int timeoutSec) {
        int effective = timeoutSec > 0 ? timeoutSec : FALLBACK_TIMEOUT_SEC;
        try {
            return future.get(effective, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("等待用户交互超时或异常 [{}]（{}s）", toolName, effective);
            return ConfirmDecision.DENY;
        }
    }

    /** 附加可展开的"查看详情"区域 */
    private void attachDetails(Alert alert, ConfirmRequest req) {
        TextArea details = new TextArea(
                "工具名：" + req.toolName() + "\n" +
                "风险等级：" + req.riskLabel() + "\n" +
                "超时：" + req.timeoutSeconds() + " 秒\n" +
                "托管场景：" + (req.managedTask() ? "是" : "否") + "\n\n" +
                "---- 操作参数 ----\n" + req.description());
        details.setEditable(false);
        details.setWrapText(true);
        details.setMaxWidth(Double.MAX_VALUE);
        details.setMaxHeight(Double.MAX_VALUE);
        GridPane.setVgrow(details, Priority.ALWAYS);
        GridPane.setHgrow(details, Priority.ALWAYS);

        GridPane pane = new GridPane();
        pane.setMaxWidth(Double.MAX_VALUE);
        pane.setPadding(new Insets(8, 0, 0, 0));
        pane.add(new Label("查看详情"), 0, 0);
        pane.add(details, 0, 1);

        alert.getDialogPane().setExpandableContent(pane);
    }

}
