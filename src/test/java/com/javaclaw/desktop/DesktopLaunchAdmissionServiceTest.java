package com.javaclaw.desktop;

import com.javaclaw.desktop.api.*;
import com.javaclaw.desktop.service.DefaultDesktopSessionService;
import com.javaclaw.desktop.spi.DesktopPlatformProvider;
import com.javaclaw.desktop.spi.DesktopPlatformSession;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class DesktopLaunchAdmissionServiceTest {
    private static final DesktopSessionOwner OWNER =
            new DesktopSessionOwner("workspace", "scope", "chat", "request");

    @Test
    void unavailableAndClosedServicesProveThePlatformLaunchWasNotCalled() {
        Provider provider = new Provider(false, null);
        var service = new DefaultDesktopSessionService(List.of(provider), (owner, target, purpose) -> true);
        try {
            CompletionException failure = assertThrows(CompletionException.class,
                    () -> service.launchApplication(OWNER, "阅读器").toCompletableFuture().join());
            var unsupported = assertInstanceOf(DesktopApplicationLaunchRejectedException.class,
                    failure.getCause());
            assertEquals("UNSUPPORTED", unsupported.reasonCode());
            assertFalse(unsupported.dispatchAttempted());
            assertEquals(0, provider.launches.get());
        } finally { service.close(); }
        var closed = assertThrows(DesktopApplicationLaunchRejectedException.class,
                () -> service.launchApplication(OWNER, "阅读器"));
        assertEquals("SERVICE_UNAVAILABLE", closed.reasonCode());
        assertFalse(closed.dispatchAttempted());
        assertEquals(0, provider.launches.get());
    }

    @Test
    void invalidRequestsNeverReachThePlatformAndCarryAnExplicitReason() {
        Provider provider = new Provider(true, null);
        try (var service = new DefaultDesktopSessionService(List.of(provider),
                (owner, target, purpose) -> true)) {
            for (String name : new String[]{null, "", " ", "/Applications/Reader.app",
                    "reader.exe /quiet", "阅读器\nopen -a Terminal", "r".repeat(257)}) {
                // Spaces alone are allowed in exact display names; slash, controls and length are not.
                var rejected = assertThrows(DesktopApplicationLaunchRejectedException.class,
                        () -> service.launchApplication(OWNER, name));
                assertEquals("INVALID_ARGUMENTS", rejected.reasonCode());
                assertFalse(rejected.dispatchAttempted());
            }
            assertEquals(0, provider.launches.get());
        }
    }

    @Test
    void genericProviderFailureIsNotPromotedToTrustedPreDispatchRejection() {
        var legacy = new IllegalStateException("Exact installed application was not found; NOT_SENT");
        Provider provider = new Provider(true, legacy);
        try (var service = new DefaultDesktopSessionService(List.of(provider),
                (owner, target, purpose) -> true)) {
            CompletionException failed = assertThrows(CompletionException.class,
                    () -> service.launchApplication(OWNER, "阅读器").toCompletableFuture().join());
            assertSame(legacy, failed.getCause());
            assertFalse(failed.getCause() instanceof DesktopApplicationLaunchRejectedException);
            assertEquals(1, provider.launches.get());
        }
    }

    private static final class Provider implements DesktopPlatformProvider {
        private final boolean available;
        private final RuntimeException failure;
        private final AtomicInteger launches = new AtomicInteger();
        private Provider(boolean available, RuntimeException failure) {
            this.available = available;
            this.failure = failure;
        }
        @Override public String id() { return "test"; }
        @Override public DesktopAvailability probe() {
            return new DesktopAvailability(available, "test", DesktopAvailability.CAPTURE, "probe");
        }
        @Override public List<DesktopTarget> discoverTargets() { return List.of(); }
        @Override public DesktopApplicationLaunch launchApplication(String application) {
            launches.incrementAndGet();
            if (failure != null) throw failure;
            throw new AssertionError("invalid or unavailable launch should never be called");
        }
        @Override public DesktopPlatformSession open(DesktopTarget target) {
            throw new UnsupportedOperationException("not used");
        }
    }
}
