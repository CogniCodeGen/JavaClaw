package com.javaclaw.desktop;

import com.javaclaw.desktop.api.*;
import com.javaclaw.desktop.service.DefaultDesktopSessionService;
import com.javaclaw.desktop.spi.DesktopPlatformProvider;
import com.javaclaw.desktop.spi.DesktopPlatformSession;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class DesktopApplicationLaunchRecoveryTest {
    private static final String APP = "com.example.reader";
    private static final DesktopSessionOwner OWNER =
            new DesktopSessionOwner("workspace", "scope", "chat", "request");

    @Test
    void installedIdentityFindsAnExistingWindowWithoutLaunchingFromItsTitle() {
        Provider provider = new Provider();
        var existing = window("existing", 202, APP, "Unrelated document title", DesktopTarget.VISIBLE);
        provider.windows = List.of(existing);
        try (var service = service(provider)) {
            var result = service.launchApplication(OWNER, "Ｒｅａｄｅｒ").toCompletableFuture().join();
            assertEquals(0, provider.launches.get());
            assertFalse(result.dispatchAttempted());
            assertEquals(APP, result.applicationId());
            assertEquals(202, result.processId());
            assertEquals(List.of(existing), result.targets());
        }
    }

    @Test
    void minimizedOrHiddenRegisteredWindowsProveRunningStateWithoutPretendingTheyAreOperable() {
        for (int flags : new int[]{DesktopTarget.VISIBLE | DesktopTarget.MINIMIZED, 0}) {
            Provider provider = new Provider();
            provider.windows = List.of(window("hidden", 202, APP, "Home", flags));
            try (var service = service(provider)) {
                var result = service.launchApplication(OWNER, "Reader").toCompletableFuture().join();
                assertFalse(result.dispatchAttempted());
                assertEquals(202, result.processId());
                assertTrue(result.targets().isEmpty(), "hidden windows cannot be presented as actionable targets");
                assertEquals(0, provider.launches.get());
                assertTrue(result.detail().contains("最小化"));
            }
        }
    }

    @Test
    void matchingTitlesAndSystemSurfacesCannotImpersonateTheInstalledApplication() {
        Provider provider = new Provider();
        provider.windows = List.of(
                window("other", 100, "com.example.other", "Reader", DesktopTarget.VISIBLE),
                window("proxy", 101, APP, "Reader", DesktopTarget.VISIBLE | DesktopTarget.SYSTEM_SURFACE));
        try (var service = service(provider)) {
            var result = service.launchApplication(OWNER, "Reader").toCompletableFuture().join();
            assertEquals(1, provider.launches.get());
            assertTrue(result.dispatchAttempted());
            assertEquals(List.of(provider.windows.getFirst()), result.targets());
            assertEquals("launched-1", result.targets().getFirst().id());
        }
    }

    @Test
    void acceptedProcessWithoutAWindowCannotBeRelaunchedByAnotherAliasOrScope() {
        Provider provider = new Provider();
        try (var service = service(provider)) {
            var first = service.launchApplication(OWNER, "Reader").toCompletableFuture().join();
            assertTrue(first.dispatchAttempted());
            provider.windows = List.of();
            service.closeScope(OWNER.workspaceId(), OWNER.scopeId());
            var otherOwner = new DesktopSessionOwner("workspace", "new-scope", "chat", "new-request");
            for (Optional<Boolean> alive : List.of(Optional.of(true), Optional.<Boolean>empty())) {
                provider.alive = alive;
                var reused = service.launchApplication(otherOwner, APP).toCompletableFuture().join();
                assertFalse(reused.dispatchAttempted());
                assertEquals(first.processId(), reused.processId());
                assertTrue(reused.targets().isEmpty());
                assertEquals(1, provider.launches.get(),
                        "absence of a window or liveness evidence must not replay accepted native launch");
            }
        }
    }

    @Test
    void aConfirmedExitedProcessAllowsOneNewLaunchRequest() {
        Provider provider = new Provider();
        try (var service = service(provider)) {
            var first = service.launchApplication(OWNER, "Reader").toCompletableFuture().join();
            provider.windows = List.of();
            provider.alive = Optional.of(false);
            var restarted = service.launchApplication(OWNER, APP).toCompletableFuture().join();
            assertEquals(2, provider.launches.get());
            assertTrue(restarted.dispatchAttempted());
            assertNotEquals(first.processId(), restarted.processId());
            assertEquals("launched-2", restarted.targets().getFirst().id());
        }
    }

    @Test
    void unknownLaunchIsFencedUntilTheRealApplicationWindowIsDiscovered() {
        Provider provider = new Provider();
        provider.launchFailure = new DesktopApplicationLaunchUncertainException(
                "lost native response", 0, APP, null);
        try (var service = service(provider)) {
            assertInstanceOf(DesktopApplicationLaunchUncertainException.class,
                    assertThrows(CompletionException.class, () -> service.launchApplication(OWNER,
                            "Reader").toCompletableFuture().join()).getCause());
            provider.alive = Optional.of(false);
            assertInstanceOf(DesktopApplicationLaunchUncertainException.class,
                    assertThrows(CompletionException.class, () -> service.launchApplication(OWNER,
                            APP).toCompletableFuture().join()).getCause());
            assertEquals(1, provider.launches.get());
            var observedWindow = window("discovered", 303, APP, "Home", DesktopTarget.VISIBLE);
            provider.windows = List.of(observedWindow);
            var recovered = service.launchApplication(OWNER, APP).toCompletableFuture().join();
            assertFalse(recovered.dispatchAttempted());
            assertEquals(List.of(observedWindow), recovered.targets());
            assertEquals(1, provider.launches.get(), "recovery is discovery, not another native launch");
        }
    }

    @Test
    void concurrentAliasRequestsShareTheSameNativeAdmission() throws Exception {
        Provider provider = new Provider();
        provider.entered = new CountDownLatch(1);
        provider.release = new CountDownLatch(1);
        try (var service = service(provider)) {
            var first = service.launchApplication(OWNER, "Reader").toCompletableFuture();
            assertTrue(provider.entered.await(2, TimeUnit.SECONDS));
            var second = service.launchApplication(OWNER, APP).toCompletableFuture();
            provider.release.countDown();
            assertTrue(first.get(3, TimeUnit.SECONDS).dispatchAttempted());
            assertFalse(second.get(3, TimeUnit.SECONDS).dispatchAttempted());
            assertEquals(1, provider.launches.get());
        } finally { provider.release.countDown(); }
    }

    @Test
    void ambiguousRegisteredAliasesAreRejectedBeforeAnyNativeLaunch() {
        Provider provider = new Provider();
        provider.catalog = new DesktopApplicationCatalog(List.of(
                new DesktopApplicationInfo("Reader", "Reader", APP, APP, List.of()),
                new DesktopApplicationInfo("Reader", "Reader", "com.example.second", "com.example.second", List.of())), false);
        try (var service = service(provider)) {
            var rejected = assertInstanceOf(DesktopApplicationLaunchRejectedException.class,
                    assertThrows(CompletionException.class, () -> service.launchApplication(OWNER,
                            "Reader").toCompletableFuture().join()).getCause());
            assertEquals("AMBIGUOUS_APPLICATION", rejected.reasonCode());
            assertFalse(rejected.dispatchAttempted());
            assertEquals(0, provider.launches.get());
        }
    }

    @Test
    void aLostOrNullPlatformAdmissionCannotBecomePermissionToLaunchAgain() {
        for (boolean nullResult : List.of(false, true)) {
            Provider provider = new Provider();
            provider.nullResult = nullResult;
            if (!nullResult) provider.launchFailure = new IllegalStateException("lost provider result");
            try (var service = service(provider)) {
                assertThrows(CompletionException.class,
                        () -> service.launchApplication(OWNER, "Reader").toCompletableFuture().join());
                assertInstanceOf(DesktopApplicationLaunchUncertainException.class,
                        assertThrows(CompletionException.class, () -> service.launchApplication(OWNER,
                                APP).toCompletableFuture().join()).getCause());
                assertEquals(1, provider.launches.get());
            }
        }
    }

    @Test
    void unavailableOrTruncatedCatalogsCannotTreatUnknownAliasesAsSeparateLaunches() {
        for (boolean unsupported : List.of(false, true)) {
            Provider provider = new Provider();
            provider.unsupportedCatalog = unsupported;
            provider.catalog = new DesktopApplicationCatalog(provider.catalog.applications(), true);
            provider.launchFailure = new DesktopApplicationLaunchUncertainException("lost response", 0, "", null);
            try (var service = service(provider)) {
                assertThrows(CompletionException.class, () -> service.launchApplication(OWNER,
                        "Reader").toCompletableFuture().join());
                assertInstanceOf(DesktopApplicationLaunchUncertainException.class,
                        assertThrows(CompletionException.class, () -> service.launchApplication(OWNER,
                                "Book Reader").toCompletableFuture().join()).getCause());
                assertEquals(1, provider.launches.get());
                // Even if a later catalog establishes the canonical identity,
                // the previous unknown admission must still fence that same app.
                provider.unsupportedCatalog = false;
                provider.catalog = new DesktopApplicationCatalog(provider.catalog.applications(), false);
                assertInstanceOf(DesktopApplicationLaunchUncertainException.class,
                        assertThrows(CompletionException.class, () -> service.launchApplication(OWNER,
                                APP).toCompletableFuture().join()).getCause());
                assertEquals(1, provider.launches.get());
            }
        }
    }

    @Test
    void concurrentUnboundAliasesShareAProviderFenceEvenWhenTheFirstResponseIsLost() throws Exception {
        for (boolean unsupported : List.of(false, true)) {
            Provider provider = new Provider();
            provider.unsupportedCatalog = unsupported;
            provider.catalog = new DesktopApplicationCatalog(provider.catalog.applications(), true);
            provider.entered = new CountDownLatch(1);
            provider.release = new CountDownLatch(1);
            provider.launchFailure = new DesktopApplicationLaunchUncertainException("lost response", 0, "", null);
            try (var service = service(provider)) {
                var first = service.launchApplication(OWNER, "Reader").toCompletableFuture();
                assertTrue(provider.entered.await(2, TimeUnit.SECONDS));
                var second = service.launchApplication(OWNER, "Book Reader").toCompletableFuture();
                provider.release.countDown();
                assertThrows(java.util.concurrent.ExecutionException.class, () -> first.get(3, TimeUnit.SECONDS));
                var blocked = assertThrows(java.util.concurrent.ExecutionException.class,
                        () -> second.get(3, TimeUnit.SECONDS));
                assertInstanceOf(DesktopApplicationLaunchUncertainException.class, blocked.getCause());
                assertEquals(1, provider.launches.get());
            } finally { provider.release.countDown(); }
        }
    }

    @Test
    void anUnboundUnknownRequestDoesNotFenceAnUnrelatedRegisteredApplication() {
        Provider provider = new Provider();
        provider.unsupportedCatalog = true;
        provider.launchFailure = new DesktopApplicationLaunchUncertainException("lost response", 0, APP, null);
        try (var service = service(provider)) {
            assertThrows(CompletionException.class, () -> service.launchApplication(OWNER,
                    "Reader").toCompletableFuture().join());
            String otherId = "com.example.calendar";
            provider.unsupportedCatalog = false;
            provider.catalog = new DesktopApplicationCatalog(List.of(provider.catalog.applications().getFirst(),
                    new DesktopApplicationInfo("Calendar", "日历", otherId, otherId, List.of())), false);
            provider.launchFailure = null;
            provider.returnedAppId = otherId;
            var other = service.launchApplication(OWNER, otherId).toCompletableFuture().join();
            assertTrue(other.dispatchAttempted());
            assertEquals(otherId, other.applicationId());
            assertEquals(2, provider.launches.get());
        }
    }

    @Test
    void discoveringOneExistingAppCannotClearAnEarlierUnknownUnboundLaunch() {
        Provider provider = new Provider();
        provider.unsupportedCatalog = true;
        provider.launchFailure = new DesktopApplicationLaunchUncertainException("lost response", 0, "", null);
        try (var service = service(provider)) {
            assertThrows(CompletionException.class, () -> service.launchApplication(OWNER,
                    "Reader").toCompletableFuture().join());
            String otherId = "com.example.calendar";
            provider.unsupportedCatalog = false;
            provider.catalog = new DesktopApplicationCatalog(List.of(provider.catalog.applications().getFirst(),
                    new DesktopApplicationInfo("Calendar", "日历", otherId, otherId, List.of())), false);
            provider.windows = List.of(window("existing-reader", 303, APP, "Home", DesktopTarget.VISIBLE));
            var existing = service.launchApplication(OWNER, APP).toCompletableFuture().join();
            assertFalse(existing.dispatchAttempted());
            assertEquals(303, existing.processId());
            assertEquals(1, provider.launches.get());

            provider.windows = List.of();
            provider.launchFailure = null;
            provider.returnedAppId = otherId;
            var otherScope = new DesktopSessionOwner("workspace", "another-scope", "chat", "request");
            assertInstanceOf(DesktopApplicationLaunchUncertainException.class,
                    assertThrows(CompletionException.class, () -> service.launchApplication(otherScope,
                            otherId).toCompletableFuture().join()).getCause());
            assertEquals(1, provider.launches.get(),
                    "the unknown earlier launch may have targeted another app; existing Reader does not clear it");
        }
    }

    @Test
    void aRenamedMissingOrTruncatedOldAliasCannotReleaseAnUnknownProviderAdmission() {
        for (boolean truncated : List.of(false, true)) {
            Provider provider = new Provider();
            provider.unsupportedCatalog = true;
            provider.launchFailure = new DesktopApplicationLaunchUncertainException("lost response", 0, "", null);
            try (var service = service(provider)) {
                assertThrows(CompletionException.class, () -> service.launchApplication(OWNER,
                        "Reader").toCompletableFuture().join());
                provider.unsupportedCatalog = false;
                // The old requested alias has disappeared. The new explicit ID
                // still resolves even in a truncated directory, but cannot prove
                // that the unknown earlier request targeted a different app.
                provider.catalog = new DesktopApplicationCatalog(List.of(new DesktopApplicationInfo(
                        "New Reader", "新版阅读器", APP, APP, List.of())), truncated);
                var otherScope = new DesktopSessionOwner("workspace", "new-scope", "chat", "new-request");
                assertInstanceOf(DesktopApplicationLaunchUncertainException.class,
                        assertThrows(CompletionException.class, () -> service.launchApplication(otherScope,
                                APP).toCompletableFuture().join()).getCause());
                assertEquals(1, provider.launches.get(),
                        "renaming or omitting an old alias cannot permit another native launch");
            }
        }
    }

    private static DefaultDesktopSessionService service(Provider provider) {
        return new DefaultDesktopSessionService(List.of(provider), (owner, target, purpose) -> true);
    }

    private static DesktopTarget window(String id, long pid, String appId, String title, int flags) {
        return new DesktopTarget("test", id, pid, "Window owner", title, 0, 0, 800, 600, flags, appId);
    }

    private static final class Provider implements DesktopPlatformProvider {
        final AtomicInteger launches = new AtomicInteger();
        volatile List<DesktopTarget> windows = List.of();
        volatile Optional<Boolean> alive = Optional.of(true);
        volatile RuntimeException launchFailure;
        volatile boolean nullResult;
        volatile boolean unsupportedCatalog;
        volatile String returnedAppId = APP;
        volatile CountDownLatch entered;
        volatile CountDownLatch release;
        volatile DesktopApplicationCatalog catalog = new DesktopApplicationCatalog(List.of(
                new DesktopApplicationInfo("Reader", "阅读器", APP, APP, List.of("Book Reader"))), false);

        @Override public String id() { return "test"; }
        @Override public DesktopAvailability probe() {
            return new DesktopAvailability(true, id(), DesktopAvailability.CAPTURE, "available");
        }
        @Override public List<DesktopTarget> discoverTargets() { return windows; }
        @Override public DesktopApplicationCatalog discoverApplications() {
            if (unsupportedCatalog) throw new UnsupportedOperationException("legacy provider");
            return catalog;
        }
        @Override public Optional<Boolean> isLaunchedApplicationRunning(DesktopApplicationLaunch launch) {
            return alive;
        }
        @Override public DesktopApplicationLaunch launchApplication(String name) {
            if (!unsupportedCatalog && !catalog.truncated()) assertEquals(returnedAppId, name,
                    "the native request uses the catalog's exact launch identity, not an alias");
            int count = launches.incrementAndGet();
            if (entered != null) entered.countDown();
            if (release != null) try {
                if (!release.await(3, TimeUnit.SECONDS)) throw new IllegalStateException("test launch timed out");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
            if (launchFailure != null) throw launchFailure;
            if (nullResult) return null;
            long pid = 201 + count;
            windows = List.of(window("launched-" + count, pid, returnedAppId, "Home", DesktopTarget.VISIBLE));
            return new DesktopApplicationLaunch(pid, returnedAppId, "accepted");
        }
        @Override public DesktopPlatformSession open(DesktopTarget target) {
            throw new UnsupportedOperationException("not used");
        }
    }
}
