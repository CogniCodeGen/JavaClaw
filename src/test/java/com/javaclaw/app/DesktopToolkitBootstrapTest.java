package com.javaclaw.app;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesktopToolkitBootstrapTest {

    @Test
    void macInitializesAwtAndEnablesTemplateImagesBeforeJavaFx() {
        String previous = System.getProperty(DesktopToolkitBootstrap.TEMPLATE_IMAGES_PROPERTY);
        AtomicInteger initialized = new AtomicInteger();
        try {
            System.clearProperty(DesktopToolkitBootstrap.TEMPLATE_IMAGES_PROPERTY);

            assertTrue(DesktopToolkitBootstrap.prepareForJavaFxLaunch(
                    "Mac OS X", false, false, initialized::incrementAndGet));

            assertEquals(1, initialized.get());
            assertEquals("true", System.getProperty(
                    DesktopToolkitBootstrap.TEMPLATE_IMAGES_PROPERTY));
        } finally {
            restoreTemplateProperty(previous);
        }
    }

    @Test
    void nonMacUiTestAndHeadlessLaunchesDoNotInitializeAwt() {
        AtomicInteger initialized = new AtomicInteger();

        assertFalse(DesktopToolkitBootstrap.prepareForJavaFxLaunch(
                "Linux", false, false, initialized::incrementAndGet));
        assertFalse(DesktopToolkitBootstrap.prepareForJavaFxLaunch(
                "Mac OS X", true, false, initialized::incrementAndGet));
        assertFalse(DesktopToolkitBootstrap.prepareForJavaFxLaunch(
                "Mac OS X", false, true, initialized::incrementAndGet));

        assertEquals(0, initialized.get());
    }

    @Test
    void failedAwtInitializationFallsBackWithoutStartingJavaFxTrayMode() {
        String previous = System.getProperty(DesktopToolkitBootstrap.TEMPLATE_IMAGES_PROPERTY);
        try {
            assertFalse(DesktopToolkitBootstrap.prepareForJavaFxLaunch(
                    "Mac OS X", false, false,
                    () -> { throw new IllegalStateException("simulated AppKit failure"); }));
        } finally {
            restoreTemplateProperty(previous);
        }
    }

    private static void restoreTemplateProperty(String previous) {
        if (previous == null) {
            System.clearProperty(DesktopToolkitBootstrap.TEMPLATE_IMAGES_PROPERTY);
        } else {
            System.setProperty(DesktopToolkitBootstrap.TEMPLATE_IMAGES_PROPERTY, previous);
        }
    }
}
