package com.javaclaw.desktop.ffm.windows;

import java.lang.foreign.Arena;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import static com.javaclaw.desktop.ffm.windows.Win32.*;

/** Direct Windows Graphics Capture and D3D11 staging readback, on one MTA carrier thread. */
final class WindowsCapture implements AutoCloseable {
    private static final String ITEM_IID = "79c3f95b-31f7-4ec2-a464-632ef5d30760";
    private static final String CLOSABLE = "30d5a829-7fa4-4026-83bb-d75bae4ea99e";
    private final Win32 win;
    private final ExecutorService worker;
    private MemorySegment device = MemorySegment.NULL, context = MemorySegment.NULL;
    private MemorySegment runtimeDevice = MemorySegment.NULL, item = MemorySegment.NULL;
    private MemorySegment pool = MemorySegment.NULL, session = MemorySegment.NULL;
    private long window;
    private int width, height;
    private volatile boolean closed;
    private volatile int apartmentFailure;
    private volatile String diagnostic = "Windows Graphics Capture has not started";
    private Future<?> inFlight;
    private final AtomicBoolean itemClosed = new AtomicBoolean();
    private WinRtClosedHandler closedHandler;
    private long closedToken;

    WindowsCapture(Win32 win) {
        this.win = win;
        worker = Executors.newSingleThreadExecutor(task -> Thread.ofPlatform().daemon()
                .name("desktop-windows-capture").unstarted(() -> {
                    int initialized = win.integer("combase", "RoInitialize", new MemoryLayout[]{I}, 1);
                    try {
                        if (initialized < 0) apartmentFailure = initialized;
                        task.run();
                    } finally {
                        if (initialized >= 0) win.nothing("combase", "RoUninitialize", new MemoryLayout[0]);
                    }
                }));
    }

    synchronized void bind(long hwnd, int requestedWidth, int requestedHeight) {
        Future<?> binding = worker.submit(() -> {
            check(apartmentFailure, "WinRT MTA initialization");
            start(hwnd, requestedWidth, requestedHeight);
        });
        inFlight = binding;
        try { binding.get(3000, TimeUnit.MILLISECONDS); }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            close();
            throw new IllegalStateException("Windows capture binding interrupted", interrupted);
        } catch (ExecutionException | TimeoutException failure) {
            close();
            throw new IllegalStateException("Windows capture could not bind the selected window", failure);
        }
    }

    boolean targetClosed() { return itemClosed.get(); }

    static boolean supported(Win32 win) {
        int initialized = win.integer("combase", "RoInitialize", new MemoryLayout[]{I}, 1);
        if (initialized < 0 && initialized != 0x80010106) return false;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(P);
            String name = "Windows.Graphics.Capture.GraphicsCaptureSession";
            if (win.integer("combase", "WindowsCreateString", new MemoryLayout[]{P, I, P}, wide(arena, name), name.length(), out) < 0) return false;
            MemorySegment hstring = out.get(P, 0);
            try {
                if (win.integer("combase", "RoGetActivationFactory", new MemoryLayout[]{P, P, P}, hstring,
                        guid(arena, "2224a540-5974-49aa-b232-0882536f4cb5"), out) < 0) return false;
                MemorySegment factory = out.get(P, 0);
                try {
                    MemorySegment supported = arena.allocate(I);
                    return win.com(factory, 6, new MemoryLayout[]{P}, supported) >= 0 && supported.get(I, 0) != 0;
                } finally { win.release(factory); }
            } finally { win.integer("combase", "WindowsDeleteString", new MemoryLayout[]{P}, hstring); }
        } finally { if (initialized >= 0) win.nothing("combase", "RoUninitialize", new MemoryLayout[0]); }
    }

    synchronized Optional<Pixels> capture(long hwnd, int requestedWidth, int requestedHeight, int timeoutMillis) {
        if (closed || itemClosed.get()) return Optional.empty();
        int timeout = Math.clamp(timeoutMillis, 1, 10000);
        if (inFlight != null && !inFlight.isDone()) {
            diagnostic = "A previous capture is still pending; no additional native capture was queued";
            return Optional.empty();
        }
        Future<Optional<Pixels>> operation = worker.submit(() -> captureOnWorker(hwnd, requestedWidth, requestedHeight, timeout));
        inFlight = operation;
        try { return operation.get(timeout + 500L, TimeUnit.MILLISECONDS); }
        catch (TimeoutException timeoutFailure) {
            diagnostic = "Windows capture timed out; no frame was fabricated";
            return Optional.empty();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (ExecutionException failure) {
            diagnostic = "Windows Graphics Capture failed: " + failure.getCause().getMessage();
            throw new IllegalStateException(diagnostic, failure.getCause());
        }
    }

    private Optional<Pixels> captureOnWorker(long hwnd, int requestedWidth, int requestedHeight, int timeout) {
        if (closed || itemClosed.get()) return Optional.empty();
        check(apartmentFailure, "WinRT MTA initialization");
        validateSize(requestedWidth, requestedHeight);
        if (window != hwnd || width != requestedWidth || height != requestedHeight || nullPointer(pool))
            start(hwnd, requestedWidth, requestedHeight);
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeout);
        do {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment out = arena.allocate(P);
                check(win.com(pool, 7, new MemoryLayout[]{P}, out), "TryGetNextFrame");
                MemorySegment frame = out.get(P, 0);
                if (!nullPointer(frame)) {
                    // Drain queued frames, keeping the newest native receipt rather than retimestamping old pixels.
                    for (int count = 0; count < 4; count++) {
                        out.set(P, 0, MemorySegment.NULL);
                        int status = win.com(pool, 7, new MemoryLayout[]{P}, out);
                        MemorySegment newer = out.get(P, 0);
                        if (status < 0 || nullPointer(newer)) break;
                        closeObject(frame);
                        frame = newer;
                    }
                    try {
                        Pixels pixels = read(frame, arena);
                        diagnostic = "Windows Graphics Capture / Direct3D11 window frame";
                        return Optional.of(pixels);
                    } finally { closeObject(frame); }
                }
            }
            if (Thread.currentThread().isInterrupted()) return Optional.empty();
            java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
        } while (!closed && System.nanoTime() < deadline);
        diagnostic = "Windows Graphics Capture has not delivered a window frame";
        return Optional.empty();
    }

    private void start(long hwnd, int requestedWidth, int requestedHeight) {
        if (itemClosed.get()) throw new IllegalStateException("The bound capture item was destroyed; the session cannot rebind");
        if (window != 0 && hwnd != window) throw new IllegalStateException("A capture session cannot switch native window handles");
        releasePool();
        try (Arena arena = Arena.ofConfined()) {
            if (nullPointer(device)) createDevice(arena);
            MemorySegment out = arena.allocate(P);
            MemorySegment factory;
            if (nullPointer(item)) {
                factory = activation(arena, "Windows.Graphics.Capture.GraphicsCaptureItem",
                        "3628e81b-3cac-4c60-b7f4-23ce0e0c3356");
                try {
                    check(win.com(factory, 3, new MemoryLayout[]{P, P, P}, handle(hwnd), guid(arena, ITEM_IID), out), "CreateForWindow");
                    item = out.get(P, 0);
                } finally { win.release(factory); }
                closedHandler = new WinRtClosedHandler(itemClosed);
                MemorySegment token = arena.allocate(L);
                check(win.com(item, 8, new MemoryLayout[]{P, P}, closedHandler.address(), token), "CaptureItem.Closed subscribe");
                closedToken = token.get(L, 0);
                window = hwnd;
            }
            MemorySegment itemSize = arena.allocate(8, 4);
            check(win.com(item, 7, new MemoryLayout[]{P}, itemSize), "CaptureItem.Size");
            int captureWidth = itemSize.get(I, 0), captureHeight = itemSize.get(I, 4);
            validateSize(captureWidth, captureHeight);
            factory = activation(arena, "Windows.Graphics.Capture.Direct3D11CaptureFramePool",
                    "589b103f-6bbc-5df5-a991-02e28b3b66d5");
            try {
                // SizeInt32 is a pair of int32 values, passed by value as one 64-bit register on Windows.
                long size = Integer.toUnsignedLong(captureWidth) | ((long) captureHeight << 32);
                check(win.com(factory, 6, new MemoryLayout[]{P, I, I, L, P}, runtimeDevice, 87, 2, size, out), "CreateFreeThreaded");
                pool = out.get(P, 0);
            } finally { win.release(factory); }
            check(win.com(pool, 10, new MemoryLayout[]{P, P}, item, out), "CreateCaptureSession");
            session = out.get(P, 0);
            out.set(P, 0, MemorySegment.NULL);
            int cursorInterface = win.com(session, 0, new MemoryLayout[]{P, P},
                    guid(arena, "2c39ae40-7d2e-5044-804e-8b6799d4cf9e"), out);
            if (cursorInterface >= 0) {
                MemorySegment session2 = out.get(P, 0);
                try { check(win.com(session2, 7, new MemoryLayout[]{java.lang.foreign.ValueLayout.JAVA_BYTE}, (byte) 0), "Disable cursor capture"); }
                finally { win.release(session2); }
            } else if (cursorInterface != 0x80004002) check(cursorInterface, "CaptureSession2.QueryInterface");
            check(win.com(session, 6, new MemoryLayout[0]), "StartCapture");
            width = requestedWidth;
            height = requestedHeight;
        } catch (RuntimeException | Error failure) {
            releasePool();
            if (nullPointer(runtimeDevice)) {
                win.release(context); context = MemorySegment.NULL;
                win.release(device); device = MemorySegment.NULL;
            }
            throw failure;
        }
    }

    private void createDevice(Arena arena) {
        MemorySegment outDevice = arena.allocate(P), outContext = arena.allocate(P), level = arena.allocate(I);
        int status = win.integer("d3d11", "D3D11CreateDevice", new MemoryLayout[]{P, I, P, I, P, I, I, P, P, P},
                MemorySegment.NULL, 1, MemorySegment.NULL, 0x20, MemorySegment.NULL, 0, 7, outDevice, level, outContext);
        if (status < 0) status = win.integer("d3d11", "D3D11CreateDevice", new MemoryLayout[]{P, I, P, I, P, I, I, P, P, P},
                MemorySegment.NULL, 5, MemorySegment.NULL, 0x20, MemorySegment.NULL, 0, 7, outDevice, level, outContext);
        check(status, "D3D11CreateDevice");
        device = outDevice.get(P, 0);
        context = outContext.get(P, 0);
        MemorySegment dxgi = query(arena, device, "54ec77fa-1377-44e6-8c32-88fd5f44c84c");
        try {
            check(win.integer("d3d11", "CreateDirect3D11DeviceFromDXGIDevice", new MemoryLayout[]{P, P}, dxgi, outDevice), "CreateDirect3D11DeviceFromDXGIDevice");
            // The system function returns IInspectable; QI obtains the exact IDirect3DDevice ABI.
            MemorySegment inspectable = outDevice.get(P, 0);
            try { runtimeDevice = query(arena, inspectable, "a37624ab-8d5f-4650-9d3e-9eae3d9bc670"); }
            finally { win.release(inspectable); }
        } finally { win.release(dxgi); }
    }

    private Pixels read(MemorySegment frame, Arena arena) {
        MemorySegment size = arena.allocate(8, 4), time = arena.allocate(L), out = arena.allocate(P);
        check(win.com(frame, 8, new MemoryLayout[]{P}, size), "Frame.ContentSize");
        int frameWidth = size.get(I, 0), frameHeight = size.get(I, 4);
        validateSize(frameWidth, frameHeight);
        check(win.com(frame, 7, new MemoryLayout[]{P}, time), "Frame.SystemRelativeTime");
        long timestamp = frameTimestamp(win, arena, time.get(L, 0));
        check(win.com(frame, 6, new MemoryLayout[]{P}, out), "Frame.Surface");
        MemorySegment surface = out.get(P, 0);
        MemorySegment access = MemorySegment.NULL, texture = MemorySegment.NULL, staging = MemorySegment.NULL;
        try {
            access = query(arena, surface, "a9b3d012-3df2-4ee3-b8d1-8695f457d3c1");
            check(win.com(access, 3, new MemoryLayout[]{P, P}, guid(arena, "6f15aaf2-d208-4e89-9ab4-489535d34f9c"), out), "DXGI.GetInterface");
            texture = out.get(P, 0);
            MemorySegment description = arena.allocate(44, 4);
            win.comVoid(texture, 10, new MemoryLayout[]{P}, description);
            if (description.get(I, 0) < frameWidth || description.get(I, 4) < frameHeight || description.get(I, 16) != 87)
                throw new IllegalStateException("WGC texture dimensions or BGRA format invalid");
            validateSize(description.get(I, 0), description.get(I, 4));
            description.set(I, 28, 3); // D3D11_USAGE_STAGING
            description.set(I, 32, 0);
            description.set(I, 36, 0x20000); // D3D11_CPU_ACCESS_READ
            description.set(I, 40, 0);
            check(win.com(device, 5, new MemoryLayout[]{P, P, P}, description, MemorySegment.NULL, out), "CreateTexture2D");
            staging = out.get(P, 0);
            win.comVoid(context, 47, new MemoryLayout[]{P, P}, staging, texture);
            MemorySegment mapped = arena.allocate(16, 8);
            check(win.com(context, 14, new MemoryLayout[]{P, I, I, I, P}, staging, 0, 1, 0, mapped), "D3D11.Map");
            try {
                int rowPitch = mapped.get(I, 8);
                if (rowPitch < (long) frameWidth * 4 || rowPitch > 1024 * 1024)
                    throw new IllegalStateException("D3D11 row pitch invalid");
                MemorySegment pixels = mapped.get(P, 0).reinterpret((long) rowPitch * frameHeight);
                byte[] copy = new byte[frameWidth * frameHeight * 4];
                for (int row = 0; row < frameHeight; row++)
                    MemorySegment.copy(pixels, (long) row * rowPitch, MemorySegment.ofArray(copy), (long) row * frameWidth * 4, (long) frameWidth * 4);
                // Window-capture alpha is not authoritative; publish opaque premultiplied BGRA.
                for (int index = 3; index < copy.length; index += 4) copy[index] = (byte) 255;
                return new Pixels(frameWidth, frameHeight, copy, timestamp);
            } finally { win.comVoid(context, 15, new MemoryLayout[]{P, I}, staging, 0); }
        } finally { win.release(staging); win.release(texture); win.release(access); win.release(surface); }
    }

    static void validateSize(int width, int height) {
        if (width < 1 || height < 1 || (long) width * height > (128L * 1024 * 1024) / 4)
            throw new IllegalStateException("Window frame exceeds the bounded BGRA size");
    }

    private static long frameTimestamp(Win32 win, Arena arena, long hundredNanoseconds) {
        MemorySegment counter = arena.allocate(L), frequency = arena.allocate(L);
        if (win.integer("kernel32", "QueryPerformanceCounter", new MemoryLayout[]{P}, counter) == 0
                || win.integer("kernel32", "QueryPerformanceFrequency", new MemoryLayout[]{P}, frequency) == 0
                || frequency.get(L, 0) <= 0) throw new IllegalStateException("Capture timebase is unavailable");
        double elapsedMillis = counter.get(L, 0) * 1000d / frequency.get(L, 0) - hundredNanoseconds / 10000d;
        if (!Double.isFinite(elapsedMillis) || elapsedMillis < -1000)
            throw new IllegalStateException("Capture timestamp is invalid");
        return System.currentTimeMillis() - Math.max(0, (long) elapsedMillis);
    }

    private MemorySegment activation(Arena arena, String name, String iid) {
        MemorySegment out = arena.allocate(P);
        check(win.integer("combase", "WindowsCreateString", new MemoryLayout[]{P, I, P}, wide(arena, name), name.length(), out), "WindowsCreateString");
        MemorySegment hstring = out.get(P, 0);
        try {
            check(win.integer("combase", "RoGetActivationFactory", new MemoryLayout[]{P, P, P}, hstring, guid(arena, iid), out), "RoGetActivationFactory");
            return out.get(P, 0);
        } finally { win.integer("combase", "WindowsDeleteString", new MemoryLayout[]{P}, hstring); }
    }

    private MemorySegment query(Arena arena, MemorySegment object, String iid) {
        MemorySegment out = arena.allocate(P);
        check(win.com(object, 0, new MemoryLayout[]{P, P}, guid(arena, iid), out), "QueryInterface");
        return out.get(P, 0);
    }

    private void closeObject(MemorySegment object) {
        if (nullPointer(object)) return;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(P);
            if (win.com(object, 0, new MemoryLayout[]{P, P}, guid(arena, CLOSABLE), out) >= 0) {
                MemorySegment closable = out.get(P, 0);
                try { win.com(closable, 6, new MemoryLayout[0]); }
                finally { win.release(closable); }
            }
        } finally { win.release(object); }
    }

    private void releasePool() {
        closeObject(session); session = MemorySegment.NULL;
        closeObject(pool); pool = MemorySegment.NULL;
    }

    private void releaseCapture() {
        releasePool();
        if (closedHandler != null) {
            try { if (!nullPointer(item)) win.com(item, 9, new MemoryLayout[]{L}, closedToken); }
            finally { closedHandler.releaseOwner(); closedHandler = null; }
        }
        win.release(item); item = MemorySegment.NULL;
        window = 0;
    }

    private static void check(int result, String operation) {
        if (result == 0x80070005) throw new SecurityException(operation + " was denied by Windows");
        if (result < 0) throw new IllegalStateException(operation + " failed: HRESULT 0x" + Integer.toHexString(result));
    }

    String diagnostics() { return diagnostic; }
    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        Future<?> cleanup = worker.submit(() -> {
            releaseCapture();
            win.release(runtimeDevice); runtimeDevice = MemorySegment.NULL;
            win.release(context); context = MemorySegment.NULL;
            win.release(device); device = MemorySegment.NULL;
        });
        worker.shutdown();
        try { cleanup.get(1000, TimeUnit.MILLISECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        catch (ExecutionException | TimeoutException pending) {
            diagnostic = "Capture closed; native cleanup remains scheduled on its owning MTA thread";
        }
    }
    record Pixels(int width, int height, byte[] bgra, long capturedAtMillis) { }
}
