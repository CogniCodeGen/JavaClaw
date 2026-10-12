package com.javaclaw.desktop;

import com.javaclaw.desktop.api.*;
import com.javaclaw.desktop.service.DefaultDesktopSessionService;
import com.javaclaw.desktop.spi.DesktopPlatformProvider;
import com.javaclaw.desktop.spi.DesktopPlatformSession;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DesktopApplicationCatalogServiceTest {
    private static final DesktopSessionOwner OWNER = new DesktopSessionOwner("workspace", "run", "chat", "message");
    private static final DesktopApplicationCatalog CATALOG = new DesktopApplicationCatalog(List.of(
            new DesktopApplicationInfo("Reader", "阅读器", "reader.exe", "阅读器", List.of("Reader"))), false);

    @Test void discoveryUsesObservationAccessWithoutOpeningOrLaunchingAnApplication() {
        Provider provider = new Provider();
        var consent = new DesktopConsentPort() {
            public boolean request(DesktopSessionOwner owner, DesktopTarget target, Purpose purpose) {
                throw new AssertionError("catalog does not open a target");
            }
            public DesktopAvailability accessStatus(Purpose purpose) {
                assertEquals(Purpose.OBSERVE, purpose);
                return new DesktopAvailability(true, "test", 1, "");
            }
        };
        try (var service = new DefaultDesktopSessionService(List.of(provider), consent)) {
            assertEquals(CATALOG, service.discoverApplications(OWNER).toCompletableFuture().join());
            assertEquals(1, provider.calls.get());
        }
    }

    @Test void disabledAccessDoesNotReadInstalledMetadata() {
        Provider provider = new Provider();
        var consent = new DesktopConsentPort() {
            public boolean request(DesktopSessionOwner owner, DesktopTarget target, Purpose purpose) { return false; }
            public boolean enabled() { return false; }
        };
        try (var service = new DefaultDesktopSessionService(List.of(provider), consent)) {
            assertThrows(SecurityException.class, () -> service.discoverApplications(OWNER));
            assertEquals(0, provider.calls.get());
        }
    }

    @Test void scopeClosureRejectsAnInFlightCatalog() throws Exception {
        Provider provider = new Provider();
        provider.block.set(true);
        try (var service = new DefaultDesktopSessionService(List.of(provider), (owner, target, purpose) -> true)) {
            var result = service.discoverApplications(OWNER).toCompletableFuture();
            assertTrue(provider.entered.await(2, TimeUnit.SECONDS));
            service.closeScope(OWNER.workspaceId(), OWNER.scopeId());
            provider.release.countDown();
            var failure = assertThrows(CompletionException.class, result::join);
            assertInstanceOf(IllegalStateException.class, failure.getCause());
            assertEquals(1, provider.calls.get());
        } finally { provider.release.countDown(); }
    }

    private static final class Provider implements DesktopPlatformProvider {
        final AtomicInteger calls = new AtomicInteger();
        final AtomicBoolean block = new AtomicBoolean();
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        public String id() { return "test"; }
        public DesktopAvailability probe() { return new DesktopAvailability(true, id(), 1, ""); }
        public List<DesktopTarget> discoverTargets() { throw new AssertionError("metadata must not discover windows"); }
        public DesktopPlatformSession open(DesktopTarget target) { throw new AssertionError("metadata must not open windows"); }
        public DesktopApplicationLaunch launchApplication(String application) { throw new AssertionError("metadata must not launch"); }
        public DesktopApplicationCatalog discoverApplications() {
            calls.incrementAndGet();
            entered.countDown();
            if (block.get()) {
                try { if (!release.await(2, TimeUnit.SECONDS)) throw new IllegalStateException("fixture timeout"); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
            }
            return CATALOG;
        }
    }
}
