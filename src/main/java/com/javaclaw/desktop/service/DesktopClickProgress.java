package com.javaclaw.desktop.service;

import com.javaclaw.desktop.api.*;

/** Pixel and semantic identity checks for refusing a repeated accepted background click. */
final class DesktopClickProgress {
    private DesktopClickProgress() {}

    static ClickControl clickControl(DesktopAction action, DesktopObservation observation) {
        if (!action.elementId().startsWith(observation.observationId() + ":e")) return null;
        DesktopElement selected = observation.elements().stream()
                .filter(element -> element.id().equals(action.elementId())).findFirst().orElse(null);
        if (selected == null || selected.role().isBlank() || selected.label().isBlank()
                || (selected.actions() & DesktopElement.PRESS) == 0) return null;
        ClickControl control = ClickControl.of(selected);
        // Observation tokens change on every capture. Only a unique exact semantic
        // identity in the captured catalog can match a previous control.
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

    static byte[] pixelDigest(DesktopFrame frame) {
        try {
            return java.security.MessageDigest.getInstance("SHA-256").digest(frame.bgraPremultiplied());
        } catch (java.security.NoSuchAlgorithmException | RuntimeException unavailable) {
            // Optional progress detection must never invent an equality proof.
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
