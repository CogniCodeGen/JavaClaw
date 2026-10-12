package com.javaclaw.desktop.ffm.windows;

import com.javaclaw.desktop.api.DesktopAction;
import com.javaclaw.desktop.api.DesktopActionResult;
import com.javaclaw.desktop.api.DesktopElement;
import com.javaclaw.desktop.api.DesktopFrame;
import com.javaclaw.desktop.ffm.DesktopSemanticAction;
import com.javaclaw.desktop.nativebridge.DesktopBridge;
import java.lang.foreign.Arena;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import static com.javaclaw.desktop.ffm.windows.Win32.*;

/** Public UI Automation only. COM references never survive their calling apartment. */
final class WindowsUia {
    static final int INVOKE = 10000, VALUE = 10002, SCROLL = 10004,
            SELECT = 10010, TOGGLE = 10015;
    private static final int MAX_NODES = 2048, MAX_ELEMENTS = 128;
    private final Backend backend;
    private final Map<String, Snapshot> snapshots = new LinkedHashMap<>();
    private long sequence;
    private String diagnostic = "";

    WindowsUia(Win32 win) { this(new ComBackend(win)); }
    WindowsUia(Backend backend) { this.backend = backend; }

    synchronized List<DesktopElement> elements(long hwnd, WindowsWindows.Rect bounds, DesktopFrame frame) {
        snapshots.clear();
        List<DesktopElement> result = new ArrayList<>();
        int visited = 0, failed = 0;
        try (Tree tree = backend.open(hwnd)) {
            for (Node node : tree.nodes()) {
                visited++;
                try {
                    State state = node.state();
                    if (!state.usable() || state.actions() == 0) continue;
                    if (sequence >= 0xffff_ffffL) break;
                    DesktopElement element = element(DesktopBridge.elementId((int) ++sequence), state, bounds, frame);
                    if (element == null || result.size() == MAX_ELEMENTS) continue;
                    result.add(element);
                    snapshots.put(element.id(), new Snapshot(hwnd, bounds, frame.windowGeneration(),
                            frame.contentRevision(), state, element));
                } catch (RuntimeException failure) { failed++; }
            }
            diagnostic = "UIA visited=" + visited + ", returned=" + result.size()
                    + ", readFailures=" + failed + ", truncated="
                    + (tree.truncated() || result.size() == MAX_ELEMENTS);
            return List.copyOf(result);
        } catch (RuntimeException failure) {
            snapshots.clear();
            diagnostic = "UIA catalog unavailable: " + failure.getClass().getSimpleName();
            return List.of();
        }
    }

    synchronized DesktopActionResult perform(long hwnd, WindowsWindows.Rect bounds,
            DesktopFrame frame, DesktopAction action, BooleanSupplier ready) {
        return perform(hwnd, bounds, frame, action, ready, () -> true);
    }

    synchronized DesktopActionResult perform(long hwnd, WindowsWindows.Rect bounds,
            DesktopFrame frame, DesktopAction action, BooleanSupplier ready, BooleanSupplier freshFrame) {
        List<Snapshot> observed = List.copyOf(snapshots.values());
        snapshots.clear(); // Any attempted use consumes the whole previous observation's tokens.
        if (!supported(action)) return result(action, DesktopActionResult.Status.UNSUPPORTED,
                "UIA supports one left press, explicit SET_TEXT and semantic scroll", false);
        Snapshot snapshot = select(observed, action);
        if (snapshot == null || !snapshot.matches(hwnd, bounds, frame, action))
            return result(action, DesktopActionResult.Status.STALE_FRAME, "Observed UIA target is absent or stale", false);
        if (!ready(ready)) return result(action, DesktopActionResult.Status.DENIED,
                "Target is active or unavailable for background input", false);
        boolean dispatched = false;
        try (Tree tree = backend.open(hwnd)) {
            if (tree.truncated()) return result(action, DesktopActionResult.Status.STALE_FRAME,
                    "Incomplete UIA tree cannot prove unique observed identity", false);
            Node selected = null;
            State current = null;
            for (Node node : tree.nodes()) {
                State candidate;
                try { candidate = node.state(); }
                catch (RuntimeException failure) {
                    return result(action, DesktopActionResult.Status.STALE_FRAME, "UIA identity read incomplete", false);
                }
                if (!candidate.runtimeId().equals(snapshot.state().runtimeId())) continue;
                if (selected != null) return result(action, DesktopActionResult.Status.STALE_FRAME,
                        "UIA runtime identity is ambiguous", false);
                selected = node;
                current = candidate;
            }
            if (selected == null || !sameTarget(snapshot.state(), current))
                return result(action, DesktopActionResult.Status.STALE_FRAME, "Observed UIA element changed", false);
            int pattern = pattern(action, current);
            if (pattern == 0) return result(action, DesktopActionResult.Status.UNSUPPORTED,
                    "Observed control no longer supports the requested public action", false);
            try (Mutation mutation = selected.prepare(pattern, action.text(), Integer.signum(action.amount()))) {
                int repeats = action.kind() == DesktopAction.Kind.SCROLL ? Math.abs(action.amount()) : 1;
                for (int step = 0; step < repeats; step++) {
                    if (step == 0 && !ready(freshFrame)) return result(action, DesktopActionResult.Status.STALE_FRAME,
                            "Fresh pre-input capture changed or is unavailable; no input was sent", false);
                    if (!sameTarget(snapshot.state(), selected.state())) return result(action, dispatched
                            ? DesktopActionResult.Status.UNKNOWN : DesktopActionResult.Status.STALE_FRAME,
                            "Observed UIA control changed before public dispatch", dispatched);
                    if (!ready(ready)) return result(action, dispatched
                            ? DesktopActionResult.Status.UNKNOWN : DesktopActionResult.Status.DENIED,
                            "Target changed before public UIA dispatch", dispatched);
                    dispatched = true; // The next call crosses a mutating COM method; errors cannot prove NOT_SENT.
                    int status = mutation.dispatch();
                    if (status < 0) return result(action, DesktopActionResult.Status.UNKNOWN,
                            "UIA dispatch returned an error; effect is uncertain", true);
                    if (!ready(ready)) return result(action, DesktopActionResult.Status.UNKNOWN,
                            "Target activated or became unavailable after UIA dispatch", true);
                }
                boolean verified = !(action.kind() == DesktopAction.Kind.TYPE && current.password())
                        && mutation.confirms();
                return result(action, verified ? DesktopActionResult.Status.VERIFIED : DesktopActionResult.Status.ACCEPTED,
                        verified ? "Public control state confirmed by readback" : "Public UIA input accepted; business result is unverified", true);
            }
        } catch (RuntimeException failure) {
            return result(action, dispatched ? DesktopActionResult.Status.UNKNOWN : DesktopActionResult.Status.FAILED,
                    dispatched ? "UIA operation may have taken effect" : "UIA target could not be revalidated", dispatched);
        }
    }

    synchronized void clear() { snapshots.clear(); }
    synchronized String diagnostics() { return diagnostic; }

    static boolean supported(DesktopAction action) {
        try {
            if (DesktopSemanticAction.admit(action).isEmpty()) return false;
            return switch (action.kind()) {
                case CLICK -> action.button() == 1 && action.clicks() == 1;
                case TYPE -> action.textOperation() == DesktopAction.TextOperation.SET_TEXT;
                case SCROLL -> true;
                case KEY -> false;
            };
        } catch (IllegalArgumentException invalid) { return false; }
    }

    private static Snapshot select(List<Snapshot> observed, DesktopAction action) {
        var admitted = DesktopSemanticAction.admit(action);
        if (admitted.isEmpty()) return null;
        String token = DesktopBridge.elementId(admitted.orElseThrow().elementToken());
        return observed.stream().filter(snapshot -> snapshot.element().id().equals(token)).findFirst().orElse(null);
    }

    static boolean sameTarget(State observed, State current) {
        return current != null && current.usable() && observed.runtimeId().equals(current.runtimeId())
                && observed.processId() == current.processId() && observed.bounds().equals(current.bounds())
                && observed.type() == current.type() && observed.label().equals(current.label())
                && observed.password() == current.password() && observed.actions() == current.actions()
                && observed.pressPattern() == current.pressPattern();
    }

    private static int pattern(DesktopAction action, State state) {
        return switch (action.kind()) {
            case CLICK -> (state.actions() & DesktopElement.PRESS) != 0 ? state.pressPattern() : 0;
            case TYPE -> (state.actions() & DesktopElement.SET_TEXT) != 0 ? VALUE : 0;
            case SCROLL -> (state.actions() & DesktopElement.SCROLL) != 0 ? SCROLL : 0;
            case KEY -> 0;
        };
    }

    static DesktopElement element(String id, State state, WindowsWindows.Rect window, DesktopFrame frame) {
        if (window.width() < 1 || window.height() < 1) return null;
        long left = Math.max(state.bounds().left(), window.left()), top = Math.max(state.bounds().top(), window.top());
        long right = Math.min(state.bounds().right(), window.right()), bottom = Math.min(state.bounds().bottom(), window.bottom());
        if (right <= left || bottom <= top) return null;
        int x = (int) ((left - window.left()) * frame.width() / window.width());
        int y = (int) ((top - window.top()) * frame.height() / window.height());
        int r = (int) ((right - window.left()) * frame.width() / window.width());
        int b = (int) ((bottom - window.top()) * frame.height() / window.height());
        return new DesktopElement(id, role(state.type()), state.label(), x, y,
                Math.max(1, r - x), Math.max(1, b - y), state.actions());
    }

    static String role(int type) {
        return switch (type) {
            case 50000 -> "button"; case 50002 -> "checkbox"; case 50003 -> "combo";
            case 50004 -> "edit"; case 50005 -> "link"; case 50007 -> "list-item";
            case 50011 -> "menu-item"; case 50013 -> "radio"; case 50014 -> "scrollbar";
            case 50019 -> "tab-item"; case 50030 -> "document"; default -> "control";
        };
    }

    private static boolean ready(BooleanSupplier ready) {
        try { return ready.getAsBoolean(); }
        catch (RuntimeException unavailable) { return false; }
    }

    private static DesktopActionResult result(DesktopAction action, DesktopActionResult.Status status,
            String detail, boolean dispatched) {
        DesktopActionResult.Reason reason = switch (status) {
            case UNKNOWN -> DesktopActionResult.Reason.DELIVERY_UNCERTAIN;
            case STALE_FRAME -> DesktopActionResult.Reason.STALE_OBSERVATION;
            case UNSUPPORTED -> DesktopActionResult.Reason.NO_SEMANTIC_PATH;
            case DENIED -> DesktopActionResult.Reason.TARGET_ACTIVE;
            case FAILED -> DesktopActionResult.Reason.PLATFORM_FAILURE;
            case ACCEPTED, VERIFIED -> DesktopActionResult.Reason.NONE;
        };
        return new DesktopActionResult(status, detail, action.windowGeneration(),
                DesktopActionResult.Mode.BACKGROUND_SEMANTIC, reason, dispatched, action.observationId(),
                status == DesktopActionResult.Status.UNKNOWN ? DesktopActionResult.NextStep.RECONCILE
                        : DesktopActionResult.NextStep.OBSERVE);
    }

    interface Backend { Tree open(long window); }
    interface Tree extends AutoCloseable {
        List<Node> nodes();
        boolean truncated();
        @Override void close();
    }
    interface Node {
        State state();
        Mutation prepare(int pattern, String text, int direction);
    }
    interface Mutation extends AutoCloseable {
        int dispatch();
        default boolean confirms() { return false; }
        @Override default void close() { }
    }
    record State(List<Integer> runtimeId, int processId, WindowsWindows.Rect bounds, int type,
            String label, boolean password, boolean enabled, boolean offscreen, int actions, int pressPattern) {
        State {
            runtimeId = List.copyOf(runtimeId);
            label = password || type == 50004 || type == 50030 ? "" : label;
        }
        boolean usable() { return !runtimeId.isEmpty() && processId > 0 && enabled && !offscreen
                && bounds.width() > 0 && bounds.height() > 0; }
    }
    private record Snapshot(long hwnd, WindowsWindows.Rect window, long generation, long revision,
            State state, DesktopElement element) {
        boolean matches(long currentWindow, WindowsWindows.Rect bounds, DesktopFrame frame, DesktopAction action) {
            return hwnd == currentWindow && window.equals(bounds) && generation == frame.windowGeneration()
                    && generation == action.windowGeneration()
                    // Only CLICK has an upper-layer pixel ROI guard permitting unrelated animation.
                    && (action.kind() == DesktopAction.Kind.CLICK
                            ? revision <= frame.contentRevision() : revision == frame.contentRevision())
                    && frame.contentRevision() == action.contentRevision() && action.x() >= element.x() && action.y() >= element.y()
                    && (long) action.x() < (long) element.x() + element.width()
                    && (long) action.y() < (long) element.y() + element.height();
        }
    }

    /** Vtable indices and IIDs follow Microsoft's UIAutomationClient.h, not a private bridge ABI. */
    private static final class ComBackend implements Backend {
        private final Win32 win;
        ComBackend(Win32 win) { this.win = win; }
        @Override public Tree open(long window) { return new ComTree(win, window); }
    }

    private static final class ComTree implements Tree {
        private final Win32 win;
        private final Win32.Apartment apartment;
        private final Arena arena = Arena.ofConfined();
        private final List<MemorySegment> references = new ArrayList<>();
        private final List<Node> nodes = new ArrayList<>();
        private boolean truncated;
        ComTree(Win32 win, long window) {
            this.win = win;
            apartment = win.apartment();
            try {
                MemorySegment output = arena.allocate(P);
                check(win.integer("ole32", "CoCreateInstance", new MemoryLayout[]{P, P, I, P, P},
                        guid(arena, "ff48dba4-60ef-4201-aa87-54103eef594e"), MemorySegment.NULL, 1,
                        guid(arena, "30cbe57d-d9d0-452a-ab13-7ac5ac4825ee"), output));
                MemorySegment automation = retain(output.get(P, 0));
                MemorySegment root = object(automation, 6, new MemoryLayout[]{P, P}, handle(window));
                int expectedPid;
                MemorySegment pid = arena.allocate(I);
                win.integer("user32", "GetWindowThreadProcessId", new MemoryLayout[]{P, P}, handle(window), pid);
                expectedPid = pid.get(I, 0);
                MemorySegment condition = object(automation, 21, new MemoryLayout[]{P});
                MemorySegment children = object(root, 6, new MemoryLayout[]{I, P, P}, 4, condition);
                nodes.add(new ComNode(win, root, expectedPid));
                MemorySegment length = arena.allocate(I);
                check(win.com(children, 3, new MemoryLayout[]{P}, length));
                int count = length.get(I, 0);
                if (count < 0) throw new IllegalStateException("Invalid UIA descendant count");
                truncated = count > MAX_NODES;
                for (int index = 0; index < Math.min(count, MAX_NODES); index++)
                    nodes.add(new ComNode(win, object(children, 4, new MemoryLayout[]{I, P}, index), expectedPid));
            } catch (RuntimeException | Error failure) { close(); throw failure; }
        }
        private MemorySegment object(MemorySegment owner, int slot, MemoryLayout[] types, Object... args) {
            MemorySegment output = arena.allocate(P);
            Object[] all = java.util.Arrays.copyOf(args, args.length + 1);
            all[args.length] = output;
            check(win.com(owner, slot, types, all));
            return retain(output.get(P, 0));
        }
        private MemorySegment retain(MemorySegment reference) {
            if (nullPointer(reference)) throw new IllegalStateException("Missing UIA object");
            references.add(reference);
            return reference;
        }
        @Override public List<Node> nodes() { return List.copyOf(nodes); }
        @Override public boolean truncated() { return truncated; }
        @Override public void close() {
            try {
                for (int index = references.size() - 1; index >= 0; index--) win.release(references.get(index));
            } finally { references.clear(); arena.close(); apartment.close(); }
        }
    }

    private static final class ComNode implements Node {
        private final Win32 win;
        private final MemorySegment element;
        private final int expectedPid;
        ComNode(Win32 win, MemorySegment element, int expectedPid) {
            this.win = win; this.element = element; this.expectedPid = expectedPid;
        }
        @Override public State state() {
            try (Arena arena = Arena.ofConfined()) {
                int process = scalar(element, 20, arena), type = scalar(element, 21, arena);
                if (process != expectedPid) throw new IllegalStateException("UIA crossed the target process");
                boolean enabled = scalar(element, 28, arena) != 0, password = scalar(element, 35, arena) != 0;
                boolean offscreen = scalar(element, 38, arena) != 0;
                MemorySegment rectangle = arena.allocate(16, 4);
                check(win.com(element, 43, new MemoryLayout[]{P}, rectangle));
                WindowsWindows.Rect bounds = new WindowsWindows.Rect(rectangle.get(I, 0), rectangle.get(I, 4),
                        rectangle.get(I, 8), rectangle.get(I, 12));
                String label = password || type == 50004 || type == 50030 ? "" : string(element, 23, 256, arena);
                int actions = 0, press = 0;
                for (int id : new int[]{TOGGLE, SELECT, INVOKE}) {
                    MemorySegment pattern = pattern(id, arena);
                    try { if (!nullPointer(pattern)) { actions |= DesktopElement.PRESS; press = id; break; } }
                    finally { win.release(pattern); }
                }
                MemorySegment value = pattern(VALUE, arena);
                try { if (!nullPointer(value) && scalar(value, 5, arena) == 0)
                    actions |= DesktopElement.WRITE | DesktopElement.SET_TEXT; }
                finally { win.release(value); }
                MemorySegment scroll = pattern(SCROLL, arena);
                try { if (!nullPointer(scroll) && scalar(scroll, 10, arena) != 0) actions |= DesktopElement.SCROLL; }
                finally { win.release(scroll); }
                return new State(runtimeId(arena), process, bounds, type, label, password, enabled, offscreen, actions, press);
            }
        }
        @Override public Mutation prepare(int id, String text, int direction) {
            Arena arena = Arena.ofConfined();
            MemorySegment pattern = MemorySegment.NULL;
            MemorySegment value = MemorySegment.NULL;
            try {
                pattern = pattern(id, arena);
                if (nullPointer(pattern)) throw new IllegalStateException("UIA pattern changed before dispatch");
                if (id == VALUE) {
                    if (scalar(pattern, 5, arena) != 0) throw new IllegalStateException("UIA value became read-only");
                    value = win.pointer("oleaut32", "SysAllocStringLen", new MemoryLayout[]{P, I},
                            wide(arena, text), text.length());
                    if (nullPointer(value)) throw new IllegalStateException("BSTR allocation failed");
                }
                if (id == SCROLL && scalar(pattern, 10, arena) == 0)
                    throw new IllegalStateException("UIA control is no longer vertically scrollable");
                MemorySegment preparedPattern = pattern, preparedValue = value;
                boolean readableValue = id == VALUE && scalar(element, 35, arena) == 0;
                return new Mutation() {
                    @Override public int dispatch() {
                        if (id == VALUE) return win.com(preparedPattern, 3, new MemoryLayout[]{P}, preparedValue);
                        if (id == SCROLL) return win.com(preparedPattern, 3, new MemoryLayout[]{I, I}, 2, direction > 0 ? 4 : 1);
                        return win.com(preparedPattern, 3, new MemoryLayout[0]);
                    }
                    @Override public boolean confirms() {
                        try {
                            return id == VALUE ? readableValue && string(preparedPattern, 4, 8192, arena).equals(text)
                                    : id == SELECT && scalar(preparedPattern, 6, arena) != 0;
                        } catch (RuntimeException failure) { return false; }
                    }
                    @Override public void close() {
                        try {
                            if (!nullPointer(preparedValue)) win.nothing("oleaut32", "SysFreeString", new MemoryLayout[]{P}, preparedValue);
                            win.release(preparedPattern);
                        } finally { arena.close(); }
                    }
                };
            } catch (RuntimeException | Error failure) {
                try {
                    if (!nullPointer(value)) win.nothing("oleaut32", "SysFreeString", new MemoryLayout[]{P}, value);
                    win.release(pattern);
                } finally { arena.close(); }
                throw failure;
            }
        }
        private int scalar(MemorySegment owner, int slot, Arena arena) {
            MemorySegment output = arena.allocate(I);
            check(win.com(owner, slot, new MemoryLayout[]{P}, output));
            return output.get(I, 0);
        }
        private String string(MemorySegment owner, int slot, int maximum, Arena arena) {
            MemorySegment output = arena.allocate(P);
            check(win.com(owner, slot, new MemoryLayout[]{P}, output));
            MemorySegment value = output.get(P, 0);
            try {
                if (nullPointer(value)) return "";
                int count = win.integer("oleaut32", "SysStringLen", new MemoryLayout[]{P}, value);
                int length = Math.min(Math.max(count, 0), maximum);
                return new String(value.reinterpret(length * 2L).toArray(java.lang.foreign.ValueLayout.JAVA_CHAR));
            } finally { if (!nullPointer(value)) win.nothing("oleaut32", "SysFreeString", new MemoryLayout[]{P}, value); }
        }
        private MemorySegment pattern(int id, Arena arena) {
            String iid = switch (id) {
                case INVOKE -> "fb377fbe-8ea6-46d5-9c73-6499642d3059";
                case VALUE -> "a94cd8b1-0844-4cd6-9d2d-640537ab39e9";
                case SCROLL -> "88f4d42a-e881-459d-a77c-73bbbb7e02dc";
                case SELECT -> "a8efa66a-0fda-421a-9194-38021f3578ea";
                case TOGGLE -> "94cf8058-9b8d-4ab9-8bfd-4cd0a33c8c70";
                default -> throw new IllegalArgumentException("Unsupported UIA pattern");
            };
            MemorySegment output = arena.allocate(P);
            int status = win.com(element, 14, new MemoryLayout[]{I, P, P}, id, guid(arena, iid), output);
            if (status < 0) { win.release(output.get(P, 0)); return MemorySegment.NULL; }
            return output.get(P, 0);
        }
        private List<Integer> runtimeId(Arena arena) {
            MemorySegment output = arena.allocate(P);
            check(win.com(element, 4, new MemoryLayout[]{P}, output));
            MemorySegment array = output.get(P, 0);
            if (nullPointer(array)) return List.of();
            try {
                if (win.integer("oleaut32", "SafeArrayGetDim", new MemoryLayout[]{P}, array) != 1)
                    throw new IllegalStateException("Invalid UIA RuntimeId dimensions");
                MemorySegment lower = arena.allocate(I), upper = arena.allocate(I), type = arena.allocate(2, 2);
                check(win.integer("oleaut32", "SafeArrayGetVartype", new MemoryLayout[]{P, P}, array, type));
                if (type.get(java.lang.foreign.ValueLayout.JAVA_SHORT, 0) != 3)
                    throw new IllegalStateException("UIA RuntimeId is not VT_I4");
                check(win.integer("oleaut32", "SafeArrayGetLBound", new MemoryLayout[]{P, I, P}, array, 1, lower));
                check(win.integer("oleaut32", "SafeArrayGetUBound", new MemoryLayout[]{P, I, P}, array, 1, upper));
                long count = (long) upper.get(I, 0) - lower.get(I, 0) + 1;
                if (count < 1 || count > 128) throw new IllegalStateException("Oversized UIA RuntimeId");
                List<Integer> result = new ArrayList<>();
                MemorySegment index = arena.allocate(I), value = arena.allocate(I);
                for (int offset = 0; offset < count; offset++) {
                    index.set(I, 0, lower.get(I, 0) + offset);
                    check(win.integer("oleaut32", "SafeArrayGetElement", new MemoryLayout[]{P, P, P}, array, index, value));
                    result.add(value.get(I, 0));
                }
                return List.copyOf(result);
            } finally { win.integer("oleaut32", "SafeArrayDestroy", new MemoryLayout[]{P}, array); }
        }
    }
    private static void check(int result) {
        if (result < 0) throw new IllegalStateException("UIA HRESULT 0x" + Integer.toHexString(result));
    }
}
