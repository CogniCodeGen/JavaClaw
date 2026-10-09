package com.javaclaw.desktop.nativebridge;

import com.javaclaw.desktop.api.*;
import com.javaclaw.desktop.ffm.SystemDesktopApi;
import com.javaclaw.desktop.ffm.SystemDesktopSession;
import com.javaclaw.desktop.ffm.ThreadBoundDesktopApi;
import com.javaclaw.desktop.ffm.DesktopSemanticAction;
import com.javaclaw.desktop.ffm.macos.MacDesktopApi;
import com.javaclaw.desktop.ffm.windows.WindowsDesktopApi;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/** Java facade over public operating-system functions reached directly through JDK FFM. */
public final class DesktopBridge {
    private final SystemDesktopApi system;

    public DesktopBridge(String platform) throws IOException {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        try {
            system = switch (platform) {
                case "macos" -> {
                    if (!os.startsWith("mac")) throw new IllegalArgumentException("当前系统不是 macOS");
                    yield new ThreadBoundDesktopApi(new MacDesktopApi());
                }
                case "windows" -> {
                    if (!os.startsWith("windows")) throw new IllegalArgumentException("当前系统不是 Windows");
                    yield new ThreadBoundDesktopApi(new WindowsDesktopApi());
                }
                default -> throw new IllegalArgumentException("不支持的桌面平台: " + platform);
            };
        } catch (RuntimeException | LinkageError failure) {
            throw new IOException("系统桌面接口不可用: " + failure.getMessage(), failure);
        }
    }

    DesktopBridge(SystemDesktopApi system) { this.system = Objects.requireNonNull(system); }

    public boolean supportsPublicApi() { return true; }
    public DesktopAvailability probe(String providerId) {
        return probe(providerId, DesktopInputPolicy.BACKGROUND_STRICT);
    }
    public DesktopAvailability probe(String providerId, DesktopInputPolicy policy) {
        return system.availability(false, Objects.requireNonNull(policy, "policy"));
    }
    public DesktopAvailability requestPermissions(String providerId) {
        return requestPermissions(providerId, DesktopInputPolicy.BACKGROUND_STRICT);
    }
    public DesktopAvailability requestPermissions(String providerId, DesktopInputPolicy policy) {
        return system.availability(true, Objects.requireNonNull(policy, "policy"));
    }
    public List<NativeWindow> listWindows() { return List.copyOf(system.windows()); }
    public DesktopApplicationCatalog listApplications() { return system.applications(); }
    public DesktopApplicationLaunch launchApplication(String application) { return system.launch(application); }
    public Optional<Boolean> windowExists(long processId, long windowId, long processInstanceId) {
        if (processId <= 0 || windowId == 0 || processInstanceId == 0) return Optional.empty();
        return system.windowExists(processId, windowId, processInstanceId);
    }

    public Session open(NativeWindow target) {
        if (target.processId() <= 0 || target.windowId() == 0 || target.processInstanceId() == 0)
            throw new IllegalArgumentException("目标窗口缺少进程实例身份，请重新发现");
        SystemDesktopSession opened = Objects.requireNonNull(system.open(target));
        Session session = new Session(target, opened);
        try { current(session); return session; }
        catch (RuntimeException invalid) { session.close(); throw invalid; }
    }

    public NativeWindow current(Session session) {
        requireOpen(session);
        NativeWindow current = session.platform.current();
        if (!sameWindow(session.opened, current))
            throw new IllegalStateException("目标窗口或进程实例已变化，请重新发现");
        return current;
    }

    public Optional<DesktopFrame> poll(Session session, String targetId, int timeoutMillis) {
        return pollCaptured(session, targetId, timeoutMillis).map(CapturedFrame::frame);
    }

    Optional<CapturedFrame> pollCaptured(Session session, String targetId, int timeoutMillis) {
        current(session);
        Optional<DesktopFrame> frame = session.platform.capture(targetId, timeoutMillis);
        current(session);
        return frame.map(value -> {
            if (!targetId.equals(value.targetId()))
                throw new IllegalStateException("采集画面不属于指定目标");
            return new CapturedFrame(value, session.opened.windowId());
        });
    }

    record CapturedFrame(DesktopFrame frame, long windowId) { }

    public List<DesktopElement> elements(Session session, DesktopFrame frame) {
        current(session);
        return List.copyOf(session.platform.elements(frame));
    }
    public String elementDiagnostics(Session session) {
        requireOpen(session);
        return session.platform.diagnostics();
    }
    public Optional<Boolean> isTargetActive(Session session) {
        current(session);
        return session.platform.targetActive();
    }
    public void prepareForeground(Session session) {
        current(session);
        session.platform.prepareForeground();
    }
    public void restoreForeground(Session session) {
        if (session != null && !session.closed) session.platform.restoreForeground();
    }

    public DesktopActionResult perform(Session session, DesktopAction action, boolean foreground) {
        Objects.requireNonNull(action, "action");
        try { current(session); }
        catch (RuntimeException unavailable) {
            return new DesktopActionResult(DesktopActionResult.Status.STALE_FRAME,
                    "输入前目标窗口不可用，未发送输入", action.windowGeneration());
        }
        if (!foreground) {
            try {
                if (DesktopSemanticAction.admit(action).isEmpty())
                    return new DesktopActionResult(DesktopActionResult.Status.UNSUPPORTED,
                            "操作没有公开的后台语义路径，未发送输入", action.windowGeneration());
            } catch (IllegalArgumentException invalid) {
                return new DesktopActionResult(DesktopActionResult.Status.STALE_FRAME,
                        "辅助功能元素不属于有效观察，未发送输入", action.windowGeneration());
            }
        }
        // A provider exception after entering perform can follow partial OS input.
        // Propagate it so ManagedSession retains UNKNOWN and prevents automatic replay.
        DesktopActionResult result = Objects.requireNonNull(session.platform.perform(action, foreground));
        if (result.dispatchAttempted() && result.delivery() == DesktopActionResult.Delivery.MAYBE_SENT
                && result.status() != DesktopActionResult.Status.UNKNOWN)
            return new DesktopActionResult(DesktopActionResult.Status.UNKNOWN, result.detail(),
                    result.windowGeneration(), result.mode(), DesktopActionResult.Reason.DELIVERY_UNCERTAIN,
                    true, result.observationId(), DesktopActionResult.NextStep.OBSERVE);
        return result;
    }

    public void close(Session session) { if (session != null) session.close(); }

    private static void requireOpen(Session session) {
        if (session == null || session.closed) throw new IllegalStateException("桌面会话已关闭");
    }
    private static boolean sameWindow(NativeWindow first, NativeWindow second) {
        return second != null && first.processId() == second.processId()
                && first.processInstanceId() == second.processInstanceId() && first.windowId() == second.windowId();
    }

    public static final class Session implements AutoCloseable {
        private final NativeWindow opened;
        private final SystemDesktopSession platform;
        private volatile boolean closed;
        private Session(NativeWindow opened, SystemDesktopSession platform) {
            this.opened = opened; this.platform = platform;
        }
        @Override public synchronized void close() {
            if (closed) return;
            closed = true;
            platform.close();
        }
    }

    public static OptionalInt publicElementIndexFor(DesktopAction action) {
        return parseElementIndex(action);
    }

    /** Element tokens are unsigned 32-bit observation identities, not Java list positions. */
    public static String elementId(int index) {
        return "e" + Integer.toUnsignedString(index);
    }

    private static OptionalInt parseElementIndex(DesktopAction action) {
        String id = action.elementId();
        if (id.isBlank()) return OptionalInt.empty();
        int separator = id.lastIndexOf(':');
        if (separator >= 0) {
            if (!id.substring(0, separator).equals(action.observationId()))
                throw new IllegalArgumentException("辅助功能元素不属于当前观察");
            id = id.substring(separator + 1);
        }
        if (!id.startsWith("e")) return OptionalInt.empty();
        String digits = id.substring(1);
        if (digits.isEmpty() || digits.length() > 10
                || !digits.chars().allMatch(c -> c >= '0' && c <= '9'))
            throw new IllegalArgumentException("辅助功能元素 token 无效");
        long index = Long.parseLong(digits);
        if (index == 0 || index > 0xffff_ffffL)
            throw new IllegalArgumentException("辅助功能元素 token 超出范围");
        return OptionalInt.of((int) index);
    }

    public record NativeWindow(long processId, long windowId, long processInstanceId,
                               int x, int y, int width,
                               int height, int flags, String application, String title,
                               String applicationId, long parentWindowId, int relationKind) {
        public NativeWindow(long processId, long windowId, long processInstanceId,
                int x, int y, int width, int height, int flags, String application, String title,
                String applicationId) {
            this(processId, windowId, processInstanceId, x, y, width, height, flags,
                    application, title, applicationId, 0, 0);
        }
    }
}
