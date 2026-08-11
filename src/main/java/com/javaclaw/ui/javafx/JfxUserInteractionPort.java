package com.javaclaw.ui.javafx;

import com.javaclaw.api.interaction.ConfirmDecision;
import com.javaclaw.api.interaction.ConfirmKind;
import com.javaclaw.api.interaction.ConfirmRequest;
import com.javaclaw.api.interaction.ChoiceRequest;
import com.javaclaw.api.interaction.SecretRequest;
import com.javaclaw.api.interaction.ToastRequest;
import com.javaclaw.api.interaction.UserInteractionPort;
import com.javaclaw.platform.fx.FxDispatcher;
import com.javaclaw.ui.javafx.image.ImageViewerFactory;
import com.javaclaw.ui.javafx.interaction.InteractionDialogFactory;
import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * {@link UserInteractionPort} 的 JavaFX 实现。
 *
 * <p>所有弹窗都通过 {@link FxDispatcher} 切换到 JavaFX Application Thread；
 * 调用线程阻塞等待 {@link CompletableFuture} 直到用户响应或超时。</p>
 *
 * <p>非阻塞通知的最终渲染由外部注入的 {@link #setToastHandler(Consumer) toastHandler}
 * 完成：本端口仅做线程切换、不关心 UI 形式（顶部横幅 / 侧边卡片等）。</p>
 */
public final class JfxUserInteractionPort implements UserInteractionPort {

    private static final Logger log = LoggerFactory.getLogger(JfxUserInteractionPort.class);
    private final FxDispatcher fx;
    private final ImageViewerFactory imageViewer;
    private final InteractionDialogFactory dialogs;

    public JfxUserInteractionPort(
            FxDispatcher fx,
            ImageViewerFactory imageViewer,
            InteractionDialogFactory dialogs) {
        this.fx = java.util.Objects.requireNonNull(fx, "fx");
        this.imageViewer = java.util.Objects.requireNonNull(imageViewer, "imageViewer");
        this.dialogs = java.util.Objects.requireNonNull(dialogs, "dialogs");
    }

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
            case CONFIRM, DOUBLE_CONFIRM -> showConfirmDialog(request);
        };
    }

    @Override
    public String choose(ChoiceRequest request) {
        if (request == null || request.options().isEmpty()) return null;
        CompletableFuture<String> future = new CompletableFuture<>();
        fx.dispatch(() -> {
            try {
                future.complete(dialogs.choose(request));
            } catch (Exception e) {
                log.error("选择对话框异常", e);
                future.complete(null);
            }
        });

        int effective = request.timeoutSeconds() > 0
                ? request.timeoutSeconds() : FALLBACK_TIMEOUT_SEC;
        try {
            return future.get(effective, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("等待用户选择超时或异常 [{}]（{}s）", request.title(), effective);
            return null;
        }
    }

    @Override
    public char[] requestSecret(SecretRequest request) {
        if (request == null) return null;
        CompletableFuture<char[]> future = new CompletableFuture<>();
        fx.dispatch(() -> {
            try {
                future.complete(dialogs.requestSecret(request));
            } catch (Exception e) {
                log.error("安全输入对话框异常", e);
                future.complete(null);
            }
        });

        try {
            return future.get(request.timeoutSeconds(), TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("等待安全输入超时或异常 [{}]（{}s）", request.title(), request.timeoutSeconds());
            return null;
        }
    }

    @Override
    public void notify(ToastRequest request) {
        String text = "[" + request.title() + "] " + request.message();
        Consumer<String> handler = toastHandler;
        if (handler != null) {
            fx.dispatch(() -> handler.accept(text));
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
        fx.dispatch(() -> {
            try {
                imageViewer.open(null, file.toPath());
            } catch (Exception e) {
                log.warn("打开图片查看窗口失败: {}", imagePath, e);
            }
        });
    }

    @Override
    public boolean isAvailable() {
        // JavaFX Platform 已启动即视为可用；Toolkit 未初始化时 FxDispatcher 排队会抛异常。
        try {
            return Platform.isFxApplicationThread() || !Platform.isImplicitExit()
                    || true; // 一旦进入 start()，Platform 始终可用
        } catch (Throwable t) {
            return false;
        }
    }

    private ConfirmDecision showConfirmDialog(ConfirmRequest req) {
        CompletableFuture<ConfirmDecision> future = new CompletableFuture<>();
        fx.dispatch(() -> {
            try {
                future.complete(dialogs.confirm(req));
            } catch (Exception e) {
                log.error("确认对话框异常", e);
                future.complete(ConfirmDecision.DENY);
            }
        });
        return awaitDecision(future, req.toolName(), req.timeoutSeconds());
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

}
