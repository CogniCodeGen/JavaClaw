package com.javaclaw.desktop.ffm.macos;

import com.javaclaw.desktop.api.DesktopAction;
import com.javaclaw.desktop.api.DesktopActionResult;
import com.javaclaw.desktop.api.DesktopElement;
import com.javaclaw.desktop.api.DesktopFrame;
import com.javaclaw.desktop.nativebridge.DesktopBridge;
import com.javaclaw.desktop.nativebridge.DesktopBridge.NativeWindow;
import com.javaclaw.desktop.ffm.DesktopSemanticAction;
import java.lang.foreign.Arena;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import static com.javaclaw.desktop.api.DesktopActionResult.Status.*;

/** Public Accessibility queries and background semantic actions; no event injection. */
final class MacAccessibility implements AutoCloseable {
    private final MacNative api;
    private final Map<Integer, Node> snapshots = new HashMap<>();
    private long nextToken = 1;
    private String diagnostics = "AX has not been observed";
    MacAccessibility(MacNative api) { this.api = api; }

    boolean trusted() {
        return (byte) api.call("AXIsProcessTrusted", ValueLayout.JAVA_BYTE, new MemoryLayout[0]) != 0;
    }

    List<DesktopElement> observe(NativeWindow target, DesktopFrame frame) {
        clear();
        if (!MacTargetPolicy.externalProcess(target.processId())) {
            diagnostics = "Self-process Accessibility observation is prohibited";
            return List.of();
        }
        if (!trusted()) { diagnostics = "Accessibility permission is unavailable"; return List.of(); }
        MemorySegment selected = window(target);
        if (MacNative.nil(selected)) { diagnostics = "Selected AX window is not uniquely identifiable"; return List.of(); }
        List<MemorySegment> queue = new ArrayList<>();
        List<Node> candidates = new ArrayList<>();
        queue.add(selected);
        long deadline = System.nanoTime() + 2_200_000_000L;
        int visited = 0;
        boolean limit = false;
        try {
            for (int cursor = 0; cursor < queue.size(); cursor++) {
                if (cursor >= 768 || System.nanoTime() >= deadline) { limit = true; break; }
                MemorySegment element = queue.get(cursor);
                if (owner(element) != target.processId()) continue;
                timeout(element, 0.15f);
                visited++;
                double[] bounds = bounds(element);
                String role = attributeText(element, "AXRole");
                if (bounds != null && owner(element) == target.processId()) {
                    int[] clipped = frameBounds(bounds, target, frame);
                    if (clipped != null) {
                        boolean protectedElement = protectedElement(element);
                        String lower = role.toLowerCase(Locale.ROOT);
                        String label = protectedElement || lower.contains("text") || lower.contains("search") ? ""
                                : attributeText(element, "AXTitle");
                        if (label.isBlank() && !protectedElement && !lower.contains("text") && !lower.contains("search"))
                            label = attributeText(element, "AXDescription");
                        int actions = actions(element);
                        candidates.add(new Node(api.retain(element), bounds, role, label, actions,
                                clipped, frame.windowGeneration(), frame.contentRevision(), null, frame));
                    }
                }
                for (String name : List.of("AXChildren", "AXVisibleChildren", "AXChildrenInNavigationOrder")) {
                    if (System.nanoTime() >= deadline) { limit = true; break; }
                    MemorySegment children = api.attribute(element, name);
                    try {
                        if (!isType(children, "CFArrayGetTypeID")) continue;
                        for (MemorySegment child : api.array(children, 1536)) {
                            if (!isType(child, "AXUIElementGetTypeID") || contains(queue, child)) continue;
                            if (queue.size() >= 1536) { limit = true; break; }
                            queue.add(api.retain(child));
                        }
                    } finally { api.release(children); }
                    if (queue.size() >= 1536) break;
                }
                if (queue.size() >= 1536) break;
            }
            candidates.sort(java.util.Comparator.comparingInt(Node::priority).reversed());
            List<DesktopElement> elements = new ArrayList<>();
            long totalRoiBytes = 0;
            for (Node candidate : candidates) {
                if (elements.size() >= 256) { api.release(candidate.element()); continue; }
                if (nextToken > 0xffff_ffffL) {
                    api.release(candidate.element()); continue;
                }
                int token = (int) nextToken++;
                String id = DesktopBridge.elementId(token);
                int[] area = candidate.frameBounds();
                long regionBytes = (long) area[2] * area[3] * 4;
                byte[] roi = null;
                if (regionBytes <= 1_048_576 && totalRoiBytes + regionBytes <= 8_388_608) {
                    roi = frame.copyBgraRegion(area[0], area[1], area[2], area[3]);
                    totalRoiBytes += regionBytes;
                }
                snapshots.put(token, candidate.withRoi(roi));
                elements.add(new DesktopElement(id, candidate.role(), candidate.label(), area[0], area[1],
                        area[2], area[3], candidate.actions()));
            }
            diagnostics = "AX visited=" + visited + " queued=" + queue.size() + " emitted=" + elements.size()
                    + " bounded=" + (limit || candidates.size() > 256) + " publicOnly=true";
            return List.copyOf(elements);
        } finally { queue.forEach(api::release); }
    }

    DesktopActionResult perform(NativeWindow target, DesktopAction action, DesktopFrame fresh, boolean targetActive, java.util.function.BooleanSupplier mayDispatch) {
        if (!MacTargetPolicy.externalProcess(target.processId()))
            return result(DENIED, action, "Self-process Accessibility input is prohibited");
        if (!trusted()) return result(DENIED, action, "Accessibility permission is required");
        if (targetActive) return result(DENIED, action,
                "Target application is active; strict background input does not take over the user's foreground");
        var admitted = DesktopSemanticAction.admit(action);
        if (admitted.isEmpty()) return result(UNSUPPORTED, action, "Observed public semantic operation is required");
        Node snapshot = snapshots.get(admitted.get().elementToken());
        if (snapshot == null || snapshot.generation() != action.windowGeneration()
                || snapshot.revision() > action.contentRevision())
            return result(STALE_FRAME, action, "Accessibility element belongs to an obsolete observation");
        int[] roi = snapshot.frameBounds();
        if (action.x() < roi[0] || action.y() < roi[1] || (long) action.x() >= (long) roi[0] + roi[2]
                || (long) action.y() >= (long) roi[1] + roi[3])
            return result(STALE_FRAME, action, "Action coordinate does not belong to the observed element");
        if (!snapshotPixelsMatch(snapshot.sourceFrame(), fresh, roi, snapshot.roi()))
            return result(STALE_FRAME, action, "Observed control pixels changed before semantic input");
        MemorySegment element = api.retain(snapshot.element());
        if (MacNative.nil(element)) return result(UNSUPPORTED, action, "No public Accessibility target at the observed point");
        try {
            timeout(element, 0.25f);
            if (!belongs(element, target)) return result(STALE_FRAME, action, "AX target is not in the selected window");
            if (!enabled(element)) return result(UNSUPPORTED, action,
                    "Disabled or unidentifiable controls are not eligible for semantic input");
            if (snapshot != null && (!sameBounds(snapshot.bounds(), bounds(element))
                    || !snapshot.role().equals(attributeText(element, "AXRole"))))
                return result(STALE_FRAME, action, "Accessibility target changed since observation");
            return switch (action.kind()) {
                case CLICK -> press(target, element, action, mayDispatch);
                case TYPE -> type(element, action, mayDispatch);
                case KEY -> result(UNSUPPORTED, action, "Background KEY input has no admitted semantic operation");
                case SCROLL -> scroll(target, element, action, mayDispatch);
            };
        } finally { api.release(element); }
    }

    private DesktopActionResult press(NativeWindow target, MemorySegment element, DesktopAction action,
            java.util.function.BooleanSupplier mayDispatch) {
        if (action.button() != 1 || action.clicks() != 1)
            return result(UNSUPPORTED, action, "AXPress supports one left click; explicit system input is required");
        MemorySegment pressable = ancestor(element, target, "AXPress");
        if (MacNative.nil(pressable)) return result(UNSUPPORTED, action, "Target does not advertise AXPress");
        try {
            if (DesktopSemanticAction.admit(action).isEmpty() || !mayDispatch.getAsBoolean())
                return result(DENIED, action, "Background admission changed before AXPress; no input was sent");
            return dispatch(pressable, "AXPress", action);
        }
        finally { api.release(pressable); }
    }

    private DesktopActionResult type(MemorySegment element, DesktopAction action,
            java.util.function.BooleanSupplier mayDispatch) {
        boolean protectedContent = protectedElement(element);
        String attribute = textAttribute(action.textOperation());
        if (!settable(element, attribute)) return result(UNSUPPORTED, action,
                "Target does not advertise the requested text operation");
        String before = protectedContent ? "" : attributeText(element, "AXValue");
        if (DesktopSemanticAction.admit(action).isEmpty() || !mayDispatch.getAsBoolean())
            return result(DENIED, action, "Background admission changed before text dispatch; no input was sent");
        if (!externalElement(element)) return result(DENIED, action,
                "Self-process or unidentifiable Accessibility input is prohibited");
        int status = (int) api.call("AXUIElementSetAttributeValue", ValueLayout.JAVA_INT,
                new MemoryLayout[]{ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS},
                element, api.string(attribute), api.string(action.text()));
        if (status != 0) return result(UNKNOWN, action, "Text dispatch returned AX error " + status + "; do not replay");
        if (protectedContent) return result(ACCEPTED, action,
                "Semantic text accepted; protected content is not read back");
        String after = attributeText(element, "AXValue");
        boolean verified = action.textOperation() == DesktopAction.TextOperation.SET_TEXT
                ? after.equals(action.text()) : !after.equals(before) && after.contains(action.text());
        return result(verified ? VERIFIED : ACCEPTED, action,
                verified ? "Semantic text verified by value readback" : "Semantic text accepted; observe the effect");
    }

    private DesktopActionResult scroll(NativeWindow target, MemorySegment element, DesktopAction action,
            java.util.function.BooleanSupplier mayDispatch) {
        String name = scrollAction(actionNames(element), action.amount());
        if (name.isEmpty()) return result(UNSUPPORTED, action, "Target does not advertise the requested scroll direction");
        MemorySegment scrollable = ancestor(element, target, name);
        if (MacNative.nil(scrollable)) return result(UNSUPPORTED, action, "Target does not advertise semantic scrolling");
        try {
            for (int i = 0; i < Math.abs(action.amount()); i++) {
                if (DesktopSemanticAction.admit(action).isEmpty() || !mayDispatch.getAsBoolean()
                        || !belongs(scrollable, target)) return result(i == 0 ? STALE_FRAME : UNKNOWN, action,
                        "Semantic scroll target changed" );
                int status = perform(scrollable, name);
                if (status != 0) return result(UNKNOWN, action, "Scroll may be partially applied; AX error " + status);
            }
            return result(ACCEPTED, action, "Semantic scroll accepted; observe the effect");
        } finally { api.release(scrollable); }
    }

    private DesktopActionResult dispatch(MemorySegment element, String name, DesktopAction action) {
        int status = perform(element, name);
        return result(status == 0 ? ACCEPTED : UNKNOWN, action, status == 0
                ? "Accessibility action accepted; observe the application effect"
                : "Accessibility action returned " + status + " after dispatch; do not replay");
    }

    int perform(MemorySegment element, String name) {
        if (!externalElement(element)) return -25201; // kAXErrorIllegalArgument, before dispatch.
        return (int) api.call("AXUIElementPerformAction", ValueLayout.JAVA_INT,
                new MemoryLayout[]{ValueLayout.ADDRESS, ValueLayout.ADDRESS}, element, api.string(name));
    }

    MemorySegment application(long pid) {
        if (!MacTargetPolicy.externalProcess(pid)) return MemorySegment.NULL;
        MemorySegment app = (MemorySegment) api.call("AXUIElementCreateApplication", ValueLayout.ADDRESS,
                new MemoryLayout[]{ValueLayout.JAVA_INT}, (int) pid);
        if (!MacNative.nil(app)) timeout(app, 0.3f);
        return app;
    }

    MemorySegment window(NativeWindow target) {
        return window(target, System.nanoTime() + 1_500_000_000L);
    }

    private MemorySegment window(NativeWindow target, long deadline) {
        if (!MacTargetPolicy.externalProcess(target.processId())) return MemorySegment.NULL;
        MemorySegment app = application(target.processId());
        List<MemorySegment> candidates = windowCandidates(app, deadline);
        api.release(app);
        try {
            if (candidates.size() > 128) return MemorySegment.NULL;
            List<MemorySegment> matches = new ArrayList<>();
            for (MemorySegment candidate : candidates) {
                if (System.nanoTime() >= deadline) return MemorySegment.NULL;
                timeout(candidate, 0.025f);
                if (owner(candidate) == target.processId() && sameBounds(bounds(candidate), target)) matches.add(candidate);
            }
            return matches.size() == 1 ? api.retain(matches.getFirst()) : MemorySegment.NULL;
        } finally { candidates.forEach(api::release); }
    }

    private List<MemorySegment> windowCandidates(MemorySegment app, long deadline) {
        List<MemorySegment> candidates = new ArrayList<>();
        if (!externalElement(app)) return candidates;
        MemorySegment array = api.attribute(app, "AXWindows");
        try {
            if (!isType(array, "CFArrayGetTypeID")) return candidates;
            if (api.number(array, "count") > 64) return candidates;
            for (MemorySegment value : api.array(array, 64))
                if (isType(value, "AXUIElementGetTypeID") && externalElement(value))
                    candidates.add(api.retain(value));
        } finally { api.release(array); }
        for (int i = 0; i < candidates.size() && candidates.size() <= 128; i++) {
            if (System.nanoTime() >= deadline) return candidates;
            MemorySegment parent = candidates.get(i);
            timeout(parent, 0.025f);
            for (String attribute : List.of("AXSheets", "AXChildren")) {
                if (System.nanoTime() >= deadline) return candidates;
                MemorySegment children = api.attribute(parent, attribute);
                try {
                    if (!isType(children, "CFArrayGetTypeID")) continue;
                    for (MemorySegment child : api.array(children, 129)) {
                        if (!isType(child, "AXUIElementGetTypeID") || !externalElement(child)
                                || contains(candidates, child)) continue;
                        timeout(child, 0.025f);
                        String role = attributeText(child, "AXRole");
                        if (role.equals("AXWindow") || role.equals("AXSheet") || role.equals("AXDialog"))
                            candidates.add(api.retain(child));
                        if (candidates.size() > 128) return candidates;
                    }
                } finally { api.release(children); }
            }
        }
        return candidates;
    }

    List<NativeWindow> decorate(List<NativeWindow> windows) { return decorate(windows, windows); }

    NativeWindow decorateOne(NativeWindow target, List<NativeWindow> allWindows) {
        return decorate(List.of(target), allWindows).getFirst();
    }

    private List<NativeWindow> decorate(List<NativeWindow> windows, List<NativeWindow> allWindows) {
        if (windows.stream().noneMatch(window -> MacTargetPolicy.externalProcess(window.processId()))
                || !trusted()) return List.copyOf(windows);
        List<NativeWindow> decorated = new ArrayList<>();
        long deadline = System.nanoTime() + 500_000_000L;
        for (NativeWindow target : windows) {
            if (!MacTargetPolicy.externalProcess(target.processId()) || System.nanoTime() >= deadline) {
                decorated.add(target); continue;
            }
            MemorySegment selected = window(target, Math.min(deadline, System.nanoTime() + 120_000_000L));
            if (MacNative.nil(selected)) { decorated.add(target); continue; }
            try {
                int flags = target.flags();
                if ((flags & 2) == 0 && booleanAttribute(selected, "AXMinimized").orElse(false)) flags |= 1;
                String role = attributeText(selected, "AXRole");
                if (role.equals("AXSheet") || role.equals("AXDialog")) flags |= 4;
                MemorySegment parent = api.attribute(selected, "AXParent");
                long parentId = 0;
                try {
                    if (!MacNative.nil(parent) && owner(parent) == target.processId()
                            && attributeText(parent, "AXRole").equals("AXWindow")) {
                        double[] parentBounds = bounds(parent);
                        List<NativeWindow> matches = allWindows.stream().filter(candidate ->
                                candidate.processId() == target.processId()
                                        && candidate.processInstanceId() == target.processInstanceId()
                                        && candidate.windowId() != target.windowId()
                                        && sameBounds(parentBounds, candidate)).toList();
                        if (matches.size() == 1 && System.nanoTime() < deadline) parentId = matches.getFirst().windowId();
                    }
                } finally { api.release(parent); }
                decorated.add(new NativeWindow(target.processId(), target.windowId(), target.processInstanceId(),
                        target.x(), target.y(), target.width(), target.height(), flags, target.application(),
                        target.title(), target.applicationId(), parentId, parentId == 0 ? 0 : 1));
            } finally { api.release(selected); }
        }
        return List.copyOf(decorated);
    }

    boolean belongs(MemorySegment element, NativeWindow target) {
        if (!MacTargetPolicy.externalProcess(target.processId())) return false;
        if (owner(element) != target.processId()) return false;
        MemorySegment selected = window(target);
        if (MacNative.nil(selected)) return false;
        MemorySegment current = api.retain(element);
        try {
            for (int depth = 0; !MacNative.nil(current) && depth < 12; depth++) {
                if (owner(current) != target.processId()) return false;
                if (equal(current, selected)) return true;
                MemorySegment actualWindow = api.attribute(current, "AXWindow");
                try { if (!MacNative.nil(actualWindow) && equal(actualWindow, selected)) return true; }
                finally { api.release(actualWindow); }
                MemorySegment parent = api.attribute(current, "AXParent");
                api.release(current); current = parent;
            }
            return false;
        } finally { api.release(current); api.release(selected); }
    }

    boolean focused(NativeWindow target) {
        if (!MacTargetPolicy.externalProcess(target.processId())) return false;
        MemorySegment app = application(target.processId());
        MemorySegment focused = api.attribute(app, "AXFocusedWindow");
        api.release(app);
        MemorySegment selected = window(target);
        try { return !MacNative.nil(focused) && !MacNative.nil(selected) && equal(focused, selected); }
        finally { api.release(focused); api.release(selected); }
    }

    private MemorySegment ancestor(MemorySegment element, NativeWindow target, String action) {
        MemorySegment current = api.retain(element);
        for (int depth = 0; !MacNative.nil(current) && depth < 8; depth++) {
            if (!belongs(current, target)) { api.release(current); return MemorySegment.NULL; }
            if (actionNames(current).contains(action)) return current;
            MemorySegment parent = api.attribute(current, "AXParent");
            api.release(current); current = parent;
        }
        api.release(current); return MemorySegment.NULL;
    }

    double[] bounds(MemorySegment element) {
        MemorySegment position = api.attribute(element, "AXPosition");
        MemorySegment size = api.attribute(element, "AXSize");
        try (Arena arena = Arena.ofConfined()) {
            if (!isType(position, "AXValueGetTypeID") || !isType(size, "AXValueGetTypeID")) return null;
            MemorySegment point = arena.allocate(MacNative.POINT);
            MemorySegment dimensions = arena.allocate(MacNative.POINT);
            byte p = (byte) api.call("AXValueGetValue", ValueLayout.JAVA_BYTE,
                    new MemoryLayout[]{ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS}, position, 1, point);
            byte s = (byte) api.call("AXValueGetValue", ValueLayout.JAVA_BYTE,
                    new MemoryLayout[]{ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS}, size, 2, dimensions);
            if (p == 0 || s == 0) return null;
            double[] bounds = {point.get(ValueLayout.JAVA_DOUBLE, 0), point.get(ValueLayout.JAVA_DOUBLE, 8),
                    dimensions.get(ValueLayout.JAVA_DOUBLE, 0), dimensions.get(ValueLayout.JAVA_DOUBLE, 8)};
            for (double value : bounds) if (!Double.isFinite(value)) return null;
            return bounds;
        } finally { api.release(position); api.release(size); }
    }

    private long owner(MemorySegment element) {
        return api.accessibilityOwner(element);
    }

    private boolean externalElement(MemorySegment element) {
        return MacTargetPolicy.externalProcess(owner(element));
    }

    private void timeout(MemorySegment element, float seconds) {
        if (!externalElement(element)) return;
        api.call("AXUIElementSetMessagingTimeout", ValueLayout.JAVA_INT,
                new MemoryLayout[]{ValueLayout.ADDRESS, ValueLayout.JAVA_FLOAT}, element, seconds);
    }

    private boolean settable(MemorySegment element, String name) {
        if (!externalElement(element)) return false;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment value = arena.allocate(ValueLayout.JAVA_BYTE);
            int status = (int) api.call("AXUIElementIsAttributeSettable", ValueLayout.JAVA_INT,
                    new MemoryLayout[]{ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS},
                    element, api.string(name), value);
            return status == 0 && value.get(ValueLayout.JAVA_BYTE, 0) != 0;
        }
    }

    private int actions(MemorySegment element) {
        if (!enabled(element)) return 0;
        List<String> names = actionNames(element);
        int flags = names.contains("AXPress") ? DesktopElement.PRESS : 0;
        if (names.contains("AXScrollDownByPage") || names.contains("AXScrollUpByPage")
                || names.contains("AXIncrement") || names.contains("AXDecrement")) flags |= DesktopElement.SCROLL;
        if (enabled(element)) {
            if (settable(element, "AXSelectedText")) flags |= DesktopElement.WRITE | DesktopElement.INSERT_TEXT;
            if (settable(element, "AXValue")) flags |= DesktopElement.WRITE | DesktopElement.SET_TEXT;
        }
        return flags;
    }

    private boolean protectedElement(MemorySegment element) {
        return protectedRole(attributeText(element, "AXRole"), attributeText(element, "AXSubrole"))
                || booleanAttribute(element, "AXProtectedContent").orElse(false);
    }

    static boolean protectedRole(String role, String subrole) {
        return role.toLowerCase(Locale.ROOT).contains("secure")
                || subrole.toLowerCase(Locale.ROOT).contains("secure");
    }

    private boolean enabled(MemorySegment element) {
        return booleanAttribute(element, "AXEnabled").orElse(true);
    }

    private java.util.Optional<Boolean> booleanAttribute(MemorySegment element, String name) {
        MemorySegment value = api.attribute(element, name);
        try {
            if (!isType(value, "CFBooleanGetTypeID")) return java.util.Optional.empty();
            return java.util.Optional.of((byte) api.call("CFBooleanGetValue", ValueLayout.JAVA_BYTE,
                    new MemoryLayout[]{ValueLayout.ADDRESS}, value) != 0);
        } finally { api.release(value); }
    }

    private List<String> actionNames(MemorySegment element) {
        if (!externalElement(element)) return List.of();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(ValueLayout.ADDRESS);
            int status = (int) api.call("AXUIElementCopyActionNames", ValueLayout.JAVA_INT,
                    new MemoryLayout[]{ValueLayout.ADDRESS, ValueLayout.ADDRESS}, element, out);
            MemorySegment names = out.get(ValueLayout.ADDRESS, 0);
            try {
                return status == 0 && isType(names, "CFArrayGetTypeID")
                        ? api.array(names, 64).stream().filter(value -> isType(value, "CFStringGetTypeID"))
                                .map(api::text).toList() : List.of();
            } finally { api.release(names); }
        }
    }

    private String attributeText(MemorySegment element, String name) {
        MemorySegment value = api.attribute(element, name);
        try { return isType(value, "CFStringGetTypeID") ? api.text(value) : ""; }
        finally { api.release(value); }
    }

    private boolean isType(MemorySegment value, String expected) {
        if (MacNative.nil(value)) return false;
        long actual = (long) api.call("CFGetTypeID", ValueLayout.JAVA_LONG,
                new MemoryLayout[]{ValueLayout.ADDRESS}, value);
        long type = (long) api.call(expected, ValueLayout.JAVA_LONG, new MemoryLayout[0]);
        return actual == type;
    }

    private boolean equal(MemorySegment first, MemorySegment second) {
        return (byte) api.call("CFEqual", ValueLayout.JAVA_BYTE,
                new MemoryLayout[]{ValueLayout.ADDRESS, ValueLayout.ADDRESS}, first, second) != 0;
    }

    private boolean contains(List<MemorySegment> values, MemorySegment value) {
        return values.stream().anyMatch(existing -> equal(existing, value));
    }

    static boolean snapshotPixelsMatch(DesktopFrame observed, DesktopFrame fresh, int[] area, byte[] roi) {
        if (observed == null || fresh == null || observed.windowGeneration() != fresh.windowGeneration()
                || observed.width() != fresh.width() || observed.height() != fresh.height()
                || observed.stride() != fresh.stride() || !java.util.Objects.equals(observed.geometry(), fresh.geometry()))
            return false;
        return roi == null ? java.util.Arrays.equals(observed.bgraPremultiplied(), fresh.bgraPremultiplied())
                : fresh.matchesBgraRegion(area[0], area[1], area[2], area[3], roi);
    }

    static String textAttribute(DesktopAction.TextOperation operation) {
        return operation == DesktopAction.TextOperation.SET_TEXT ? "AXValue" : "AXSelectedText";
    }

    static String scrollAction(List<String> available, int amount) {
        for (String candidate : amount > 0 ? List.of("AXIncrement", "AXScrollDownByPage")
                : List.of("AXDecrement", "AXScrollUpByPage"))
            if (available.contains(candidate)) return candidate;
        return "";
    }

    static int[] frameBounds(double[] bounds, NativeWindow target, DesktopFrame frame) {
        if (bounds == null || bounds.length != 4 || frame.geometry() == null || bounds[2] <= 0 || bounds[3] <= 0)
            return null;
        for (double value : bounds) if (!Double.isFinite(value)) return null;
        double scaleX = frame.width() / frame.geometry().logicalWidth();
        double scaleY = frame.height() / frame.geometry().logicalHeight();
        int x = Math.max(0, (int) Math.floor((bounds[0] - target.x()) * scaleX));
        int y = Math.max(0, (int) Math.floor((bounds[1] - target.y()) * scaleY));
        int right = Math.min(frame.width(), (int) Math.ceil((bounds[0] + bounds[2] - target.x()) * scaleX));
        int bottom = Math.min(frame.height(), (int) Math.ceil((bounds[1] + bounds[3] - target.y()) * scaleY));
        return right > x && bottom > y ? new int[]{x, y, right - x, bottom - y} : null;
    }

    static boolean sameBounds(double[] first, double[] second) {
        if (first == null || second == null || first.length != 4 || second.length != 4) return false;
        for (int i = 0; i < 4; i++)
            if (!Double.isFinite(first[i]) || !Double.isFinite(second[i]) || Math.abs(first[i] - second[i]) > 2)
                return false;
        return true;
    }
    static boolean sameBounds(double[] bounds, NativeWindow target) {
        return sameBounds(bounds, new double[]{target.x(), target.y(), target.width(), target.height()});
    }

    private DesktopActionResult result(DesktopActionResult.Status status, DesktopAction action, String detail) {
        return new DesktopActionResult(status, detail, action.windowGeneration()).withContext(
                DesktopActionResult.Mode.BACKGROUND_SEMANTIC, action.observationId(),
                status == UNKNOWN ? DesktopActionResult.NextStep.RECONCILE : DesktopActionResult.NextStep.OBSERVE);
    }

    String diagnostics() { return diagnostics; }
    private void clear() { snapshots.values().forEach(node -> api.release(node.element())); snapshots.clear(); }
    @Override public void close() { clear(); }
    private record Node(MemorySegment element, double[] bounds, String role, String label, int actions,
                        int[] frameBounds, long generation, long revision, byte[] roi, DesktopFrame sourceFrame) {
        Node withRoi(byte[] pixels) {
            return new Node(element, bounds, role, label, actions, frameBounds, generation, revision,
                    pixels, sourceFrame);
        }
        int priority() { return ((actions & DesktopElement.PRESS) != 0 ? 100 : 0)
                + ((actions & DesktopElement.WRITE) != 0 ? 80 : 0)
                + ((actions & DesktopElement.SCROLL) != 0 ? 60 : 0) + (label.isEmpty() ? 0 : 20); }
    }
}
