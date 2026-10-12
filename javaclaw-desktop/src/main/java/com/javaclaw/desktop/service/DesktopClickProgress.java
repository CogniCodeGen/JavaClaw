package com.javaclaw.desktop.service;

import com.javaclaw.desktop.api.*;

import static com.javaclaw.desktop.service.DefaultDesktopSessionService.UNKNOWN_SETTLE_MILLIS;

/** 比较像素与语义身份，拒绝重复派发已接受但无进展的后台点击。 */
final class DesktopClickProgress {
    private DesktopClickProgress() {}

    static ClickControl clickControl(DesktopAction action, DesktopObservation observation) {
        if (!action.elementId().startsWith(observation.observationId() + ":e")) return null;
        DesktopElement selected = observation.elements().stream()
                .filter(element -> element.id().equals(action.elementId())).findFirst().orElse(null);
        if (selected == null || selected.role().isBlank() || selected.label().isBlank()
                || (selected.actions() & DesktopElement.PRESS) == 0) return null;
        ClickControl control = ClickControl.of(selected);
        // 观察令牌每帧变化；只有捕获目录中唯一的精确语义身份才能匹配先前控件。
        return observation.elements().stream().filter(element -> control.equals(ClickControl.of(element)))
                .limit(2).count() == 1 ? control : null;
    }

    static boolean sameClickSurface(DesktopSurfaceSnapshot before, DesktopSurfaceSnapshot after) {
        return before.providerId().equals(after.providerId())
                && before.runtimeId().equals(after.runtimeId())
                && before.surfaceId().equals(after.surfaceId())
                && before.logicalTargetId().equals(after.logicalTargetId())
                && before.applicationId().equals(after.applicationId())
                && before.generation() == after.generation()
                && before.contentRevision() == after.contentRevision();
    }

    static boolean unchangedAcceptedClick(AcceptedBackgroundClick previous, DesktopAction action,
            DesktopObservation observation, DesktopSurfaceSnapshot currentSurface, DesktopFrame frame, long actionEpoch) {
        if (previous == null
                || action.kind() != DesktopAction.Kind.CLICK
                || action.button() != 1 || action.clicks() != 1
                || previous.actionEpoch() != actionEpoch
                || observation.frame().capturedAtMillis()
                    <= previous.completedAtMillis() + UNKNOWN_SETTLE_MILLIS
                || !sameClickSurface(previous.surface(), observation.capturedSurface())
                || !sameClickSurface(previous.surface(), currentSurface)
                || previous.width() != frame.width() || previous.height() != frame.height()
                || previous.stride() != frame.stride()
                || previous.width() != observation.frame().width()
                || previous.height() != observation.frame().height()
                || previous.stride() != observation.frame().stride()) return false;
        ClickControl control = clickControl(action, observation);
        if (control == null || !previous.control().equals(control)) return false;
        byte[] observedPixels = pixelDigest(observation.frame());
        byte[] currentPixels = pixelDigest(frame);
        return observedPixels != null && currentPixels != null
                && java.util.Arrays.equals(previous.pixels(), observedPixels)
                && java.util.Arrays.equals(previous.pixels(), currentPixels);
    }

    /** 比较有界目标及其周围命中区域的精确像素。 */
    static boolean samePixels(DesktopFrame observed, DesktopFrame current,
                                      int x, int y, int width, int height) {
        if (width < 1 || height < 1 || x < 0 || y < 0
                || (long) x + width > observed.width()
                || (long) y + height > observed.height()
                || observed.width() != current.width() || observed.height() != current.height())
            return false;
        int left = Math.max(0, x - 8);
        int top = Math.max(0, y - 8);
        int right = Math.min(observed.width(), x + width + 8);
        int bottom = Math.min(observed.height(), y + height + 8);
        byte[] before = observed.bgraPremultiplied();
        byte[] after = current.bgraPremultiplied();
        int rowBytes = (right - left) * 4;
        for (int row = top; row < bottom; row++) {
            int beforeStart = row * observed.stride() + left * 4;
            int afterStart = row * current.stride() + left * 4;
            if (java.util.Arrays.mismatch(before, beforeStart, beforeStart + rowBytes,
                    after, afterStart, afterStart + rowBytes) >= 0)
                return false;
        }
        return true;
    }

    static byte[] pixelDigest(DesktopFrame frame) {
        try {
            return java.security.MessageDigest.getInstance("SHA-256").digest(frame.bgraPremultiplied());
        } catch (java.security.NoSuchAlgorithmException | RuntimeException unavailable) {
            // 可选进度检查无法完成时不得伪造像素相同证明。
            return null;
        }
    }

    record ClickControl(String role, String label, int x, int y,
                                int width, int height, int actions) {
        static ClickControl of(DesktopElement element) {
            return new ClickControl(element.role(), element.label(), element.x(), element.y(),
                    element.width(), element.height(), element.actions());
        }
    }

    record AcceptedBackgroundClick(DesktopSurfaceSnapshot surface, int width, int height,
            int stride, byte[] pixels, ClickControl control, long actionEpoch, long completedAtMillis) {
        AcceptedBackgroundClick dispatched(long epoch, long completedAt) {
            return new AcceptedBackgroundClick(surface, width, height, stride, pixels, control,
                    epoch, completedAt);
        }
    }

}
