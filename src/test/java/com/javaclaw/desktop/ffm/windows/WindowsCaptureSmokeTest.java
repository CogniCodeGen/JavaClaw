package com.javaclaw.desktop.ffm.windows;

import com.javaclaw.desktop.api.DesktopInputPolicy;
import java.lang.foreign.Arena;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import static com.javaclaw.desktop.ffm.windows.Win32.*;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in Windows acceptance: captures only a window owned by this test, without delivering input. */
@EnabledOnOs(OS.WINDOWS)
@EnabledIfSystemProperty(named = "javaclaw.windows.ffm.smoke", matches = "true")
class WindowsCaptureSmokeTest {
    @Test void capturesItsOwnWindowAndPermanentlyRejectsItsDestruction() throws Exception {
        Win32 win = new Win32();
        try (Fixture fixture = new Fixture(win)) {
            WindowsDesktopApi api = new WindowsDesktopApi();
            assertTrue(api.availability(false, DesktopInputPolicy.BACKGROUND_STRICT).available());
            var window = api.windows().stream().filter(candidate -> candidate.windowId() == fixture.hwnd).findFirst().orElseThrow();
            try (var session = api.open(window)) {
                var frame = session.capture("windows-test-window", 3000).orElseThrow();
                assertTrue(frame.width() > 0 && frame.height() > 0);
                assertTrue(frame.capturedAtMillis() <= System.currentTimeMillis());
                assertTrue(System.currentTimeMillis() - frame.capturedAtMillis() < 2500);
                boolean visiblePixels = false;
                byte[] pixels = frame.bgraPremultiplied();
                for (int index = 0; index < pixels.length; index += 4)
                    if (pixels[index] != 0 || pixels[index + 1] != 0 || pixels[index + 2] != 0) { visiblePixels = true; break; }
                assertTrue(visiblePixels, "The real fixture must contain rendered pixels");
                fixture.close();
                fixture.thread.join(2000);
                assertFalse(fixture.thread.isAlive());
                assertThrows(IllegalStateException.class, session::current);
            }
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final Win32 win;
        private final Thread thread;
        private final long hwnd;
        private boolean closed;

        Fixture(Win32 win) throws Exception {
            this.win = win;
            CompletableFuture<Long> ready = new CompletableFuture<>();
            thread = Thread.ofPlatform().daemon().name("windows-ffm-capture-fixture").start(() -> {
                MemorySegment window = MemorySegment.NULL;
                try (Arena arena = Arena.ofConfined()) {
                    MemorySegment module = win.pointer("kernel32", "GetModuleHandleW", new MemoryLayout[]{P}, MemorySegment.NULL);
                    window = win.pointer("user32", "CreateWindowExW", new MemoryLayout[]{I, P, P, I, I, I, I, I, P, P, P, P},
                            0, wide(arena, "STATIC"), wide(arena, "Java FFM capture fixture"), 0x10cf0000,
                            100, 100, 480, 320, MemorySegment.NULL, MemorySegment.NULL, module, MemorySegment.NULL);
                    if (nullPointer(window)) throw new IllegalStateException("Could not create the fixture window");
                    win.integer("user32", "UpdateWindow", new MemoryLayout[]{P}, window);
                    ready.complete(window.address());
                    MemorySegment message = arena.allocate(48, 8);
                    while (win.integer("user32", "GetMessageW", new MemoryLayout[]{P, P, I, I}, message, MemorySegment.NULL, 0, 0) > 0) {
                        win.integer("user32", "TranslateMessage", new MemoryLayout[]{P}, message);
                        invoke(win.function("user32", "DispatchMessageW", L, P), message);
                        if (win.integer("user32", "IsWindow", new MemoryLayout[]{P}, window) == 0) break;
                    }
                } catch (Throwable failure) { ready.completeExceptionally(failure); }
                finally { if (!nullPointer(window)) win.integer("user32", "DestroyWindow", new MemoryLayout[]{P}, window); }
            });
            hwnd = ready.get(3000, TimeUnit.MILLISECONDS);
        }

        @Override public void close() {
            if (closed) return;
            closed = true;
            win.integer("user32", "PostMessageW", new MemoryLayout[]{P, I, L, L}, handle(hwnd), 0x10, 0L, 0L);
        }
    }
}
