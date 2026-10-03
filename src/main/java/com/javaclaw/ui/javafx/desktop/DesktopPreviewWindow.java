package com.javaclaw.ui.javafx.desktop;

import com.javaclaw.desktop.api.DesktopAction;
import com.javaclaw.desktop.api.DesktopActionEvent;
import com.javaclaw.desktop.api.DesktopActionResult;
import com.javaclaw.desktop.api.DesktopFrame;
import com.javaclaw.desktop.api.DesktopSessionInfo;
import com.javaclaw.desktop.api.DesktopSessionObserver;
import com.javaclaw.desktop.api.DesktopSessionOwner;
import com.javaclaw.desktop.api.DesktopSessionService;
import com.javaclaw.desktop.api.DesktopSessionState;
import com.javaclaw.desktop.api.DesktopTarget;
import com.javaclaw.platform.fx.FxDispatcher;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

/** 桌面会话实时悬浮预览；采集、授权和控制仍由会话服务负责。 */
public final class DesktopPreviewWindow implements DesktopSessionObserver, AutoCloseable {
    private static final long FRAME_INTERVAL_MILLIS = 150;

    private final FxDispatcher fx;
    private final Map<String, Preview> previews = new ConcurrentHashMap<>();
    private final AtomicInteger nextPosition = new AtomicInteger();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ScheduledExecutorService scaler = Executors.newSingleThreadScheduledExecutor(task ->
            Thread.ofPlatform().daemon().name("desktop-preview-scaler").unstarted(task));

    /** 使用应用统一 FX 调度器创建预览订阅边界。 */
    public DesktopPreviewWindow(FxDispatcher fx) { this.fx = Objects.requireNonNull(fx); }

    @Override public void opened(DesktopSessionOwner owner, DesktopSessionInfo info,
                                 DesktopSessionService service) {
        if (closed.get()) return;
        Preview preview = new Preview(owner, info, service, nextPosition.getAndIncrement());
        if (previews.putIfAbsent(info.sessionId(), preview) != null) return;
        if (closed.get()) {
            if (previews.remove(info.sessionId(), preview)) preview.dispose();
            return;
        }
        service.frames(owner, info.sessionId()).subscribe(preview.frameSubscriber);
        service.states(owner, info.sessionId()).subscribe(preview.stateSubscriber);
        service.actions(owner, info.sessionId()).subscribe(preview.actionSubscriber);
        fx.dispatchLater(preview::show);
    }

    @Override public void closed(String sessionId) {
        Preview preview = previews.remove(sessionId);
        if (preview != null) preview.dispose();
    }

    @Override public java.util.concurrent.CompletionStage<Void> beforeForegroundAction(String sessionId) {
        Preview preview = previews.get(sessionId);
        return preview == null ? java.util.concurrent.CompletableFuture.completedFuture(null)
                : fx.call(() -> {
                    if (preview.stage != null) preview.stage.hide();
                    return null;
                });
    }

    @Override public void afterForegroundAction(String sessionId) {
        Preview preview = previews.get(sessionId);
        if (preview != null) fx.dispatchLater(() -> {
            if (!preview.disposed.get() && preview.stage != null && !preview.stage.isShowing())
                preview.stage.show();
        });
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        for (String id : previews.keySet()) closed(id);
        scaler.shutdownNow();
    }

    private final class Preview {
        final DesktopSessionOwner owner;
        final DesktopSessionInfo info;
        final DesktopSessionService service;
        final int position;
        final AtomicBoolean disposed = new AtomicBoolean();
        final AtomicBoolean scaleQueued = new AtomicBoolean();
        final AtomicBoolean renderQueued = new AtomicBoolean();
        final AtomicReference<DesktopFrame> pending = new AtomicReference<>();
        final AtomicReference<DesktopFrame> latestFrame = new AtomicReference<>();
        final AtomicReference<ViewportSize> viewportSize =
                new AtomicReference<>(new ViewportSize(960, 540));
        final AtomicReference<DesktopPreviewScaler.ScaledFrame> scaled = new AtomicReference<>();
        final AtomicReference<DesktopSessionState> lastState = new AtomicReference<>();
        final AtomicReference<DesktopActionEvent> lastAction = new AtomicReference<>();
        final DesktopPreviewFrameGate frameGate = new DesktopPreviewFrameGate();
        final AtomicReference<Flow.Subscription> frameSubscription = new AtomicReference<>();
        final AtomicReference<Flow.Subscription> stateSubscription = new AtomicReference<>();
        final AtomicReference<Flow.Subscription> actionSubscription = new AtomicReference<>();
        final Flow.Subscriber<DesktopFrame> frameSubscriber = new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription subscription) {
                subscribe(frameSubscription, subscription);
            }
            @Override public void onNext(DesktopFrame frame) {
                if (disposed.get() || !frameGate.accepts(frame.capturedAtMillis(),
                        frameGate.version())) return;
                latestFrame.set(frame);
                enqueueFrame(frame);
                scheduleScale();
            }
            @Override public void onError(Throwable failure) { status("预览已停止: " + failure.getMessage()); }
            @Override public void onComplete() {}
        };
        final Flow.Subscriber<DesktopSessionState> stateSubscriber = new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription subscription) {
                subscribe(stateSubscription, subscription);
            }
            @Override public void onNext(DesktopSessionState state) {
                if (disposed.get()) return;
                if (state.kind() == DesktopSessionState.Kind.PAUSED
                        || state.kind() == DesktopSessionState.Kind.CLOSED) {
                    frameGate.pause(state.atMillis());
                    pending.set(null);
                    latestFrame.set(null);
                    scaled.set(null);
                }
                lastState.set(state);
                fx.dispatchLater(() -> applyState(state));
            }
            @Override public void onError(Throwable failure) { status("状态不可用: " + failure.getMessage()); }
            @Override public void onComplete() {}
        };
        final Flow.Subscriber<DesktopActionEvent> actionSubscriber = new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription subscription) {
                subscribe(actionSubscription, subscription);
            }
            @Override public void onNext(DesktopActionEvent event) {
                if (disposed.get()) return;
                lastAction.set(event);
                fx.dispatchLater(() -> applyAction(event));
            }
            @Override public void onError(Throwable failure) { status("操作状态不可用: " + failure.getMessage()); }
            @Override public void onComplete() {}
        };
        Stage stage;
        DesktopPreviewController view;
        DesktopSessionState.Kind currentState = DesktopSessionState.Kind.PAUSED;
        boolean controlGranted;
        boolean takeoverPending;
        long shownGeneration;
        volatile long lastScaleNanos;

        Preview(DesktopSessionOwner owner, DesktopSessionInfo info,
                DesktopSessionService service, int position) {
            this.owner = owner;
            this.info = info;
            this.service = service;
            this.position = position;
            controlGranted = info.controlGranted();
        }

        void subscribe(AtomicReference<Flow.Subscription> slot, Flow.Subscription subscription) {
            if (!slot.compareAndSet(null, subscription) || disposed.get()) {
                subscription.cancel();
                return;
            }
            subscription.request(Long.MAX_VALUE);
        }

        void show() {
            if (disposed.get() || stage != null || !previews.containsKey(info.sessionId())) return;
            view = new DesktopPreviewController();
            VBox content = view.load();
            stage = new Stage(StageStyle.TRANSPARENT);
            stage.setTitle("JavaClaw · " + info.target().application());
            stage.setAlwaysOnTop(true);
            Scene scene = new Scene(content, DesktopPreviewChrome.NORMAL_WIDTH,
                    DesktopPreviewChrome.NORMAL_HEIGHT, Color.TRANSPARENT);
            for (String stylesheet : new String[] {"/css/chat.css", "/css/desktop-preview.css"}) {
                scene.getStylesheets().add(Objects.requireNonNull(
                        DesktopPreviewWindow.class.getResource(stylesheet)).toExternalForm());
            }
            stage.setScene(scene);
            view.configure(stage, this::requestStop, this::requestTakeover, this::resizePreview);
            view.target(info.target().application(), info.target().title());
            view.takeoverDisabled(!controlGranted);
            stage.setOnCloseRequest(event -> requestStop());
            stage.show();
            staggerPosition();
            DesktopSessionState state = lastState.get();
            if (state != null) applyState(state);
            DesktopActionEvent action = lastAction.get();
            if (action != null) applyAction(action);
            queueRender();
        }

        void requestTakeover() {
            if (disposed.get() || takeoverPending) return;
            takeoverPending = true;
            view.takeoverDisabled(true);
            Thread.startVirtualThread(() -> {
                try {
                    service.authorizeForeground(owner, info.sessionId()).whenComplete(this::takeoverFinished);
                } catch (RuntimeException failure) {
                    takeoverFinished(false, failure);
                }
            });
        }

        void takeoverFinished(Boolean approved, Throwable failure) {
            fx.dispatchLater(() -> {
                if (disposed.get() || stage == null) return;
                takeoverPending = false;
                view.takeoverDisabled(!controlGranted);
                view.status(failure != null ? "切换失败: " + failure.getMessage()
                        : Boolean.TRUE.equals(approved) ? "本会话可前台接管"
                        : "前台接管不可用，请检查设置与系统权限");
                refreshTitle(shownGeneration);
            });
        }

        void resizePreview(int width, int height) {
            ViewportSize size = new ViewportSize(width, height);
            if (size.equals(viewportSize.getAndSet(size))) return;
            DesktopFrame frame = latestFrame.get();
            if (frame == null || disposed.get()) return;
            enqueueFrame(frame);
            scheduleScale();
        }

        void enqueueFrame(DesktopFrame frame) {
            pending.accumulateAndGet(frame, (queued, incoming) -> queued != null
                    && queued.capturedAtMillis() > incoming.capturedAtMillis() ? queued : incoming);
        }

        void staggerPosition() {
            Rectangle2D bounds = Screen.getPrimary().getVisualBounds();
            double width = Math.max(DesktopPreviewChrome.NORMAL_WIDTH, stage.getWidth());
            double height = Math.max(DesktopPreviewChrome.NORMAL_HEIGHT, stage.getHeight());
            double gap = 12;
            int columns = Math.max(1, (int) ((bounds.getWidth() - gap) / (width + gap)));
            int rows = Math.max(1, (int) ((bounds.getHeight() - gap) / (height + gap)));
            int slot = Math.floorMod(position, columns * rows);
            int column = slot % columns;
            int row = slot / columns;
            stage.setX(Math.max(bounds.getMinX() + gap,
                    bounds.getMaxX() - gap - width - column * (width + gap)));
            stage.setY(Math.max(bounds.getMinY() + gap,
                    Math.min(bounds.getMaxY() - gap - height,
                            bounds.getMinY() + gap + row * (height + gap))));
        }

        void dispose() {
            if (!disposed.compareAndSet(false, true)) return;
            cancel(frameSubscription);
            cancel(stateSubscription);
            cancel(actionSubscription);
            pending.set(null);
            latestFrame.set(null);
            scaled.set(null);
            frameGate.invalidate();
            fx.dispatchLater(this::hide);
        }

        void cancel(AtomicReference<Flow.Subscription> slot) {
            Flow.Subscription subscription = slot.getAndSet(null);
            if (subscription != null) subscription.cancel();
        }

        void hide() {
            if (stage == null) return;
            Stage previous = stage;
            stage = null;
            previous.hide();
        }

        void stopSession() {
            try { service.closeSession(owner, info.sessionId()); }
            catch (IllegalStateException | SecurityException ignored) {
                // Closing an already disposed window during shutdown is harmless.
            }
        }

        void requestStop() {
            DesktopPreviewWindow.this.closed(info.sessionId());
            Thread.startVirtualThread(this::stopSession);
        }

        void status(String text) {
            fx.dispatchLater(() -> {
                if (!disposed.get() && stage != null) view.status(text);
            });
        }

        void applyState(DesktopSessionState state) {
            if (disposed.get() || stage == null || state != lastState.get()) return;
            currentState = state.kind();
            view.status(state.detail());
            view.state(stateName(state.kind()), state.kind() == DesktopSessionState.Kind.LIVE
                    || state.kind() == DesktopSessionState.Kind.FOREGROUND_READY,
                    state.kind() == DesktopSessionState.Kind.FOREGROUND_REQUIRED);
            if (state.kind() == DesktopSessionState.Kind.PAUSED
                    || state.kind() == DesktopSessionState.Kind.CLOSED) {
                frameGate.invalidate();
                pending.set(null);
                latestFrame.set(null);
                scaled.set(null);
                view.image(null);
            } else {
                render(scaled.get());
            }
            refreshTitle(shownGeneration);
        }

        void applyAction(DesktopActionEvent event) {
            if (disposed.get() || stage == null || event != lastAction.get()) return;
            String result = event.phase() == DesktopActionEvent.Phase.STARTED
                    ? "执行中" : resultName(event.result());
            view.action("最近操作：" + actionName(event.kind()) + " · " + result
                    + " · 窗口代次 " + event.windowGeneration());
        }

        void scheduleScale() {
            if (disposed.get() || closed.get() || !scaleQueued.compareAndSet(false, true)) return;
            long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - lastScaleNanos);
            long delay = Math.max(0, FRAME_INTERVAL_MILLIS - elapsed);
            try { scaler.schedule(this::scaleLatest, delay, TimeUnit.MILLISECONDS); }
            catch (java.util.concurrent.RejectedExecutionException stopped) { scaleQueued.set(false); }
        }

        void scaleLatest() {
            try {
                if (disposed.get()) return;
                DesktopFrame frame = pending.getAndSet(null);
                if (frame == null) return;
                long epoch = frameGate.version();
                if (!frameGate.accepts(frame.capturedAtMillis(), epoch)) return;
                ViewportSize size = viewportSize.get();
                DesktopPreviewScaler.ScaledFrame ready =
                        DesktopPreviewScaler.scale(frame, size.width(), size.height());
                lastScaleNanos = System.nanoTime();
                if (disposed.get() || !frameGate.accepts(frame.capturedAtMillis(), epoch)) return;
                if (!size.equals(viewportSize.get())) {
                    pending.compareAndSet(null, latestFrame.get());
                    return;
                }
                scaled.set(ready);
                queueRender();
            } finally {
                scaleQueued.set(false);
                if (pending.get() != null && !disposed.get()) scheduleScale();
            }
        }

        void queueRender() {
            if (disposed.get() || !renderQueued.compareAndSet(false, true)) return;
            fx.dispatchLater(() -> {
                DesktopPreviewScaler.ScaledFrame latest = scaled.get();
                try { render(latest); }
                finally {
                    renderQueued.set(false);
                    if (!disposed.get() && scaled.get() != latest) queueRender();
                }
            });
        }

        void render(DesktopPreviewScaler.ScaledFrame frame) {
            if (frame == null || disposed.get() || stage == null
                    || currentState == DesktopSessionState.Kind.PAUSED
                    || currentState == DesktopSessionState.Kind.CLOSED
                    || !frameGate.accepts(frame.capturedAtMillis(), frameGate.version())
                    || frame != scaled.get()) return;
            WritableImage next = new WritableImage(frame.width(), frame.height());
            next.getPixelWriter().setPixels(0, 0, frame.width(), frame.height(),
                    PixelFormat.getByteBgraPreInstance(), frame.bgra(), 0, frame.width() * 4);
            view.image(next);
            if (shownGeneration != frame.generation()) {
                shownGeneration = frame.generation();
                refreshTitle(frame.generation());
            }
            if (currentState == DesktopSessionState.Kind.LIVE)
                view.status("窗口代次 " + frame.generation());
        }

        void refreshTitle(long expectedGeneration) {
            Thread.startVirtualThread(() -> {
                try {
                    DesktopSessionInfo currentInfo = service.info(owner, info.sessionId());
                    DesktopTarget current = currentInfo.target();
                    fx.dispatchLater(() -> {
                        if (disposed.get() || stage == null || shownGeneration != expectedGeneration) return;
                        view.target(current.application(), current.title()
                                + ((current.flags() & DesktopTarget.POPUP) != 0 ? " · 弹窗" : ""));
                        controlGranted = currentInfo.controlGranted();
                        view.takeoverDisabled(!controlGranted || takeoverPending);
                        stage.setTitle("JavaClaw · " + current.application());
                    });
                } catch (IllegalStateException | SecurityException ignored) {
                    fx.dispatchLater(() -> {
                        if (disposed.get() || stage == null) return;
                        controlGranted = false;
                        view.takeoverDisabled(true);
                    });
                }
            });
        }
    }

    private record ViewportSize(int width, int height) {}

    private static String actionName(DesktopAction.Kind kind) {
        return switch (kind) {
            case CLICK -> "点击";
            case TYPE -> "输入";
            case KEY -> "按键";
            case SCROLL -> "滚动";
        };
    }

    private static String resultName(DesktopActionResult.Status status) {
        return switch (status) {
            case ACCEPTED -> "输入已受理·效果待观察";
            case VERIFIED -> "已验证";
            case UNKNOWN -> "结果未知";
            case UNSUPPORTED -> "需前台接管";
            case STALE_FRAME -> "画面已过期";
            case DENIED -> "未获授权";
            case FAILED -> "失败";
        };
    }

    private static String stateName(DesktopSessionState.Kind state) {
        return switch (state) {
            case LIVE -> "实时";
            case PAUSED -> "已暂停";
            case FOREGROUND_REQUIRED -> "需前台接管";
            case FOREGROUND_READY -> "自动前台操作中";
            case CLOSED -> "已关闭";
        };
    }
}
