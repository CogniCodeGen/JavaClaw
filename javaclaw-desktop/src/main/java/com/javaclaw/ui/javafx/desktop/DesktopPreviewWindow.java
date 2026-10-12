package com.javaclaw.ui.javafx.desktop;

import com.javaclaw.desktop.api.DesktopActionEvent;
import com.javaclaw.desktop.api.DesktopFrame;
import com.javaclaw.desktop.api.DesktopSessionInfo;
import com.javaclaw.desktop.api.DesktopSessionObserver;
import com.javaclaw.desktop.api.DesktopSessionOwner;
import com.javaclaw.desktop.api.DesktopSessionService;
import com.javaclaw.desktop.api.DesktopSessionState;
import com.javaclaw.desktop.api.DesktopTarget;
import com.javaclaw.desktop.api.DesktopVirtualInputState;
import com.javaclaw.platform.fx.FxDispatcher;
import java.awt.image.BufferedImage;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Owner-scoped subscriptions and a passive native preview; never requests JavaFX focus. */
public final class DesktopPreviewWindow implements DesktopSessionObserver, AutoCloseable {
    private static final long FRAME_INTERVAL_MILLIS = 150;
    private static final Logger log = LoggerFactory.getLogger(DesktopPreviewWindow.class);
    private final Consumer<Runnable> ui;
    private final DesktopPreviewHost.Factory hostFactory;
    private final FxDispatcher fx;
    private final Map<String, Preview> previews = new ConcurrentHashMap<>();
    private final AtomicInteger nextPosition = new AtomicInteger();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ScheduledExecutorService renderer = Executors.newSingleThreadScheduledExecutor(task ->
            Thread.ofPlatform().daemon().name("desktop-preview-renderer").unstarted(task));

    /** Keep the composition-root signature; JavaFX and AWT never synchronously wait on each other. */
    public DesktopPreviewWindow(FxDispatcher fx) {
        this(JavaDesktopPreviewHost::dispatch, JavaDesktopPreviewHost::new, Objects.requireNonNull(fx));
    }

    DesktopPreviewWindow(Consumer<Runnable> ui, DesktopPreviewHost.Factory hostFactory) {
        this(ui, hostFactory, null);
    }

    private DesktopPreviewWindow(Consumer<Runnable> ui, DesktopPreviewHost.Factory hostFactory, FxDispatcher fx) {
        this.ui = Objects.requireNonNull(ui);
        this.hostFactory = Objects.requireNonNull(hostFactory);
        this.fx = fx;
        renderer.scheduleAtFixedRate(() -> previews.values().forEach(Preview::expireInput),
                100, 100, TimeUnit.MILLISECONDS);
    }

    /** Persistent after the final preview closes, for mixed AWT/JavaFX shutdown coordination. */
    public static boolean wasAwtUsed() { return JavaDesktopPreviewHost.wasAwtUsed(); }

    @Override public void opened(DesktopSessionOwner owner, DesktopSessionInfo info,
            DesktopSessionService service) {
        if (closed.get()) return;
        Preview preview = new Preview(owner, info, service, nextPosition.getAndIncrement());
        if (previews.putIfAbsent(info.sessionId(), preview) != null) return;
        if (closed.get()) { closed(info.sessionId()); return; }
        try {
            service.frames(owner, info.sessionId()).subscribe(preview.frameSubscriber);
            service.states(owner, info.sessionId()).subscribe(preview.stateSubscriber);
            service.actions(owner, info.sessionId()).subscribe(preview.actionSubscriber);
            service.virtualInputs(owner, info.sessionId()).subscribe(preview.inputSubscriber);
            ui.accept(preview::show);
        } catch (RuntimeException failure) {
            closed(info.sessionId());
            throw failure;
        }
    }

    @Override public void closed(String sessionId) {
        Preview preview = previews.remove(sessionId);
        if (preview != null) preview.dispose();
    }

    @Override public CompletionStage<Void> beforeForegroundAction(String sessionId) {
        Preview preview = previews.get(sessionId);
        if (preview == null) return CompletableFuture.completedFuture(null);
        CompletableFuture<Void> hidden = new CompletableFuture<>();
        ui.accept(() -> {
            try {
                if (preview.host != null) preview.host.hide();
                hidden.complete(null);
            } catch (RuntimeException failure) { hidden.completeExceptionally(failure); }
        });
        return hidden;
    }

    @Override public void afterForegroundAction(String sessionId) {
        Preview preview = previews.get(sessionId);
        if (preview != null) ui.accept(() -> {
            if (!preview.disposed.get() && preview.host != null) preview.host.show();
        });
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        for (String id : previews.keySet()) closed(id);
        renderer.shutdownNow();
    }

    private final class Preview {
        final DesktopSessionOwner owner;
        final DesktopSessionInfo info;
        final DesktopSessionService service;
        final int position;
        final AtomicBoolean disposed = new AtomicBoolean();
        final AtomicBoolean conversionQueued = new AtomicBoolean();
        final AtomicBoolean renderQueued = new AtomicBoolean();
        final AtomicReference<DesktopFrame> pending = new AtomicReference<>();
        final AtomicReference<ReadyFrame> ready = new AtomicReference<>();
        final AtomicReference<DesktopSessionState> lastState = new AtomicReference<>();
        final DesktopPreviewFeedback feedback = new DesktopPreviewFeedback();
        final AtomicBoolean feedbackQueued = new AtomicBoolean();
        final AtomicLong feedbackVersion = new AtomicLong();
        final DesktopPreviewFrameGate frameGate = new DesktopPreviewFrameGate();
        final AtomicReference<Flow.Subscription> frameSubscription = new AtomicReference<>();
        final AtomicReference<Flow.Subscription> stateSubscription = new AtomicReference<>();
        final AtomicReference<Flow.Subscription> actionSubscription = new AtomicReference<>();
        final AtomicReference<Flow.Subscription> inputSubscription = new AtomicReference<>();
        DesktopPreviewHost host;
        DesktopSessionState.Kind currentState = DesktopSessionState.Kind.PAUSED;
        boolean controlGranted;
        boolean takeoverPending;
        long shownGeneration;
        volatile long lastConversionNanos;
        volatile DesktopVirtualInputState displayedInput;

        final Flow.Subscriber<DesktopFrame> frameSubscriber = new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription subscription) {
                subscribe(frameSubscription, subscription);
            }
            @Override public void onNext(DesktopFrame frame) {
                if (disposed.get() || !frameGate.accepts(frame.capturedAtMillis(), frameGate.version())) return;
                pending.accumulateAndGet(frame, (queued, incoming) -> queued != null
                        && queued.capturedAtMillis() > incoming.capturedAtMillis() ? queued : incoming);
                scheduleConversion();
            }
            @Override public void onError(Throwable failure) { status("预览已停止: " + failure.getMessage()); }
            @Override public void onComplete() { }
        };
        final Flow.Subscriber<DesktopSessionState> stateSubscriber = new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription subscription) {
                subscribe(stateSubscription, subscription);
            }
            @Override public void onNext(DesktopSessionState state) {
                if (disposed.get() || !info.sessionId().equals(state.sessionId())) return;
                DesktopSessionState newest = lastState.accumulateAndGet(state, (previous, incoming) ->
                        previous != null && previous.atMillis() > incoming.atMillis() ? previous : incoming);
                if (newest != state) return;
                if (state.kind() == DesktopSessionState.Kind.PAUSED || state.kind() == DesktopSessionState.Kind.CLOSED) {
                    frameGate.pause(state.atMillis());
                    pending.set(null);
                    ready.set(null);
                    feedback.clearPointer();
                }
                ui.accept(() -> applyState(state));
            }
            @Override public void onError(Throwable failure) { status("状态不可用: " + failure.getMessage()); }
            @Override public void onComplete() { }
        };
        final Flow.Subscriber<DesktopActionEvent> actionSubscriber = new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription subscription) {
                subscribe(actionSubscription, subscription);
            }
            @Override public void onNext(DesktopActionEvent event) {
                if (disposed.get() || !info.sessionId().equals(event.sessionId())) return;
                if (feedback.accept(event, System.nanoTime())) queueFeedback();
            }
            @Override public void onError(Throwable failure) { status("操作状态不可用: " + failure.getMessage()); }
            @Override public void onComplete() { }
        };
        final Flow.Subscriber<DesktopVirtualInputState> inputSubscriber = new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription subscription) {
                subscribe(inputSubscription, subscription);
            }
            @Override public void onNext(DesktopVirtualInputState input) {
                if (disposed.get() || !info.sessionId().equals(input.sessionId())) return;
                DesktopSessionState state = lastState.get();
                if (state != null && (state.kind() == DesktopSessionState.Kind.PAUSED
                        || state.kind() == DesktopSessionState.Kind.CLOSED)) return;
                long epoch = frameGate.version();
                if (!frameGate.accepts(input.atMillis(), epoch)) return;
                if (feedback.accept(input, System.nanoTime())) queueFeedback();
            }
            @Override public void onError(Throwable failure) { status("输入状态不可用: " + failure.getMessage()); }
            @Override public void onComplete() { }
        };

        Preview(DesktopSessionOwner owner, DesktopSessionInfo info, DesktopSessionService service, int position) {
            this.owner = owner;
            this.info = info;
            this.service = service;
            this.position = position;
            controlGranted = info.controlGranted();
        }

        void subscribe(AtomicReference<Flow.Subscription> slot, Flow.Subscription subscription) {
            if (!slot.compareAndSet(null, subscription)) { subscription.cancel(); return; }
            if (disposed.get()) { cancel(slot); return; }
            subscription.request(Long.MAX_VALUE);
        }

        void show() {
            if (disposed.get() || host != null || previews.get(info.sessionId()) != this) return;
            try {
                host = hostFactory.create(info.target().application(), info.target().title(), position,
                        this::requestStop, this::requestTakeover, this::requestManualInput);
                host.takeoverEnabled(controlGranted);
                host.show();
                DesktopSessionState state = lastState.get();
                if (state != null) applyState(state);
                render(ready.get());
                refreshFeedback();
            } catch (RuntimeException failure) {
                if (host != null) host.close();
                host = null;
                // Observation and policy remain usable without a native display host.
                log.warn("无法创建被动桌面预览: {}", failure.getMessage());
            }
        }

        void requestTakeover() {
            if (disposed.get() || takeoverPending || !controlGranted) return;
            takeoverPending = true;
            host.takeoverEnabled(false);
            Thread.startVirtualThread(() -> {
                try { service.authorizeForeground(owner, info.sessionId()).whenComplete(this::takeoverFinished); }
                catch (RuntimeException failure) { takeoverFinished(false, failure); }
            });
        }

        void requestManualInput() {
            // An explicit click is the only path from the passive host to an activating FX window.
            if (fx != null && !disposed.get()) fx.dispatchLater(() -> {
                if (!disposed.get()) DesktopManualInputPanel.open(owner, info, service);
            });
        }

        void takeoverFinished(Boolean approved, Throwable failure) {
            ui.accept(() -> {
                if (disposed.get() || host == null) return;
                takeoverPending = false;
                host.takeoverEnabled(controlGranted);
                host.status(failure != null ? "切换失败: " + failure.getMessage()
                        : Boolean.TRUE.equals(approved) ? "本会话可前台接管"
                        : "前台接管不可用，请检查设置与系统权限");
                refreshTitle(shownGeneration);
            });
        }

        void dispose() {
            if (!disposed.compareAndSet(false, true)) return;
            frameGate.invalidate();
            cancel(frameSubscription); cancel(stateSubscription); cancel(actionSubscription); cancel(inputSubscription);
            pending.set(null); ready.set(null); feedback.clearPointer(); displayedInput = null;
            DesktopManualInputPanel.closeSession(info.sessionId());
            ui.accept(() -> {
                if (host != null) { host.close(); host = null; }
            });
        }

        void cancel(AtomicReference<Flow.Subscription> slot) {
            Flow.Subscription subscription = slot.getAndSet(null);
            if (subscription != null) subscription.cancel();
        }

        void requestStop() {
            DesktopPreviewWindow.this.closed(info.sessionId());
            Thread.startVirtualThread(() -> {
                try { service.closeSession(owner, info.sessionId()); }
                catch (IllegalStateException | SecurityException ignored) {
                    // Closing an already invalidated session is harmless during shutdown.
                }
            });
        }

        void status(String text) {
            ui.accept(() -> { if (!disposed.get() && host != null) host.status(text); });
        }

        void applyState(DesktopSessionState state) {
            if (disposed.get() || host == null || state != lastState.get()) return;
            currentState = state.kind();
            host.status(state.detail());
            host.state(stateName(state.kind()), state.kind() == DesktopSessionState.Kind.FOREGROUND_REQUIRED);
            if (state.kind() == DesktopSessionState.Kind.PAUSED || state.kind() == DesktopSessionState.Kind.CLOSED)
                host.clear();
            else render(ready.get());
            refreshFeedback();
            refreshTitle(shownGeneration);
        }

        void queueFeedback() {
            feedbackVersion.incrementAndGet();
            if (disposed.get() || !feedbackQueued.compareAndSet(false, true)) return;
            ui.accept(() -> {
                long version = feedbackVersion.get();
                try { refreshFeedback(); }
                finally {
                    feedbackQueued.set(false);
                    if (!disposed.get() && feedbackVersion.get() != version) queueFeedback();
                }
            });
        }

        void expireInput() {
            if (!disposed.get() && displayedInput != null
                    && feedback.visibleInput(System.nanoTime()) == null) queueFeedback();
        }

        void refreshFeedback() {
            if (disposed.get() || host == null) return;
            String text = feedback.text();
            if (text != null) host.action("最近操作：" + text);
            DesktopVirtualInputState next = currentState == DesktopSessionState.Kind.PAUSED
                    || currentState == DesktopSessionState.Kind.CLOSED ? null
                    : feedback.visibleInput(System.nanoTime());
            if (next != displayedInput) {
                displayedInput = next;
                host.input(next);
            }
        }

        void scheduleConversion() {
            if (disposed.get() || closed.get() || !conversionQueued.compareAndSet(false, true)) return;
            long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - lastConversionNanos);
            try { renderer.schedule(this::convertLatest, Math.max(0, FRAME_INTERVAL_MILLIS - elapsed), TimeUnit.MILLISECONDS); }
            catch (java.util.concurrent.RejectedExecutionException stopped) { conversionQueued.set(false); }
        }

        void convertLatest() {
            try {
                if (disposed.get()) return;
                DesktopFrame frame = pending.getAndSet(null);
                if (frame == null) return;
                long epoch = frameGate.version();
                if (!frameGate.accepts(frame.capturedAtMillis(), epoch)) return;
                BufferedImage pixels = DesktopPreviewImage.convert(frame);
                lastConversionNanos = System.nanoTime();
                if (disposed.get() || !frameGate.accepts(frame.capturedAtMillis(), epoch)) return;
                ready.set(new ReadyFrame(frame, pixels, epoch));
                queueRender();
            } finally {
                conversionQueued.set(false);
                if (pending.get() != null && !disposed.get()) scheduleConversion();
            }
        }

        void queueRender() {
            if (disposed.get() || !renderQueued.compareAndSet(false, true)) return;
            ui.accept(() -> {
                ReadyFrame latest = ready.get();
                try { render(latest); }
                finally {
                    renderQueued.set(false);
                    if (!disposed.get() && ready.get() != latest) queueRender();
                }
            });
        }

        void render(ReadyFrame readyFrame) {
            if (readyFrame == null || disposed.get() || host == null || readyFrame != ready.get()
                    || currentState == DesktopSessionState.Kind.PAUSED || currentState == DesktopSessionState.Kind.CLOSED
                    || !frameGate.accepts(readyFrame.frame().capturedAtMillis(), readyFrame.epoch())) return;
            DesktopFrame frame = readyFrame.frame();
            feedback.generation(frame.windowGeneration());
            host.frame(frame, readyFrame.image());
            refreshFeedback();
            if (shownGeneration != frame.windowGeneration()) {
                shownGeneration = frame.windowGeneration();
                refreshTitle(shownGeneration);
            }
        }

        void refreshTitle(long expectedGeneration) {
            Thread.startVirtualThread(() -> {
                try {
                    DesktopSessionInfo currentInfo = service.info(owner, info.sessionId());
                    DesktopTarget target = currentInfo.target();
                    ui.accept(() -> {
                        if (disposed.get() || host == null || shownGeneration != expectedGeneration) return;
                        host.target(target.application(), target.title()
                                + ((target.flags() & DesktopTarget.POPUP) != 0 ? " · 弹窗" : ""));
                        controlGranted = currentInfo.controlGranted();
                        host.takeoverEnabled(controlGranted && !takeoverPending);
                    });
                } catch (IllegalStateException | SecurityException ignored) {
                    ui.accept(() -> {
                        if (disposed.get() || host == null) return;
                        controlGranted = false;
                        host.takeoverEnabled(false);
                    });
                }
            });
        }
    }

    private record ReadyFrame(DesktopFrame frame, BufferedImage image, long epoch) { }
    private static String stateName(DesktopSessionState.Kind state) {
        return switch (state) {
            case LIVE -> "实时"; case PAUSED -> "已暂停"; case FOREGROUND_REQUIRED -> "需前台接管";
            case FOREGROUND_READY -> "前台模式已授权"; case CLOSED -> "已关闭";
        };
    }
}
