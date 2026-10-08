package com.javaclaw.desktop.nativebridge;

import com.javaclaw.desktop.api.DesktopAction;
import com.javaclaw.desktop.api.DesktopActionResult;
import com.javaclaw.desktop.api.DesktopAvailability;
import com.javaclaw.desktop.api.DesktopApplicationLaunch;
import com.javaclaw.desktop.api.DesktopApplicationLaunchRejectedException;
import com.javaclaw.desktop.api.DesktopApplicationLaunchUncertainException;
import com.javaclaw.desktop.api.DesktopFrame;
import com.javaclaw.desktop.api.DesktopElement;
import com.javaclaw.desktop.nativebridge.generated.desktop_bridge_h;
import com.javaclaw.desktop.nativebridge.generated.jc_desktop_action;
import com.javaclaw.desktop.nativebridge.generated.jc_desktop_frame;
import com.javaclaw.desktop.nativebridge.generated.jc_desktop_element;
import com.javaclaw.desktop.nativebridge.generated.jc_desktop_window;
import com.javaclaw.platform.data.ApplicationHome;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

/** Adapts jextract's generated C binding to the public desktop API. */
public final class DesktopBridge {
    private static final int DETAIL_BYTES = 4096;
    private static Path loadedLibrary;
    private static SymbolLookup librarySymbols;
    private final MethodHandle launchApplication;
    private final MethodHandle resolveApplicationId;
    private final MethodHandle processApplicationId;
    private final MethodHandle listApplications;
    private final MethodHandle performElement;
    private final MethodHandle elementDiagnostics;
    private final String providerId;

    public DesktopBridge(ApplicationHome home, String platform, String libraryName) throws IOException {
        providerId = platform;
        Path fixed = home.runtimeDirectory().resolve("native").resolve(platform).resolve(libraryName);
        Path library = fixed;
        // Source checkout only: load the prebuilt data/native library without compiling at startup.
        if (!Files.isRegularFile(fixed, LinkOption.NOFOLLOW_LINKS)
                && Files.isRegularFile(home.root().resolve("pom.xml"), LinkOption.NOFOLLOW_LINKS)
                && Files.isDirectory(home.root().resolve("src"), LinkOption.NOFOLLOW_LINKS)) {
            Path prebuilt = home.requireManaged(home.dataDirectory().resolve("native")
                    .resolve(platform).resolve(libraryName));
            // Keep existing development builds usable when no prebuilt library is present.
            library = Files.notExists(prebuilt, LinkOption.NOFOLLOW_LINKS)
                    ? home.root().resolve("target/native").resolve(platform).resolve(libraryName)
                    : prebuilt;
        }
        library = home.requireManaged(library);
        if (!Files.isRegularFile(library, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("缺少已打包的桌面原生库: " + library);
        // Never resolve a native library from plugins/, cwd, java.library.path, or system temp.
        if (!library.toRealPath().startsWith(home.root()))
            throw new IOException("桌面原生库逃逸应用目录: " + library);
        // The generated wrappers resolve symbols only against this exact library.
        try {
            initializeNativeLookup(library);
            if (desktop_bridge_h.jc_desktop_api_version() != desktop_bridge_h.JC_DESKTOP_ABI_VERSION())
                throw new IOException("桌面原生库 ABI 版本不兼容: " + library);
            desktop_bridge_h.jc_desktop_probe$address();
            desktop_bridge_h.jc_desktop_request_permissions$address();
            desktop_bridge_h.jc_desktop_list_windows$address();
            desktop_bridge_h.jc_desktop_open$address();
            desktop_bridge_h.jc_desktop_poll_frame$address();
            desktop_bridge_h.jc_desktop_release_frame$address();
            desktop_bridge_h.jc_desktop_list_elements$address();
            desktop_bridge_h.jc_desktop_current_window$address();
            desktop_bridge_h.jc_desktop_prepare_foreground$address();
            desktop_bridge_h.jc_desktop_restore_foreground$address();
            desktop_bridge_h.jc_desktop_perform$address();
            desktop_bridge_h.jc_desktop_close$address();
        } catch (RuntimeException | LinkageError failure) {
            throw new IOException("桌面原生库无法加载或缺少 ABI 符号: " + library, failure);
        }
        // This symbol was added after the original ABI. Older installed libraries can
        // still capture existing windows, but must report launch as unsupported.
        launchApplication = nativeLookup().find("jc_desktop_launch_application")
                .map(address -> Linker.nativeLinker().downcallHandle(address,
                        FunctionDescriptor.of(ValueLayout.JAVA_INT,
                                ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                                ValueLayout.ADDRESS, ValueLayout.JAVA_INT)))
                .orElse(null);
        resolveApplicationId = nativeLookup().find("jc_desktop_resolve_application_id")
                .map(address -> Linker.nativeLinker().downcallHandle(address,
                        FunctionDescriptor.of(ValueLayout.JAVA_INT,
                                ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                                ValueLayout.JAVA_INT)))
                .orElse(null);
        listApplications = nativeLookup().find("jc_desktop_list_applications")
                .map(address -> Linker.nativeLinker().downcallHandle(address,
                        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
                                ValueLayout.JAVA_INT, ValueLayout.ADDRESS)))
                .orElse(null);
        processApplicationId = nativeLookup().find("jc_desktop_process_application_id")
                .map(address -> Linker.nativeLinker().downcallHandle(address,
                        FunctionDescriptor.of(ValueLayout.JAVA_INT,
                                ValueLayout.JAVA_LONG, ValueLayout.ADDRESS,
                                ValueLayout.JAVA_INT)))
                .orElse(null);
        // ABI-compatible libraries may omit these additive entry points (including Windows).
        // Keep the generated ABI and its mandatory symbols unchanged.
        performElement = nativeLookup().find("jc_desktop_perform_element")
                .map(address -> Linker.nativeLinker().downcallHandle(address,
                        FunctionDescriptor.of(ValueLayout.JAVA_INT,
                                ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                                ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
                                ValueLayout.JAVA_INT)))
                .orElse(null);
        elementDiagnostics = nativeLookup().find("jc_desktop_element_diagnostics")
                .map(address -> Linker.nativeLinker().downcallHandle(address,
                        FunctionDescriptor.of(ValueLayout.JAVA_INT,
                                ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                                ValueLayout.JAVA_INT)))
                .orElse(null);
    }

    /** Called by the generated header binding after the trusted path is established. */
    public static synchronized SymbolLookup nativeLookup() {
        if (librarySymbols == null) throw new IllegalStateException("桌面原生库尚未装载");
        return librarySymbols;
    }

    private static synchronized void initializeNativeLookup(Path library) throws IOException {
        Path canonical = library.toRealPath();
        if (librarySymbols != null) {
            if (!canonical.equals(loadedLibrary))
                throw new IOException("本进程已装载其他桌面原生库: " + loadedLibrary);
            return;
        }
        librarySymbols = SymbolLookup.libraryLookup(canonical, Arena.global());
        loadedLibrary = canonical;
    }

    public DesktopAvailability probe(String providerId) {
        return availability(providerId, false);
    }

    /** Explicit settings action. The native request may display OS UI. */
    public DesktopAvailability requestPermissions(String providerId) {
        return availability(providerId, true);
    }

    private DesktopAvailability availability(String providerId, boolean request) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment capabilities = arena.allocate(ValueLayout.JAVA_INT);
            MemorySegment detail = arena.allocate(DETAIL_BYTES);
            int code = request
                    ? desktop_bridge_h.jc_desktop_request_permissions(capabilities, detail, DETAIL_BYTES)
                    : desktop_bridge_h.jc_desktop_probe(capabilities, detail, DETAIL_BYTES);
            int flags = capabilities.get(ValueLayout.JAVA_INT, 0);
            int input = desktop_bridge_h.JC_CAP_SEMANTIC_INPUT()
                    | desktop_bridge_h.JC_CAP_DIRECTED_INPUT()
                    | desktop_bridge_h.JC_CAP_FOREGROUND_INPUT();
            boolean ready = code == 0 && (flags & desktop_bridge_h.JC_CAP_CAPTURE()) != 0
                    && ("macos".equals(providerId)
                        ? (flags & (desktop_bridge_h.JC_CAP_SEMANTIC_INPUT()
                            | desktop_bridge_h.JC_CAP_FOREGROUND_INPUT()))
                            == (desktop_bridge_h.JC_CAP_SEMANTIC_INPUT()
                                | desktop_bridge_h.JC_CAP_FOREGROUND_INPUT())
                        : (flags & input) != 0);
            String reason = utf8(detail, DETAIL_BYTES);
            if (code != 0 && "macos".equals(providerId)) {
                return new DesktopAvailability(false, "", 0,
                        reason.isBlank() ? "桌面原生权限检查失败（" + code + "）" : reason);
            }
            if (!ready && reason.isBlank()) reason = "桌面捕获或输入不可用，或缺少系统授权";
            return new DesktopAvailability(ready, providerId, flags, reason);
        } catch (Throwable failure) {
            String reason = failure.getMessage();
            return new DesktopAvailability(false, "", 0,
                    reason == null || reason.isBlank() ? "桌面原生权限检查失败" : reason);
        }
    }

    public List<NativeWindow> listWindows() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment count = arena.allocate(ValueLayout.JAVA_INT);
            int result = desktop_bridge_h.jc_desktop_list_windows(MemorySegment.NULL, 0, count);
            if (result < 0) throw new IllegalStateException("窗口发现失败: " + result);
            int total = Math.min(512, Math.max(0, count.get(ValueLayout.JAVA_INT, 0)));
            if (total == 0) return List.of();
            long windowBytes = jc_desktop_window.layout().byteSize();
            MemorySegment entries = arena.allocate(windowBytes * total,
                    jc_desktop_window.layout().byteAlignment());
            result = desktop_bridge_h.jc_desktop_list_windows(entries, total, count);
            if (result < 0) throw new IllegalStateException("窗口发现失败: " + result);
            int size = Math.min(total, Math.max(0, count.get(ValueLayout.JAVA_INT, 0)));
            List<NativeWindow> windows = new ArrayList<>(size);
            for (int i = 0; i < size; i++) windows.add(window(entries.asSlice(i * windowBytes, windowBytes)));
            return List.copyOf(windows);
        } catch (Throwable failure) {
            throw new IllegalStateException("窗口发现失败", failure);
        }
    }

    public com.javaclaw.desktop.api.DesktopApplicationCatalog listApplications() {
        return DesktopApplicationCatalogCodec.read(listApplications);
    }

    public DesktopApplicationLaunch launchApplication(String application) {
        if (launchApplication == null)
            throw new DesktopApplicationLaunchRejectedException(
                    DesktopApplicationLaunchRejectedException.Reason.UNSUPPORTED,
                    "当前桌面原生库尚不支持启动应用，请更新原生库");
        if (application == null)
            throw new DesktopApplicationLaunchRejectedException(
                    DesktopApplicationLaunchRejectedException.Reason.INVALID_ARGUMENTS,
                    "应用名称或 bundle ID 不能为空");
        byte[] name = application.getBytes(StandardCharsets.UTF_8);
        boolean dispatchAttempted = false;
        String applicationId = "";
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment input = arena.allocate(name.length + 1L);
            input.asSlice(0, name.length).copyFrom(MemorySegment.ofArray(name));
            input.set(ValueLayout.JAVA_BYTE, name.length, (byte) 0);
            if (resolveApplicationId != null) {
                try {
                    MemorySegment resolved = arena.allocate(256);
                    int status = (int) resolveApplicationId.invokeExact(input, resolved, 256);
                    if (status == 0) applicationId = utf8(resolved, 256);
                } catch (Throwable ignored) {
                    // Optional identity lookup cannot turn a safe launch into a failed one.
                }
            }
            MemorySegment pid = arena.allocate(ValueLayout.JAVA_LONG);
            pid.set(ValueLayout.JAVA_LONG, 0, 0L);
            MemorySegment detail = arena.allocate(DETAIL_BYTES);
            dispatchAttempted = true;
            int code = (int) launchApplication.invokeExact(input, pid, detail, DETAIL_BYTES);
            String message = utf8(detail, DETAIL_BYTES);
            return launchResult(code, pid.get(ValueLayout.JAVA_LONG, 0), applicationId, message);
        } catch (DesktopApplicationLaunchRejectedException
                | DesktopApplicationLaunchUncertainException failure) { throw failure; }
        catch (Throwable failure) {
            if (dispatchAttempted) throw new DesktopApplicationLaunchUncertainException(
                    "启动请求结果未确认；请先重新发现窗口", 0, applicationId, failure);
            throw new DesktopApplicationLaunchRejectedException(
                    DesktopApplicationLaunchRejectedException.Reason.PRE_DISPATCH_FAILURE,
                    0, "启动请求准备失败，尚未发送到系统", failure);
        }
    }

    /** Native -1..-4 are admission failures; all other failures may follow an OS dispatch. */
    static DesktopApplicationLaunch launchResult(int code, long processId,
            String applicationId, String detail) {
        String message = detail == null || detail.isBlank() ? "错误码 " + code : detail;
        DesktopApplicationLaunchRejectedException.Reason rejection = switch (code) {
            case -1 -> DesktopApplicationLaunchRejectedException.Reason.INVALID_ARGUMENTS;
            case -2 -> DesktopApplicationLaunchRejectedException.Reason.UNSUPPORTED;
            case -3 -> DesktopApplicationLaunchRejectedException.Reason.APPLICATION_NOT_FOUND;
            case -4 -> DesktopApplicationLaunchRejectedException.Reason.AMBIGUOUS_APPLICATION;
            default -> null;
        };
        if (rejection != null)
            throw new DesktopApplicationLaunchRejectedException(rejection, code, message, null);
        if (code != 0 || processId <= 0)
            throw new DesktopApplicationLaunchUncertainException(
                    code == 0 ? "启动请求已返回，但未获得有效进程 ID；请先重新发现窗口" : message,
                    Math.max(0, processId), applicationId, null);
        return new DesktopApplicationLaunch(processId, applicationId, detail);
    }

    public MemorySegment open(NativeWindow target) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment detail = arena.allocate(DETAIL_BYTES);
            MemorySegment pointer = desktop_bridge_h.jc_desktop_open(target.processId(),
                    target.windowId(), target.processInstanceId(), detail, DETAIL_BYTES);
            if (pointer.equals(MemorySegment.NULL))
                throw new IllegalStateException("无法打开桌面会话: " + utf8(detail, DETAIL_BYTES));
            return pointer;
        } catch (RuntimeException failure) { throw failure; }
        catch (Throwable failure) { throw new IllegalStateException("无法打开桌面会话", failure); }
    }

    public NativeWindow current(MemorySegment session) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment value = arena.allocate(jc_desktop_window.layout());
            int code = desktop_bridge_h.jc_desktop_current_window(session, value);
            if (code != 0) throw new IllegalStateException("目标窗口不可用: " + code);
            return window(value);
        } catch (RuntimeException failure) { throw failure; }
        catch (Throwable failure) { throw new IllegalStateException("目标窗口不可用", failure); }
    }

    public Optional<DesktopFrame> poll(MemorySegment session, String targetId, int timeoutMillis) {
        return pollCaptured(session, targetId, timeoutMillis).map(CapturedFrame::frame);
    }

    /** Existing ABI frame identity stays paired with its pixels; no subsequent current-window guess. */
    Optional<CapturedFrame> pollCaptured(MemorySegment session, String targetId, int timeoutMillis) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment frame = arena.allocate(jc_desktop_frame.layout());
            int result = desktop_bridge_h.jc_desktop_poll_frame(session, frame, timeoutMillis);
            if (result == 1) return Optional.empty();
            if (result != 0) throw new IllegalStateException("采集失败或权限已撤销: " + result);
            try {
                MemorySegment pixels = jc_desktop_frame.pixels(frame);
                long byteCount = jc_desktop_frame.byte_count(frame);
                int width = jc_desktop_frame.width(frame);
                int height = jc_desktop_frame.height(frame);
                int stride = jc_desktop_frame.stride(frame);
                long timestamp = jc_desktop_frame.timestamp_millis(frame);
                long generation = jc_desktop_frame.generation(frame);
                long contentRevision = jc_desktop_frame.content_revision(frame);
                long windowId = jc_desktop_frame.window_id(frame);
                if (pixels.equals(MemorySegment.NULL) || width < 1 || height < 1
                        || stride < (long) width * 4 || byteCount != (long) stride * height
                        || byteCount > 256L * 1024 * 1024 || generation < 1
                        || contentRevision < 1)
                    throw new IllegalStateException("原生采集返回无效帧");
                byte[] copy = pixels.reinterpret(byteCount).toArray(ValueLayout.JAVA_BYTE);
                return Optional.of(new CapturedFrame(new DesktopFrame(targetId, generation, timestamp,
                        width, height, stride, copy, contentRevision), windowId));
            } finally {
                desktop_bridge_h.jc_desktop_release_frame(frame);
            }
        } catch (RuntimeException failure) { throw failure; }
        catch (Throwable failure) { throw new IllegalStateException("采集失败", failure); }
    }

    record CapturedFrame(DesktopFrame frame, long windowId) { }

    public List<DesktopElement> elements(MemorySegment session, DesktopFrame frame) {
        try (Arena arena = Arena.ofConfined()) {
            int capacity = 512;
            long elementBytes = jc_desktop_element.layout().byteSize();
            MemorySegment values = arena.allocate(jc_desktop_element.layout(), capacity);
            MemorySegment count = arena.allocate(ValueLayout.JAVA_INT);
            int code = desktop_bridge_h.jc_desktop_list_elements(session,
                    frame.windowGeneration(), frame.contentRevision(), values, capacity, count);
            if (code == 1) throw new IllegalStateException("辅助功能元素所在画面已变化");
            if (code != 0) return List.of();
            int length = count.get(ValueLayout.JAVA_INT, 0);
            if (length < 0 || length > capacity) throw new IllegalStateException("原生元素列表长度无效");
            List<DesktopElement> found = new ArrayList<>(length);
            for (int index = 0; index < length; index++) {
                MemorySegment value = values.asSlice(index * elementBytes, elementBytes);
                int x = jc_desktop_element.x(value);
                int y = jc_desktop_element.y(value);
                int width = jc_desktop_element.width(value);
                int height = jc_desktop_element.height(value);
                int token = jc_desktop_element.index(value);
                if (x < 0 || y < 0 || width < 1 || height < 1
                        || !acceptsElementIndex(token, performElement != null)
                        || (long) x + width > frame.width()
                        || (long) y + height > frame.height()) continue;
                found.add(new DesktopElement(elementId(token),
                        utf8(jc_desktop_element.role_utf8(value), 64),
                        utf8(jc_desktop_element.label_utf8(value), 256),
                        x, y, width, height, jc_desktop_element.actions(value)));
            }
            return List.copyOf(found);
        } catch (RuntimeException failure) { throw failure; }
        catch (Throwable failure) { throw new IllegalStateException("读取辅助功能元素失败", failure); }
    }

    public void prepareForeground(MemorySegment session) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment detail = arena.allocate(DETAIL_BYTES);
            int code = desktop_bridge_h.jc_desktop_prepare_foreground(session, detail, DETAIL_BYTES);
            if (code != 0) throw new IllegalStateException("目标窗口无法安全置前: "
                    + utf8(detail, DETAIL_BYTES) + " (" + code + ")");
        } catch (RuntimeException failure) { throw failure; }
        catch (Throwable failure) { throw new IllegalStateException("准备前台输入失败", failure); }
    }

    public void restoreForeground(MemorySegment session) {
        try { desktop_bridge_h.jc_desktop_restore_foreground(session); }
        catch (Throwable failure) { throw new IllegalStateException("恢复前台焦点失败", failure); }
    }

    /** Last native accessibility catalog diagnostic; absent on libraries without the extension. */
    public String elementDiagnostics(MemorySegment session) {
        return readElementDiagnostics(elementDiagnostics, session);
    }

    /** Telemetry failure never prevents a captured frame from being observed. */
    static String readElementDiagnostics(MethodHandle diagnostics, MemorySegment session) {
        if (diagnostics == null) return "";
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment detail = arena.allocate(DETAIL_BYTES);
            int code = (int) diagnostics.invokeExact(session, detail, DETAIL_BYTES);
            // A failing native call may leave arbitrary or partial bytes in detail.
            if (code != 0) return "辅助功能元素诊断不可用（状态 " + code + "）";
            return utf8(detail, DETAIL_BYTES);
        } catch (Throwable failure) {
            // Do not expose a native exception message, which may contain target content.
            return "辅助功能元素诊断不可用（调用失败）";
        }
    }

    public DesktopActionResult perform(MemorySegment session, DesktopAction action,
                                        boolean foreground) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment input = arena.allocate(jc_desktop_action.layout());
            MemorySegment detail = arena.allocate(DETAIL_BYTES);
            byte[] bytes = action.text().getBytes(StandardCharsets.UTF_8);
            MemorySegment text = bytes.length == 0 ? MemorySegment.NULL : arena.allocate(bytes.length + 1L);
            if (bytes.length > 0) text.asSlice(0, bytes.length).copyFrom(MemorySegment.ofArray(bytes));
            jc_desktop_action.kind(input, switch (action.kind()) {
                case CLICK -> desktop_bridge_h.JC_ACTION_CLICK();
                case TYPE -> desktop_bridge_h.JC_ACTION_TYPE();
                case KEY -> desktop_bridge_h.JC_ACTION_KEY();
                case SCROLL -> desktop_bridge_h.JC_ACTION_SCROLL();
            });
            jc_desktop_action.mode(input, foreground
                    ? desktop_bridge_h.JC_MODE_FOREGROUND() : desktop_bridge_h.JC_MODE_BACKGROUND());
            jc_desktop_action.x(input, action.x());
            jc_desktop_action.y(input, action.y());
            jc_desktop_action.button(input, action.button());
            jc_desktop_action.clicks(input, action.clicks());
            jc_desktop_action.amount(input, action.amount());
            jc_desktop_action.generation(input, action.windowGeneration());
            jc_desktop_action.content_revision(input, action.contentRevision());
            jc_desktop_action.text_utf8(input, text);
            jc_desktop_action.text_bytes(input, bytes.length);
            int status = dispatchAction(action, foreground, performElement,
                    session, input, detail, DETAIL_BYTES, desktop_bridge_h::jc_desktop_perform);
            return new DesktopActionResult(actionStatus(status), utf8(detail, DETAIL_BYTES),
                    action.windowGeneration());
        } catch (RuntimeException failure) { throw failure; }
        catch (Throwable failure) { throw new IllegalStateException("目标操作失败", failure); }
    }

    @FunctionalInterface
    interface LegacyPerformer {
        int perform(MemorySegment session, MemorySegment action, MemorySegment detail, int capacity);
    }

    /** The native index is a uint32 token, not a signed Java element position. */
    static String elementId(int index) {
        return "e" + Integer.toUnsignedString(index);
    }

    /** Implementations without tokenized AX use zero-based element positions. */
    static boolean acceptsElementIndex(int index, boolean tokenizedElementDispatch) {
        return index != 0 || !tokenizedElementDispatch;
    }

    /** Only a background single left AX click can use the optional element entry point. */
    static OptionalInt elementIndexFor(DesktopAction action, boolean foreground) {
        if (foreground || action.kind() != DesktopAction.Kind.CLICK
                || action.button() != 1 || action.clicks() != 1) return OptionalInt.empty();
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

    /** Once element dispatch is selected, its status or failure cannot trigger a coordinate retry. */
    static int dispatchAction(DesktopAction action, boolean foreground,
            MethodHandle elementPerformer, MemorySegment session, MemorySegment input,
            MemorySegment detail, int capacity, LegacyPerformer legacy) throws Throwable {
        if (elementPerformer != null) {
            OptionalInt index = elementIndexFor(action, foreground);
            if (index.isPresent())
                return (int) elementPerformer.invokeExact(session, input,
                        index.getAsInt(), detail, capacity);
        }
        return legacy.perform(session, input, detail, capacity);
    }

    public void close(MemorySegment session) {
        try { desktop_bridge_h.jc_desktop_close(session); }
        catch (Throwable failure) { throw new IllegalStateException("释放桌面会话失败", failure); }
    }

    static DesktopActionResult.Status actionStatus(int nativeStatus) {
        // ABI 6 action codes from desktop_bridge.h. Keep classification independent
        // of generated FFM wrappers, whose initializer requires a loaded OS library.
        return switch (nativeStatus) {
            case 0 -> DesktopActionResult.Status.VERIFIED;
            case 1 -> DesktopActionResult.Status.UNKNOWN;
            case 2 -> DesktopActionResult.Status.UNSUPPORTED;
            case 3 -> DesktopActionResult.Status.STALE_FRAME;
            case 4 -> DesktopActionResult.Status.DENIED;
            case 5 -> DesktopActionResult.Status.FAILED;
            case 6 -> DesktopActionResult.Status.ACCEPTED;
            // An unrecognized native result cannot establish that input was not sent.
            default -> DesktopActionResult.Status.UNKNOWN;
        };
    }

    private NativeWindow window(MemorySegment value) {
        long processId = jc_desktop_window.process_id(value);
        String application = utf8(jc_desktop_window.app_utf8(value),
                desktop_bridge_h.JC_DESKTOP_APP_BYTES());
        return new NativeWindow(jc_desktop_window.process_id(value),
                jc_desktop_window.window_id(value), jc_desktop_window.process_instance_id(value),
                jc_desktop_window.x(value),
                jc_desktop_window.y(value), jc_desktop_window.width(value),
                jc_desktop_window.height(value), jc_desktop_window.flags(value),
                application,
                utf8(jc_desktop_window.title_utf8(value), desktop_bridge_h.JC_DESKTOP_TITLE_BYTES()),
                applicationId(processId, application));
    }

    private String applicationId(long processId, String application) {
        if (processId > 0 && processApplicationId != null) {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment value = arena.allocate(256);
                int status = (int) processApplicationId.invokeExact(processId, value, 256);
                if (status == 0) return utf8(value, 256);
            } catch (Throwable ignored) {
                // Missing permission or a terminating process leaves the owner ID unknown.
            }
        }
        return providerId.equals("windows") && application.toLowerCase(java.util.Locale.ROOT)
                .endsWith(".exe") ? application.toLowerCase(java.util.Locale.ROOT) : "";
    }

    private static String utf8(MemorySegment segment, int capacity) {
        byte[] bytes = segment.asSlice(0, capacity).toArray(ValueLayout.JAVA_BYTE);
        int end = 0;
        while (end < bytes.length && bytes[end] != 0) end++;
        return new String(bytes, 0, end, StandardCharsets.UTF_8);
    }

    public record NativeWindow(long processId, long windowId, long processInstanceId,
                               int x, int y, int width,
                               int height, int flags, String application, String title,
                               String applicationId) {}
}
