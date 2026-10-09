package com.javaclaw.desktop.spi;

import com.javaclaw.desktop.api.DesktopFrame;
import com.javaclaw.desktop.api.DesktopFrameGeometry;
import com.javaclaw.desktop.api.DesktopAction;
import com.javaclaw.desktop.api.DesktopActionResult;
import com.javaclaw.desktop.api.DesktopSurfaceSnapshot;
import com.javaclaw.desktop.api.DesktopTarget;
import java.util.Objects;

/** Immutable capture evidence checked in Java immediately before platform click dispatch. */
public record DesktopClickGuard(String targetId, long generation, long capturedAtMillis, long contentRevision,
        int frameWidth, int frameHeight, int frameStride, DesktopFrameGeometry geometry,
        int x, int y, int width, int height, byte[] bgra, DesktopSurfaceSnapshot capturedSurface) {
    public static final int MAX_BYTES = 8 * 1024 * 1024;
    private static final long FRAME_FRESHNESS_MILLIS = 2_500;

    public DesktopClickGuard {
        long rowBytes = (long) width * 4;
        if (targetId == null || targetId.isBlank() || generation < 1 || capturedAtMillis < 0 || contentRevision < 1
                || frameWidth < 1 || frameHeight < 1 || frameStride < (long) frameWidth * 4
                || x < 0 || y < 0 || width < 1 || height < 1
                || (long) x + width > frameWidth || (long) y + height > frameHeight
                || rowBytes > MAX_BYTES || height > MAX_BYTES / rowBytes
                || bgra == null || bgra.length != rowBytes * height
                || (geometry != null && !geometry.fits(frameWidth, frameHeight)))
            throw new IllegalArgumentException("点击校验区域无效或超过 8 MiB，请选择更小目标");
        if (capturedSurface != null && (!targetId.equals(capturedSurface.logicalTargetId())
                || capturedSurface.runtimeId().isBlank() || capturedSurface.surfaceId().isBlank()
                || capturedSurface.providerId().isBlank() || capturedSurface.applicationId().isBlank()
                || generation != capturedSurface.generation()
                || contentRevision != capturedSurface.contentRevision()
                || capturedAtMillis != capturedSurface.observedAtMillis()))
            throw new IllegalArgumentException("点击校验窗口身份与采集画面不匹配，请重新观察");
        bgra = bgra.clone();
    }

    public DesktopClickGuard(String targetId, long generation, long capturedAtMillis, long contentRevision,
            int frameWidth, int frameHeight, int frameStride, DesktopFrameGeometry geometry,
            int x, int y, int width, int height, byte[] bgra) {
        this(targetId, generation, capturedAtMillis, contentRevision, frameWidth, frameHeight,
                frameStride, geometry, x, y, width, height, bgra, null);
    }

    @Override public byte[] bgra() { return bgra.clone(); }

    /** Includes the same eight-pixel margin used by Java's observation comparison. */
    public static DesktopClickGuard capture(DesktopFrame frame, int x, int y, int width, int height) {
        return capture(frame, null, x, y, width, height);
    }

    public static DesktopClickGuard capture(DesktopFrame frame, DesktopSurfaceSnapshot capturedSurface,
                                           int x, int y, int width, int height) {
        if (x < 0 || y < 0 || width < 1 || height < 1
                || (long) x + width > frame.width() || (long) y + height > frame.height())
            throw new IllegalArgumentException("点击校验区域超出画面");
        int left = Math.max(0, x - 8), top = Math.max(0, y - 8);
        int right = (int) Math.min(frame.width(), (long) x + width + 8);
        int bottom = (int) Math.min(frame.height(), (long) y + height + 8);
        long size = (long) (right - left) * (bottom - top) * 4;
        if (size > MAX_BYTES)
            throw new IllegalArgumentException("点击校验区域超过 8 MiB，请选择更小目标");
        byte[] roi = frame.copyBgraRegion(left, top, right - left, bottom - top);
        return new DesktopClickGuard(frame.targetId(), frame.windowGeneration(), frame.capturedAtMillis(),
                frame.contentRevision(), frame.width(), frame.height(), frame.stride(),
                frame.geometry(), left, top, right - left, bottom - top, roi, capturedSurface);
    }

    /** Revisions may advance for animation outside the selected region, but never roll back. */
    public boolean matches(DesktopAction action, DesktopFrame current, long nowMillis) {
        if (action == null || action.kind() != DesktopAction.Kind.CLICK || current == null
                || action.windowGeneration() != generation || action.contentRevision() != contentRevision
                || action.button() < 1 || action.button() > 3 || action.clicks() < 1 || action.clicks() > 2
                || action.x() < x || action.y() < y
                || (long) action.x() >= (long) x + width || (long) action.y() >= (long) y + height
                || !targetId.equals(current.targetId()) || current.windowGeneration() != generation
                || current.contentRevision() < contentRevision || current.capturedAtMillis() < capturedAtMillis
                || capturedAtMillis > nowMillis || current.capturedAtMillis() > nowMillis
                || nowMillis - capturedAtMillis > FRAME_FRESHNESS_MILLIS
                || nowMillis - current.capturedAtMillis() > FRAME_FRESHNESS_MILLIS
                || current.width() != frameWidth || current.height() != frameHeight
                || current.stride() != frameStride || !Objects.equals(current.geometry(), geometry)) return false;
        if (geometry != null && (action.x() < geometry.contentX() || action.y() < geometry.contentY()
                || (long) action.x() >= (long) geometry.contentX() + geometry.contentWidth()
                || (long) action.y() >= (long) geometry.contentY() + geometry.contentHeight())) return false;
        return current.matchesBgraRegion(x, y, width, height, bgra);
    }

    /** Capture and admission share the provider monitor; only perform may have delivered input. */
    static DesktopActionResult perform(DesktopPlatformSession platform, DesktopAction action,
                                      boolean foreground, DesktopClickGuard guard) {
        synchronized (platform) {
            DesktopAction refreshed;
            try {
                if (guard == null || guard.geometry == null || guard.capturedSurface == null)
                    return stale(action, "缺少点击目标区域、几何或窗口身份校验，请重新观察");
                DesktopTarget before = platform.currentTarget();
                DesktopSurfaceSnapshot previousSurface = platform.currentSurface().orElse(null);
                DesktopFrame current = platform.pollFrame(200).orElse(null);
                DesktopTarget after = platform.currentTarget();
                DesktopSurfaceSnapshot currentSurface = platform.currentSurface().orElse(null);
                if (!sameTarget(before, after) || !guard.targetId.equals(after.id())
                        || !after.visible() || after.minimized()
                        || !sameSurface(guard.capturedSurface, previousSurface)
                        || !sameSurface(previousSurface, currentSurface)
                        || !captureIdentity(currentSurface, after, current)
                        || !guard.matches(action, current, System.currentTimeMillis()))
                    return stale(action, "点击前目标窗口或区域已变化，未发送输入；请重新观察");
                refreshed = new DesktopAction(action.kind(), action.x(), action.y(), action.button(),
                        action.clicks(), action.amount(), action.text(), action.windowGeneration(),
                        action.observationId(), action.elementId(), current.contentRevision(), action.textOperation());
            } catch (SecurityException denied) {
                return new DesktopActionResult(DesktopActionResult.Status.DENIED,
                        "点击前采集权限不可用，未发送输入", action.windowGeneration());
            } catch (RuntimeException unavailable) {
                return stale(action, "点击前无法刷新目标画面，未发送输入；请重新观察");
            }
            // Preserve the provider's partial/unknown delivery result, including double clicks.
            // Exceptions after entering perform are handled by the service as UNKNOWN.
            return platform.perform(refreshed, foreground);
        }
    }

    private static boolean sameTarget(DesktopTarget before, DesktopTarget after) {
        return before.providerId().equals(after.providerId()) && before.id().equals(after.id())
                && before.processId() == after.processId() && before.applicationId().equals(after.applicationId());
    }

    private static boolean sameSurface(DesktopSurfaceSnapshot before, DesktopSurfaceSnapshot after) {
        return before != null && after != null && !before.runtimeId().isBlank() && !before.surfaceId().isBlank()
                && before.providerId().equals(after.providerId()) && before.runtimeId().equals(after.runtimeId())
                && before.surfaceId().equals(after.surfaceId()) && before.logicalTargetId().equals(after.logicalTargetId())
                && before.applicationId().equals(after.applicationId()) && before.generation() == after.generation();
    }

    private static boolean captureIdentity(DesktopSurfaceSnapshot surface, DesktopTarget target, DesktopFrame frame) {
        return surface != null && frame != null && surface.providerId().equals(target.providerId())
                && surface.logicalTargetId().equals(target.id()) && target.id().equals(frame.targetId())
                && !surface.applicationId().isBlank() && surface.applicationId().equals(target.applicationId())
                && surface.generation() == frame.windowGeneration()
                && surface.contentRevision() == frame.contentRevision()
                && surface.observedAtMillis() == frame.capturedAtMillis();
    }

    private static DesktopActionResult stale(DesktopAction action, String detail) {
        return new DesktopActionResult(DesktopActionResult.Status.STALE_FRAME, detail, action.windowGeneration())
                .withContext(DesktopActionResult.Mode.NONE, action.observationId(), DesktopActionResult.NextStep.OBSERVE);
    }
}
