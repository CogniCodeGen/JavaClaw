package com.javaclaw.desktop.ffm.macos;

import com.javaclaw.desktop.api.DesktopFrame;
import com.javaclaw.desktop.api.DesktopFrameGeometry;
import com.javaclaw.desktop.nativebridge.DesktopBridge.NativeWindow;
import java.lang.foreign.Arena;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.time.Duration;
import java.util.Arrays;

/** macOS 14+ ScreenCaptureKit screenshots, without a custom native stream delegate. */
final class MacCapture {
    private final MacNative api;
    private final MacWindows windows;
    MacCapture(MacNative api, MacWindows windows) { this.api = api; this.windows = windows; }

    Pixels capture(NativeWindow target, int timeoutMillis) {
        return windows.withWindow(target, window -> screenshot(window, Math.max(100, timeoutMillis)));
    }

    private Pixels screenshot(MemorySegment window, int timeoutMillis) {
        MemorySegment filter = api.object(api.object(api.cls("SCContentFilter"), "alloc"),
                "initWithDesktopIndependentWindow:", window);
        MemorySegment config = api.object(api.cls("SCStreamConfiguration"), "new");
        try (MacAsync callback = new MacAsync(api)) {
            double[] rect = api.rect(window, "frame");
            float scale = (float) api.message(filter, "pointPixelScale", ValueLayout.JAVA_FLOAT,
                    new MemoryLayout[0]);
            if (!Float.isFinite(scale) || scale < 1 || scale > 4) scale = 2;
            int[] size = captureSize(rect[2], rect[3], scale);
            api.send(config, "setWidth:", (long) size[0]);
            api.send(config, "setHeight:", (long) size[1]);
            api.send(config, "setPixelFormat:", 0x42475241); // kCVPixelFormatType_32BGRA
            api.send(config, "setShowsCursor:", (byte) 0);
            api.send(config, "setScalesToFit:", (byte) 1);
            api.send(config, "setCapturesAudio:", (byte) 0);
            api.send(config, "setIgnoreShadowsSingleWindow:", (byte) 1);
            api.send(api.cls("SCScreenshotManager"), "captureImageWithFilter:configuration:completionHandler:",
                    filter, config, callback.pointer());
            var result = callback.await(Duration.ofMillis(timeoutMillis));
            if (MacNative.nil(result.pointer()))
                throw new IllegalStateException("ScreenCaptureKit capture failed: " + result.error());
            return decode(result.pointer(), rect[2], rect[3]);
        } finally { api.release(config); api.release(filter); }
    }

    static int[] captureSize(double width, double height, double scale) {
        if (!Double.isFinite(width) || !Double.isFinite(height) || !Double.isFinite(scale)
                || width <= 0 || height <= 0 || scale <= 0)
            throw new IllegalArgumentException("Invalid ScreenCaptureKit window dimensions");
        double reduction = Math.min(1, Math.min(4096 / (width * scale), 4096 / (height * scale)));
        return new int[]{Math.max(1, (int) Math.ceil(width * scale * reduction)),
                Math.max(1, (int) Math.ceil(height * scale * reduction))};
    }

    Pixels decode(MemorySegment image, double logicalWidth, double logicalHeight) {
        long width = (long) api.call("CGImageGetWidth", ValueLayout.JAVA_LONG,
                new MemoryLayout[]{ValueLayout.ADDRESS}, image);
        long height = (long) api.call("CGImageGetHeight", ValueLayout.JAVA_LONG,
                new MemoryLayout[]{ValueLayout.ADDRESS}, image);
        if (width < 1 || height < 1 || width > 4096 || height > 4096)
            throw new IllegalStateException("ScreenCaptureKit returned an invalid or oversized image");
        int stride = Math.toIntExact(width * 4);
        int bytes = Math.toIntExact(stride * height);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment output = arena.allocate(bytes, 16);
            MemorySegment colorSpace = (MemorySegment) api.call("CGColorSpaceCreateDeviceRGB",
                    ValueLayout.ADDRESS, new MemoryLayout[0]);
            MemorySegment context = MemorySegment.NULL;
            try {
                context = (MemorySegment) api.call("CGBitmapContextCreate", ValueLayout.ADDRESS,
                        new MemoryLayout[]{ValueLayout.ADDRESS, ValueLayout.JAVA_LONG,
                                ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                                ValueLayout.ADDRESS, ValueLayout.JAVA_INT}, output, width, height, 8L,
                        (long) stride, colorSpace, 0x2002); // little endian, premultiplied first => BGRA
                if (MacNative.nil(context)) throw new IllegalStateException("Cannot decode captured pixels");
                MemorySegment bounds = arena.allocate(MacNative.RECT);
                bounds.set(ValueLayout.JAVA_DOUBLE, 16, (double) width);
                bounds.set(ValueLayout.JAVA_DOUBLE, 24, (double) height);
                api.call("CGContextDrawImage", null,
                        new MemoryLayout[]{ValueLayout.ADDRESS, MacNative.RECT, ValueLayout.ADDRESS},
                        context, bounds, image);
                return new Pixels((int) width, (int) height, stride,
                        output.toArray(ValueLayout.JAVA_BYTE), logicalWidth, logicalHeight);
            } finally { api.release(context); api.release(colorSpace); }
        }
    }

    record Pixels(int width, int height, int stride, byte[] bytes, double logicalWidth, double logicalHeight) {
        DesktopFrame frame(String targetId, long generation, long revision) {
            return new DesktopFrame(targetId, generation, System.currentTimeMillis(), width, height,
                    stride, bytes, revision, new DesktopFrameGeometry(logicalWidth, logicalHeight,
                            0, 0, width, height, true));
        }
        boolean sameContent(DesktopFrame old) {
            return old != null && old.width() == width && old.height() == height
                    && old.stride() == stride && Arrays.equals(bytes, old.bgraPremultiplied());
        }
    }
}
