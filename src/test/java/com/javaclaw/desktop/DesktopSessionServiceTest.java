package com.javaclaw.desktop;

import com.javaclaw.desktop.api.DesktopAction;
import com.javaclaw.desktop.api.DesktopInputPolicy;
import com.javaclaw.desktop.api.DesktopElement;
import com.javaclaw.desktop.api.DesktopActionEvent;
import com.javaclaw.desktop.api.DesktopActionResult;
import com.javaclaw.desktop.api.DesktopAvailability;
import com.javaclaw.desktop.api.DesktopApplicationLaunch;
import com.javaclaw.desktop.api.DesktopApplicationLaunchResult;
import com.javaclaw.desktop.api.DesktopApplicationLaunchRejectedException;
import com.javaclaw.desktop.api.DesktopApplicationLaunchUncertainException;
import com.javaclaw.desktop.api.DesktopConsentPort;
import com.javaclaw.desktop.api.DesktopFrame;
import com.javaclaw.desktop.api.DesktopObservation;
import com.javaclaw.desktop.api.DesktopSessionInfo;
import com.javaclaw.desktop.api.DesktopSessionObserver;
import com.javaclaw.desktop.api.DesktopSessionOwner;
import com.javaclaw.desktop.api.DesktopSessionState;
import com.javaclaw.desktop.api.DesktopTarget;
import com.javaclaw.desktop.api.DesktopVirtualInputState;
import com.javaclaw.desktop.agent.DesktopSessionTools;
import com.javaclaw.desktop.service.DefaultDesktopSessionService;
import com.javaclaw.desktop.service.LatestPublisher;
import com.javaclaw.desktop.spi.DesktopPlatformProvider;
import com.javaclaw.desktop.spi.DesktopPlatformSession;
import java.time.Clock;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Flow;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesktopSessionServiceTest {
    private static final DesktopSessionOwner OWNER =
            new DesktopSessionOwner("workspace-a", "run-1", "agent", "message-1");
    private static final DesktopTarget TARGET_A = target("target-a");
    private static final DesktopTarget TARGET_B = target("target-b");

    @Test
    void liveSessionInventoryIsExactOwnerScopedAndReflectsCloseWithoutNativeCalls() {
        FakeProvider provider = new FakeProvider("test", true, List.of(TARGET_A, TARGET_B));
        DesktopSessionOwner differentSource = new DesktopSessionOwner(
                OWNER.workspaceId(), OWNER.scopeId(), OWNER.sourceKind(), "other-source");
        try (var service = new DefaultDesktopSessionService(List.of(provider), allowAll())) {
            assertEquals(List.of(), service.liveSessionIds(OWNER).orElseThrow());
            String own = service.open(OWNER, TARGET_A.id(), true).toCompletableFuture().join().sessionId();
            String other = service.open(differentSource, TARGET_B.id(), true)
                    .toCompletableFuture().join().sessionId();
            int discoveries = provider.discoveryCount.get();
            assertEquals(List.of(own), service.liveSessionIds(OWNER).orElseThrow());
            assertEquals(List.of(other), service.liveSessionIds(differentSource).orElseThrow());
            assertEquals(discoveries, provider.discoveryCount.get(),
                    "liveness is an in-memory snapshot, not native discovery or permission probing");
            service.closeSession(OWNER, own);
            assertEquals(List.of(), service.liveSessionIds(OWNER).orElseThrow());
            assertEquals(List.of(other), service.liveSessionIds(differentSource).orElseThrow());
        }
    }

    @Test
    void launchRunsOnceAndReturnsOnlyWindowsBelongingToTheNewProcess() {
        FakeProvider provider = new FakeProvider("test", true, List.of(TARGET_A));
        DesktopTarget launchedWindow = new DesktopTarget("test", "reader-window", 202,
                "阅读器", "书架", 0, 0, 800, 600, DesktopTarget.VISIBLE);
        provider.nextLaunch = new DesktopApplicationLaunch(202, "已启动阅读器");
        provider.targetsAfterLaunch = List.of(TARGET_A, launchedWindow);
        provider.targetVisibleAfterDiscoveries = 1;
        try (var service = new DefaultDesktopSessionService(List.of(provider), allowAll())) {
            DesktopApplicationLaunchResult result = service.launchApplication(OWNER, " 阅读器 ")
                    .toCompletableFuture().join();
            assertEquals(202, result.processId());
            assertEquals(List.of(launchedWindow), result.targets());
            assertEquals(1, provider.launchCount.get(), "rediscovery must not repeat the launch");
            assertTrue(provider.discoveryCount.get() >= 2,
                    "the service should rediscover after the first window-free result");
            assertEquals("阅读器", provider.lastLaunchedApplication);
        }
    }

    @Test
    void launchedApplicationWithMultipleWindowsIgnoresAnotherOwnersMatchingTitle() {
        DesktopTarget statusItem = new DesktopTarget("test", "status-item", 101,
                "系统界面", "QQ", 0, 0, 100, 40,
                DesktopTarget.VISIBLE | DesktopTarget.SYSTEM_SURFACE);
        DesktopTarget first = new DesktopTarget("test", "qq-main", 202,
                "QQ", "会话", 0, 40, 500, 500, DesktopTarget.VISIBLE);
        DesktopTarget second = new DesktopTarget("test", "qq-settings", 202,
                "QQ", "设置", 500, 40, 500, 500, DesktopTarget.VISIBLE);
        FakeProvider provider = new FakeProvider("test", true, List.of(statusItem));
        provider.nextLaunch = new DesktopApplicationLaunch(202, "");
        provider.targetsAfterLaunch = List.of(statusItem, first, second);
        try (var service = new DefaultDesktopSessionService(List.of(provider), allowAll())) {
            DesktopApplicationLaunchResult result = service.launchApplication(OWNER, "QQ")
                    .toCompletableFuture().join();
            assertEquals(List.of(first, second), result.targets());
            assertEquals(1, provider.launchCount.get());
        }
    }

    @Test
    void launchResolvesAChildWindowByStableOwnerIdentity() {
        DesktopTarget child = new DesktopTarget("test", "reader-child", 303,
                "阅读器", "书架", 0, 0, 800, 600, DesktopTarget.VISIBLE,
                "com.example.reader");
        DesktopTarget unrelated = new DesktopTarget("test", "other", 404,
                "其他应用", "阅读器", 0, 0, 400, 300, DesktopTarget.VISIBLE,
                "com.example.other");
        FakeProvider provider = new FakeProvider("test", true, List.of());
        provider.nextLaunch = new DesktopApplicationLaunch(202, "com.example.reader", "accepted");
        provider.targetsAfterLaunch = List.of(unrelated, child);
        try (var service = new DefaultDesktopSessionService(List.of(provider), allowAll())) {
            DesktopApplicationLaunchResult result = service.launchApplication(OWNER, "阅读器")
                    .toCompletableFuture().join();
            assertEquals("com.example.reader", result.applicationId());
            assertEquals(List.of(child), result.targets());
            assertEquals(1, provider.launchCount.get());
        }
    }

    @Test
    void discoveryFailureAfterLaunchPreservesProcessIdAndDoesNotRepeatLaunch() {
        FakeProvider provider = new FakeProvider("test", true, List.of());
        provider.nextLaunch = new DesktopApplicationLaunch(202, "");
        provider.discoveryFailureAfterLaunch = new IllegalStateException("native discovery failed");
        try (var service = new DefaultDesktopSessionService(List.of(provider), allowAll())) {
            CompletionException failed = assertThrows(CompletionException.class,
                    () -> service.launchApplication(OWNER, "阅读器")
                            .toCompletableFuture().join());
            assertTrue(failed.getCause() instanceof DesktopApplicationLaunchUncertainException);
            assertEquals(202,
                    ((DesktopApplicationLaunchUncertainException) failed.getCause()).processId());
            assertEquals(1, provider.launchCount.get());
        }
    }

    @Test
    void launchRequiresComputerAccessAndRejectsPathsBeforePlatformCall() {
        FakeProvider provider = new FakeProvider("test", true, List.of());
        AtomicBoolean enabled = new AtomicBoolean(false);
        DesktopConsentPort policy = new DesktopConsentPort() {
            @Override public boolean enabled() { return enabled.get(); }
            @Override public boolean request(DesktopSessionOwner owner, DesktopTarget target,
                                             Purpose purpose) { return true; }
        };
        try (var service = new DefaultDesktopSessionService(List.of(provider), policy)) {
            var denied = assertThrows(DesktopApplicationLaunchRejectedException.class,
                    () -> service.launchApplication(OWNER, "阅读器"));
            assertEquals("ACCESS_DENIED", denied.reasonCode());
            assertFalse(denied.dispatchAttempted());
            enabled.set(true);
            assertThrows(DesktopApplicationLaunchRejectedException.class,
                    () -> service.launchApplication(OWNER, "/Applications/Reader.app"));
            assertThrows(DesktopApplicationLaunchRejectedException.class,
                    () -> service.launchApplication(OWNER, "阅读器\nopen -a Terminal"));
            assertEquals(0, provider.launchCount.get());
        }
    }

    @Test
    void selectsOnlyAvailableProviderAndReportsUnavailableWhenNoneCanRun() {
        FakeProvider unavailable = new FakeProvider("windows", false, List.of(TARGET_A));
        FakeProvider selected = new FakeProvider("macos", true, List.of(TARGET_A));
        try (var service = new DefaultDesktopSessionService(List.of(unavailable, selected), allowAll())) {
            assertTrue(service.availability().available());
            assertEquals("macos", service.availability().providerId());
            assertEquals(List.of(TARGET_A), service.discoverTargets().toCompletableFuture().join());
            assertEquals(0, unavailable.discoveryCount.get());
            assertEquals(1, selected.discoveryCount.get());
            selected.available = false;
            assertFalse(service.availability().available(), "revoked permissions must change availability");
            assertTrue(service.discoverTargets().toCompletableFuture().join().isEmpty());
            selected.available = true;
            assertTrue(service.availability().available(), "newly granted permissions must recover");
        }
        try (var service = new DefaultDesktopSessionService(List.of(unavailable), allowAll())) {
            assertFalse(service.availability().available());
            assertTrue(service.availability().detail().contains("windows"));
            assertTrue(service.discoverTargets().toCompletableFuture().join().isEmpty());
        }
    }

    @Test
    void settingsSwitchGatesDiscoveryAndClosesAnExistingSessionWhenTurnedOff() {
        FakeProvider provider = new FakeProvider("test", true, List.of(TARGET_A));
        AtomicBoolean enabled = new AtomicBoolean(false);
        DesktopConsentPort policy = new DesktopConsentPort() {
            @Override public boolean enabled() { return enabled.get(); }
            @Override public boolean request(DesktopSessionOwner owner, DesktopTarget target,
                                             Purpose purpose) { return true; }
        };
        try (var service = new DefaultDesktopSessionService(List.of(provider), policy)) {
            assertFalse(service.availability().available());
            assertTrue(service.discoverTargets().toCompletableFuture().join().isEmpty());
            assertThrows(SecurityException.class, () -> service.open(OWNER, TARGET_A.id(), true));

            enabled.set(true);
            String id = service.open(OWNER, TARGET_A.id(), true).toCompletableFuture().join().sessionId();
            assertEquals(1, provider.opened.size());
            enabled.set(false);
            assertThrows(SecurityException.class, () -> service.perform(OWNER, id, click(1)));
            await(() -> provider.opened.getFirst().closed);
            assertFalse(service.availability().available());
        }
    }

    @Test
    void revokedOsPermissionPausesExistingSessionAndRejectsObservationAndInput() {
        FakeProvider provider = new FakeProvider("test", true, List.of(TARGET_A));
        AtomicBoolean permitted = new AtomicBoolean(true);
        DesktopConsentPort policy = new DesktopConsentPort() {
            @Override public boolean request(DesktopSessionOwner owner, DesktopTarget target,
                                             Purpose purpose) { return true; }
            @Override public DesktopAvailability accessStatus() {
                return new DesktopAvailability(permitted.get(), "test", 0,
                        permitted.get() ? "ready" : "system permission revoked");
            }
        };
        try (var service = new DefaultDesktopSessionService(List.of(provider), policy)) {
            String id = service.open(OWNER, TARGET_A.id(), true).toCompletableFuture().join().sessionId();
            FakeSession platform = provider.opened.getFirst();
            RecordingSubscriber<DesktopSessionState> states = new RecordingSubscriber<>();
            service.states(OWNER, id).subscribe(states);
            states.request(Long.MAX_VALUE);
            platform.emit(frame(TARGET_A, 1));
            await(() -> service.snapshot(OWNER, id).toCompletableFuture().join().isPresent());

            permitted.set(false);
            assertFalse(service.availability().available());
            assertThrows(SecurityException.class, () -> service.snapshot(OWNER, id));
            assertTrue(assertThrows(SecurityException.class, () -> service.state(OWNER, id))
                    .getMessage().contains("system permission revoked"));
            assertThrows(SecurityException.class, () -> service.perform(OWNER, id, click(1)));
            await(() -> states.items.stream().anyMatch(state ->
                    state.kind() == DesktopSessionState.Kind.PAUSED
                            && state.detail().contains("权限")));
            assertEquals(0, platform.actionCount.get());
            assertFalse(platform.closed, "the session may resume after permission is restored");
        }
    }

    @Test
    void targetConsentSeparatesObservationFromControlAndPrecedesNativeOpen() {
        FakeProvider provider = new FakeProvider("test", true, List.of(TARGET_A, TARGET_B));
        List<String> requests = new CopyOnWriteArrayList<>();
        DesktopConsentPort consent = (owner, target, purpose) -> {
            requests.add(target.id() + ":" + purpose);
            return !(target.id().equals(TARGET_B.id()) && purpose == DesktopConsentPort.Purpose.CONTROL);
        };
        try (var service = new DefaultDesktopSessionService(List.of(provider), consent)) {
            DesktopSessionInfo observed = service.open(OWNER, TARGET_A.id(), false)
                    .toCompletableFuture().join();
            assertFalse(observed.controlGranted());
            assertEquals(List.of("target-a:OBSERVE"), requests);
            CompletionException denied = assertThrows(CompletionException.class,
                    () -> service.open(OWNER, TARGET_B.id(), true).toCompletableFuture().join());
            assertTrue(denied.getCause() instanceof SecurityException);
            assertEquals(List.of("target-a:OBSERVE", "target-b:OBSERVE", "target-b:CONTROL"), requests);
            assertEquals(1, provider.opened.size());

            FakeSession nativeSession = provider.opened.getFirst();
            nativeSession.emit(frame(TARGET_A, 1));
            await(() -> service.snapshot(OWNER, observed.sessionId())
                    .toCompletableFuture().join().isPresent());
            DesktopActionResult result = service.perform(OWNER, observed.sessionId(), click(1))
                    .toCompletableFuture().join();
            assertEquals(DesktopActionResult.Status.DENIED, result.status());
            assertEquals(DesktopActionResult.Reason.SESSION_CONTROL_REQUIRED, result.reason());
            assertEquals(DesktopActionResult.NextStep.OPEN_SESSION, result.nextStep());
            assertEquals(DesktopActionResult.Delivery.NOT_SENT, result.delivery());
            assertFalse(result.dispatchAttempted());
            assertEquals(0, nativeSession.actionCount.get());
        }
    }

    @Test
    void openingTheSameTargetReusesItsNativeSessionAndUpgradesControlInPlace() {
        FakeProvider provider = new FakeProvider("test", true, List.of(TARGET_A));
        try (var service = new DefaultDesktopSessionService(List.of(provider), allowAll())) {
            String id = service.open(OWNER, TARGET_A.id(), false)
                    .toCompletableFuture().join().sessionId();
            FakeSession nativeSession = provider.opened.getFirst();
            nativeSession.emit(frame(TARGET_A, 1));
            await(() -> service.snapshot(OWNER, id).toCompletableFuture().join().isPresent());
            DesktopObservation old = observeCommitted(service, id);

            DesktopActionResult readOnly = service.perform(OWNER, id, click(1, old.observationId()))
                    .toCompletableFuture().join();
            assertEquals(DesktopActionResult.Reason.SESSION_CONTROL_REQUIRED, readOnly.reason());
            assertEquals(DesktopActionResult.NextStep.OPEN_SESSION, readOnly.nextStep());
            assertTrue(service.availability().available());
            assertFalse(service.info(OWNER, id).controlGranted(),
                    "probing ready OS permissions must not grant control to a read-only session");
            assertEquals(0, nativeSession.actionCount.get());

            var upgraded = service.open(OWNER, TARGET_A.id(), true).toCompletableFuture().join();
            assertEquals(id, upgraded.sessionId());
            assertTrue(upgraded.controlGranted());
            assertEquals(id, service.open(OWNER, TARGET_A.id(), false)
                    .toCompletableFuture().join().sessionId());
            assertEquals(1, provider.opened.size());
            assertEquals(DesktopActionResult.Status.STALE_FRAME,
                    service.perform(OWNER, id, click(1, old.observationId()))
                            .toCompletableFuture().join().status(),
                    "control escalation invalidates an earlier read-only observation");
            DesktopObservation fresh = observeCommitted(service, id);
            assertEquals(DesktopActionResult.Status.VERIFIED,
                    service.perform(OWNER, id, click(1, fresh.observationId()))
                            .toCompletableFuture().join().status());
            assertEquals(1, nativeSession.actionCount.get());
        }
    }

    @Test
    void upgradingReadOnlySessionStillRequiresTargetControlConsent() {
        FakeProvider provider = new FakeProvider("test", true, List.of(TARGET_A));
        AtomicBoolean consented = new AtomicBoolean(false);
        List<DesktopConsentPort.Purpose> requests = new CopyOnWriteArrayList<>();
        DesktopConsentPort policy = (owner, target, purpose) -> {
            requests.add(purpose);
            return purpose != DesktopConsentPort.Purpose.CONTROL || consented.get();
        };
        try (var service = new DefaultDesktopSessionService(List.of(provider), policy)) {
            String id = service.open(OWNER, TARGET_A.id(), false).toCompletableFuture().join().sessionId();
            FakeSession platform = provider.opened.getFirst();
            platform.emit(frame(TARGET_A, 1));
            await(() -> service.snapshot(OWNER, id).toCompletableFuture().join().isPresent());
            DesktopObservation old = observeCommitted(service, id);
            CompletionException rejected = assertThrows(CompletionException.class,
                    () -> service.open(OWNER, TARGET_A.id(), true).toCompletableFuture().join());
            assertTrue(rejected.getCause() instanceof SecurityException);
            assertTrue(requests.contains(DesktopConsentPort.Purpose.CONTROL));
            assertFalse(service.info(OWNER, id).controlGranted());
            assertEquals(DesktopActionResult.Reason.SESSION_CONTROL_REQUIRED,
                    service.perform(OWNER, id, click(1, old.observationId()))
                            .toCompletableFuture().join().reason());
            assertEquals(0, platform.actionCount.get());
            consented.set(true);
            assertTrue(service.open(OWNER, TARGET_A.id(), true).toCompletableFuture().join().controlGranted());
            assertEquals(1, provider.opened.size());
            assertEquals(DesktopActionResult.Status.STALE_FRAME,
                    service.perform(OWNER, id, click(1, old.observationId()))
                            .toCompletableFuture().join().status());
            DesktopObservation fresh = observeCommitted(service, id);
            assertEquals(DesktopActionResult.Status.VERIFIED,
                    service.perform(OWNER, id, click(1, fresh.observationId()))
                            .toCompletableFuture().join().status());
        }
    }

    @Test
    void concurrentOpenCallsCreateOnlyOneNativeSession() {
        FakeProvider provider = new FakeProvider("test", true, List.of(TARGET_A));
        try (var service = new DefaultDesktopSessionService(List.of(provider), allowAll())) {
            var read = service.open(OWNER, TARGET_A.id(), false).toCompletableFuture();
            var control = service.open(OWNER, TARGET_A.id(), true).toCompletableFuture();
            assertEquals(read.join().sessionId(), control.join().sessionId());
            assertTrue(service.info(OWNER, read.join().sessionId()).controlGranted());
            assertEquals(1, provider.opened.size());
        }
    }

    @Test
    void capturePermissionAllowsObservationWhileMissingInputBlocksControl() {
        FakeProvider provider = new FakeProvider("test", true, List.of(TARGET_A));
        AtomicBoolean inputReady = new AtomicBoolean(false);
        DesktopConsentPort policy = new DesktopConsentPort() {
            @Override public boolean request(DesktopSessionOwner owner, DesktopTarget target,
                                             Purpose purpose) {
                return accessStatus(purpose).available();
            }
            @Override public DesktopAvailability accessStatus() {
                return accessStatus(Purpose.OBSERVE);
            }
            @Override public DesktopAvailability accessStatus(Purpose purpose) {
                boolean ready = purpose == Purpose.OBSERVE || inputReady.get();
                return new DesktopAvailability(ready, "test", DesktopAvailability.CAPTURE,
                        ready ? "ready" : "input permission missing");
            }
        };
        try (var service = new DefaultDesktopSessionService(List.of(provider), policy)) {
            String id = service.open(OWNER, TARGET_A.id(), false)
                    .toCompletableFuture().join().sessionId();
            provider.opened.getFirst().emit(frame(TARGET_A, 1));
            await(() -> service.snapshot(OWNER, id).toCompletableFuture().join().isPresent());
            assertTrue(observeCommitted(service, id).observationId().length() > 0);
            assertThrows(SecurityException.class,
                    () -> service.open(OWNER, TARGET_A.id(), true));
            assertThrows(SecurityException.class,
                    () -> service.perform(OWNER, id, click(1)));
            assertTrue(service.snapshot(OWNER, id).toCompletableFuture().join().isPresent());
            inputReady.set(true);
            assertEquals(id, service.open(OWNER, TARGET_A.id(), true)
                    .toCompletableFuture().join().sessionId());
            assertEquals(1, provider.opened.size());
        }
    }

    @Test
    void ownerIncludesWorkspaceScopeAndRunOriginAndWorkspaceCloseReleasesNativeSessions() {
        FakeProvider provider = new FakeProvider("test", true, List.of(TARGET_A));
        DesktopSessionOwner otherScope = new DesktopSessionOwner("workspace-a", "run-2", "agent", "message-1");
        DesktopSessionOwner otherOrigin = new DesktopSessionOwner("workspace-a", "run-1", "agent", "message-2");
        DesktopSessionOwner otherWorkspace = new DesktopSessionOwner("workspace-b", "run-1", "agent", "message-1");
        try (var service = new DefaultDesktopSessionService(List.of(provider), allowAll())) {
            String a = service.open(OWNER, TARGET_A.id(), true).toCompletableFuture().join().sessionId();
            String b = service.open(otherScope, TARGET_A.id(), true).toCompletableFuture().join().sessionId();
            String c = service.open(otherWorkspace, TARGET_A.id(), true).toCompletableFuture().join().sessionId();
            assertNotEquals(a, b);
            assertThrows(SecurityException.class, () -> service.info(otherScope, a));
            assertThrows(SecurityException.class, () -> service.info(otherOrigin, a));
            assertThrows(SecurityException.class, () -> service.frames(otherWorkspace, a));
            assertThrows(SecurityException.class, () -> service.closeSession(otherScope, a));

            service.closeScope("workspace-a", "run-1");
            assertTrue(provider.opened.get(0).closed);
            assertFalse(provider.opened.get(1).closed);
            assertFalse(provider.opened.get(2).closed);
            assertThrows(SecurityException.class, () -> service.info(OWNER, a));

            service.closeWorkspace("workspace-a");
            assertTrue(provider.opened.get(1).closed);
            assertFalse(provider.opened.get(2).closed);
            assertEquals(c, service.info(otherWorkspace, c).sessionId());
        }
        assertTrue(provider.opened.get(2).closed);
        assertEquals(1, provider.opened.get(0).closeCount.get());
        assertEquals(1, provider.opened.get(1).closeCount.get());
        assertEquals(1, provider.opened.get(2).closeCount.get());
    }

    @Test
    void platformAcceptedInputRequiresFreshObservationWithoutCreatingUnknownDeliveryFence() {
        FakeProvider provider = new FakeProvider("test", true, List.of(TARGET_A));
        try (var service = new DefaultDesktopSessionService(List.of(provider), allowAll())) {
            String id = service.open(OWNER, TARGET_A.id(), true).toCompletableFuture().join().sessionId();
            FakeSession platform = provider.opened.getFirst();
            platform.emit(frame(TARGET_A, 1));
            await(() -> service.snapshot(OWNER, id).toCompletableFuture().join().isPresent());
            DesktopObservation before = observeCommitted(service, id);
            platform.nextResult = new DesktopActionResult(DesktopActionResult.Status.ACCEPTED,
                    "platform admitted input; application outcome still unknown", 1);
            DesktopActionResult accepted = service.perform(OWNER, id,
                    click(1, before.observationId())).toCompletableFuture().join();
            assertEquals(DesktopActionResult.Status.ACCEPTED, accepted.status());
            assertEquals(DesktopActionResult.Delivery.SENT, accepted.delivery());
            assertEquals(DesktopActionResult.NextStep.OBSERVE, accepted.nextStep());
            assertFalse(service.state(OWNER, id).detail().contains("操作结果未知"));
            DesktopActionResult replay = service.perform(OWNER, id,
                    click(1, before.observationId())).toCompletableFuture().join();
            assertTrue(replay.status() == DesktopActionResult.Status.DENIED
                    || replay.status() == DesktopActionResult.Status.STALE_FRAME);
            assertEquals(DesktopActionResult.Delivery.NOT_SENT, replay.delivery());
            assertEquals(1, platform.actionCount.get());
            platform.emit(new DesktopFrame(TARGET_A.id(), 1, System.currentTimeMillis() + 250,
                    1, 1, 4, new byte[] { 0, 0, 0, (byte) 255 }));
            await(() -> service.snapshot(OWNER, id).toCompletableFuture().join().isPresent());
            DesktopObservation after = observeCommitted(service, id);
            assertEquals(DesktopActionResult.Status.ACCEPTED, service.perform(OWNER, id,
                    click(1, after.observationId())).toCompletableFuture().join().status());
            assertEquals(2, platform.actionCount.get());
        }
    }

    @Test
    void staleGenerationNeverReachesPlatformAndUnknownResultIsNotRetried() {
        FakeProvider provider = new FakeProvider("test", true, List.of(TARGET_A));
        List<DesktopConsentPort.Purpose> purposes = new CopyOnWriteArrayList<>();
        DesktopConsentPort consent = (owner, target, purpose) -> {
            purposes.add(purpose);
            return true;
        };
        try (var service = new DefaultDesktopSessionService(List.of(provider), consent, Clock.systemUTC())) {
            String id = service.open(OWNER, TARGET_A.id(), true).toCompletableFuture().join().sessionId();
            FakeSession platform = provider.opened.getFirst();
            RecordingSubscriber<DesktopSessionState> states = new RecordingSubscriber<>();
            service.states(OWNER, id).subscribe(states);
            states.request(Long.MAX_VALUE);
            platform.emit(frame(TARGET_A, 2));
            await(() -> service.snapshot(OWNER, id).toCompletableFuture().join()
                    .map(value -> value.windowGeneration() == 2).orElse(false));

            DesktopActionResult stale = service.perform(OWNER, id, click(1)).toCompletableFuture().join();
            assertEquals(DesktopActionResult.Status.STALE_FRAME, stale.status());
            assertEquals(2, stale.windowGeneration());
            assertEquals(0, platform.actionCount.get());

            DesktopObservation firstObservation = observeCommitted(service, id);

            platform.nextResult = new DesktopActionResult(DesktopActionResult.Status.UNKNOWN,
                    "delivery could not be verified", 2);
            DesktopActionResult unknown = service.perform(OWNER, id,
                    click(2, firstObservation.observationId())).toCompletableFuture().join();
            assertEquals(DesktopActionResult.Status.UNKNOWN, unknown.status());
            assertTrue(unknown.dispatchAttempted());
            assertEquals(DesktopActionResult.NextStep.OBSERVE, unknown.nextStep());
            await(() -> states.items.stream().anyMatch(state ->
                    state.kind() == DesktopSessionState.Kind.PAUSED
                            && state.detail().contains("未知")));
            assertEquals(1, platform.actionCount.get());
            assertEquals(DesktopActionResult.Status.DENIED,
                    service.perform(OWNER, id, click(2, firstObservation.observationId()))
                            .toCompletableFuture().join().status());
            assertEquals(1, platform.actionCount.get(), "unknown delivery must not be retried");
            assertTrue(service.snapshot(OWNER, id).toCompletableFuture().join().isEmpty());

            platform.nextResult = new DesktopActionResult(DesktopActionResult.Status.VERIFIED,
                    "verified", 2);
            platform.emit(new DesktopFrame(TARGET_A.id(), 2, System.currentTimeMillis() + 250,
                    1, 1, 4, new byte[] { 0, 0, 0, (byte) 255 }));
            await(() -> service.snapshot(OWNER, id).toCompletableFuture().join().isPresent());
            assertEquals(DesktopActionResult.Status.DENIED,
                    service.perform(OWNER, id, click(2, firstObservation.observationId()))
                            .toCompletableFuture().join().status(),
                    "a new capture without a successful interpretation cannot unlock input");
            DesktopObservation secondObservation = observeCommitted(service, id);
            assertEquals(DesktopSessionState.Kind.LIVE, service.state(OWNER, id).kind(),
                    "new input may use the interpreted post-settle baseline without verifying the old effect");
            assertEquals(1, platform.actionCount.get());
            assertFalse(service.reconcilePendingAction(OWNER, id,
                    "wrong-action", secondObservation.observationId()).toCompletableFuture().join());
            assertFalse(service.reconcilePendingAction(OWNER, id,
                    firstObservation.observationId(), "wrong-evidence").toCompletableFuture().join());
            assertEquals(DesktopActionResult.Status.STALE_FRAME,
                    service.perform(OWNER, id, click(2, firstObservation.observationId()))
                            .toCompletableFuture().join().status(),
                    "refresh must not permit replaying the old action observation");
            assertEquals(DesktopActionResult.Status.VERIFIED,
                    service.perform(OWNER, id, click(2, secondObservation.observationId()))
                            .toCompletableFuture().join().status(),
                    "a new action can use the fresh baseline before business reconciliation");
            assertEquals(2, platform.actionCount.get());
            assertTrue(service.reconcilePendingAction(OWNER, id,
                    firstObservation.observationId(), secondObservation.observationId())
                    .toCompletableFuture().join());
            assertTrue(service.reconcilePendingAction(OWNER, id,
                    firstObservation.observationId(), secondObservation.observationId())
                    .toCompletableFuture().join(),
                    "replaying the same durable verification must be harmless");
            assertFalse(service.info(OWNER, id).foregroundGranted());
            assertFalse(service.authorizeForeground(OWNER, id).toCompletableFuture().join());
            assertFalse(service.info(OWNER, id).foregroundGranted());
            assertFalse(purposes.contains(DesktopConsentPort.Purpose.FOREGROUND_TAKEOVER));
        }
    }

    @Test
    void strictSessionCannotAuthorizeSystemInputOrForeground() {
        FakeProvider provider = new FakeProvider("test", true, List.of(TARGET_A));
        try (var service = new DefaultDesktopSessionService(List.of(provider), allowAll())) {
            String id = service.open(OWNER, TARGET_A.id(), true).toCompletableFuture().join().sessionId();
            assertEquals(DesktopInputPolicy.BACKGROUND_STRICT, service.info(OWNER, id).inputPolicy());
            assertFalse(service.authorizeSystemInput(OWNER, id).toCompletableFuture().join());
            assertFalse(service.authorizeForeground(OWNER, id).toCompletableFuture().join());
            assertFalse(service.info(OWNER, id).foregroundGranted());
            assertEquals(0, provider.opened.getFirst().actionCount.get());
            assertEquals(0, provider.opened.getFirst().prepareCount.get());
        }
    }

    @Test
    @DisplayName("后台输入历史不能被新观察或效果核验清除，显式前台接管仍可用")
    void systemInputRemainsDeniedAfterBackgroundDispatchFenceIsReconciled() {
        FakeProvider provider = new FakeProvider("test", true, List.of(TARGET_A));
        List<DesktopConsentPort.Purpose> purposes = new CopyOnWriteArrayList<>();
        DesktopConsentPort consent = (owner, target, purpose) -> {
            purposes.add(purpose);
            return true;
        };
        long capturedAt = System.currentTimeMillis();
        Clock captureClock = Clock.fixed(java.time.Instant.ofEpochMilli(capturedAt),
                java.time.ZoneOffset.UTC);
        try (var service = new DefaultDesktopSessionService(List.of(provider), consent, captureClock)) {
            String id = service.open(OWNER, TARGET_A.id(), true)
                    .toCompletableFuture().join().sessionId();
            FakeSession platform = provider.opened.getFirst();
            platform.emit(new DesktopFrame(TARGET_A.id(), 1, capturedAt,
                    1, 1, 4, new byte[] { 0, 0, 0, (byte) 255 }));
            await(() -> service.snapshot(OWNER, id).toCompletableFuture().join().isPresent());
            DesktopObservation before = observeCommitted(service, id);
            platform.nextResult = new DesktopActionResult(DesktopActionResult.Status.UNKNOWN,
                    "may have been sent", 1);
            DesktopActionResult result = service.perform(OWNER, id,
                    click(1, before.observationId())).toCompletableFuture().join();
            assertTrue(result.dispatchAttempted());
            assertEquals(List.of(false), platform.foregroundModes);

            platform.emit(new DesktopFrame(TARGET_A.id(), 1, capturedAt + 250,
                    1, 1, 4, new byte[] { 0, 0, 0, (byte) 255 }, 2));
            await(() -> service.snapshot(OWNER, id).toCompletableFuture().join()
                    .map(value -> value.contentRevision() == 2).orElse(false));
            DesktopObservation after = observeCommitted(service, id);
            assertEquals(DesktopSessionState.Kind.LIVE, service.state(OWNER, id).kind());
            assertTrue(service.reconcilePendingAction(OWNER, id,
                    before.observationId(), after.observationId()).toCompletableFuture().join());
            int consentCount = purposes.size();

            assertFalse(service.authorizeSystemInput(OWNER, id).toCompletableFuture().join(),
                    "策略不可提升，输入屏障已清除也不能自动改用系统输入");
            assertFalse(service.info(OWNER, id).foregroundGranted());
            assertEquals(consentCount, purposes.size(), "拒绝自动切换时不应请求前台授权");
            assertEquals(1, platform.actionCount.get());
            assertFalse(service.authorizeForeground(OWNER, id).toCompletableFuture().join(),
                    "用户人工后台面板与系统输入权限分离");
        }
    }

    @Test
    @DisplayName("后台已接受输入后同任务重开会话仍不能切换系统输入，新任务可以")
    void backgroundDispatchHistorySurvivesReopenButDoesNotBlockANewScope() {
        FakeProvider provider = new FakeProvider("test", true, List.of(TARGET_A));
        DesktopSessionOwner sameScopeOwner = new DesktopSessionOwner(
                OWNER.workspaceId(), OWNER.scopeId(), OWNER.sourceKind(), "message-2");
        DesktopSessionOwner newScopeOwner = new DesktopSessionOwner(
                OWNER.workspaceId(), "run-2", OWNER.sourceKind(), "message-3");
        try (var service = new DefaultDesktopSessionService(List.of(provider), allowAll())) {
            String firstId = service.open(OWNER, TARGET_A.id(), true)
                    .toCompletableFuture().join().sessionId();
            FakeSession firstPlatform = provider.opened.getFirst();
            firstPlatform.emit(frame(TARGET_A, 1));
            await(() -> service.snapshot(OWNER, firstId).toCompletableFuture().join().isPresent());
            DesktopObservation before = observeCommitted(service, firstId);
            firstPlatform.nextResult = new DesktopActionResult(DesktopActionResult.Status.ACCEPTED,
                    "input accepted", 1);
            DesktopActionResult accepted = service.perform(OWNER, firstId,
                    click(1, before.observationId())).toCompletableFuture().join();
            assertEquals(DesktopActionResult.Status.ACCEPTED, accepted.status());
            assertEquals(DesktopActionResult.Delivery.SENT, accepted.delivery());
            assertEquals(List.of(false), firstPlatform.foregroundModes);
            service.closeSession(OWNER, firstId);

            String reopenedId = service.open(sameScopeOwner, TARGET_A.id(), true)
                    .toCompletableFuture().join().sessionId();
            FakeSession reopened = provider.opened.getLast();
            assertNotEquals(firstId, reopenedId);
            assertFalse(service.authorizeSystemInput(sameScopeOwner, reopenedId)
                    .toCompletableFuture().join());
            assertThrows(CompletionException.class, () -> service.open(sameScopeOwner, TARGET_A.id(),
                    true, DesktopInputPolicy.SYSTEM_EXPLICIT).toCompletableFuture().join());
            assertFalse(service.info(sameScopeOwner, reopenedId).foregroundGranted());
            assertEquals(0, reopened.actionCount.get());

            String newScopeId = service.open(newScopeOwner, TARGET_A.id(), true, DesktopInputPolicy.SYSTEM_EXPLICIT)
                    .toCompletableFuture().join().sessionId();
            assertTrue(service.authorizeSystemInput(newScopeOwner, newScopeId).toCompletableFuture().join(),
                    "已接受的后台输入没有未决派发屏障，不应阻止新任务选择系统输入");
            assertTrue(service.info(newScopeOwner, newScopeId).foregroundGranted());
            assertEquals(0, provider.opened.getLast().actionCount.get());
        }
    }

    @Test
    @DisplayName("同一目标的未决输入阻止其他全新会话自动选择系统输入")
    void targetWidePendingInputBlocksSystemInputInAnotherFreshSession() {
        FakeProvider provider = new FakeProvider("test", true, List.of(TARGET_A, TARGET_B));
        DesktopSessionOwner otherOwner = new DesktopSessionOwner(
                OWNER.workspaceId(), "run-2", "agent", "message-2");
        try (var service = new DefaultDesktopSessionService(List.of(provider), allowAll())) {
            String firstId = service.open(OWNER, TARGET_A.id(), true)
                    .toCompletableFuture().join().sessionId();
            FakeSession firstPlatform = provider.opened.getFirst();
            firstPlatform.emit(frame(TARGET_A, 1));
            await(() -> service.snapshot(OWNER, firstId).toCompletableFuture().join().isPresent());
            DesktopObservation before = observeCommitted(service, firstId);
            firstPlatform.nextResult = new DesktopActionResult(DesktopActionResult.Status.UNKNOWN,
                    "may have been sent", 1);
            assertEquals(DesktopActionResult.Status.UNKNOWN,
                    service.perform(OWNER, firstId, click(1, before.observationId()))
                            .toCompletableFuture().join().status());

            assertThrows(CompletionException.class,
                    () -> service.open(otherOwner, TARGET_A.id(), true, DesktopInputPolicy.SYSTEM_EXPLICIT)
                            .toCompletableFuture().join(),
                    "同一目标未决输入不能通过新系统会话绕过");
            String unrelatedId = service.open(otherOwner, TARGET_B.id(), true,
                    DesktopInputPolicy.SYSTEM_EXPLICIT).toCompletableFuture().join().sessionId();
            assertTrue(service.info(otherOwner, unrelatedId).foregroundGranted());
        }
    }

    @Test
    void definiteBackgroundUnsupportedNeverRequestsForegroundOrRepeatsClick() {
        FakeProvider provider = new FakeProvider("test", true, List.of(TARGET_A));
        List<DesktopConsentPort.Purpose> purposes = new CopyOnWriteArrayList<>();
        try (var service = new DefaultDesktopSessionService(List.of(provider), (owner, target, purpose) -> {
            purposes.add(purpose);
            return true;
        })) {
            String id = service.open(OWNER, TARGET_A.id(), true).toCompletableFuture().join().sessionId();
            FakeSession platform = provider.opened.getFirst();
            platform.emit(frame(TARGET_A, 1));
            await(() -> service.snapshot(OWNER, id).toCompletableFuture().join().isPresent());
            DesktopObservation initial = observeCommitted(service, id);
            platform.nextResult = new DesktopActionResult(DesktopActionResult.Status.UNSUPPORTED,
                    "no pressable semantic target", 1);
            DesktopActionResult unsupported = service.perform(OWNER, id,
                    click(1, initial.observationId())).toCompletableFuture().join();
            assertEquals(DesktopActionResult.Status.UNSUPPORTED, unsupported.status());
            assertFalse(unsupported.dispatchAttempted());
            assertFalse(service.info(OWNER, id).foregroundGranted());
            assertEquals(1, platform.actionCount.get());
            assertEquals(0, platform.prepareCount.get());
            assertFalse(purposes.contains(DesktopConsentPort.Purpose.FOREGROUND_TAKEOVER));
            assertEquals(DesktopActionResult.Status.STALE_FRAME, service.perform(OWNER, id,
                    click(1, initial.observationId())).toCompletableFuture().join().status());
            assertEquals(1, platform.actionCount.get());
        }
    }

    @Test
    void strictUnsupportedDoesNotAttemptEvenFailingForegroundPreparation() {
        FakeProvider provider = new FakeProvider("test", true, List.of(TARGET_A));
        try (var service = new DefaultDesktopSessionService(List.of(provider), allowAll())) {
            String id = service.open(OWNER, TARGET_A.id(), true).toCompletableFuture().join().sessionId();
            FakeSession platform = provider.opened.getFirst();
            platform.emit(frame(TARGET_A, 1));
            await(() -> service.snapshot(OWNER, id).toCompletableFuture().join().isPresent());
            DesktopObservation observed = observeCommitted(service, id);
            platform.prepareFailure = new IllegalStateException("must never activate");
            platform.nextResult = new DesktopActionResult(DesktopActionResult.Status.UNSUPPORTED,
                    "AX target has no press action", 1);
            DesktopActionResult result = service.perform(OWNER, id,
                    click(1, observed.observationId())).toCompletableFuture().join();
            assertEquals(DesktopActionResult.Status.UNSUPPORTED, result.status());
            assertFalse(result.dispatchAttempted());
            assertTrue(result.detail().contains("AX target has no press action"));
            assertEquals(0, platform.prepareCount.get());
        }
    }

    @Test
    void observationCarriesPlatformElementDiagnosticsEvenWhenNoElementsAreAvailable() {
        FakeProvider provider = new FakeProvider("test", true, List.of(TARGET_A));
        try (var service = new DefaultDesktopSessionService(List.of(provider), allowAll())) {
            String id = service.open(OWNER, TARGET_A.id(), true)
                    .toCompletableFuture().join().sessionId();
            FakeSession platform = provider.opened.getFirst();
            platform.exposeElements = false;
            platform.diagnostics = "AX catalog unavailable; status=unsupported";
            platform.emit(frame(TARGET_A, 1));
            await(() -> service.snapshot(OWNER, id).toCompletableFuture().join().isPresent());

            DesktopObservation observation = service.captureObservation(OWNER, id)
                    .toCompletableFuture().join().orElseThrow();
            assertTrue(observation.elements().isEmpty());
            assertEquals(platform.diagnostics, observation.elementDiagnostics());
            assertTrue(service.commitObservation(OWNER, id, observation.observationId(), List.of())
                    .toCompletableFuture().join());
        }
    }

    @Test
    void onlyLatestCommittedObservationCanAuthorizeAnInBoundsAction() {
        FakeProvider provider = new FakeProvider("test", true, List.of(TARGET_A));
        try (var service = new DefaultDesktopSessionService(List.of(provider), allowAll())) {
            String id = service.open(OWNER, TARGET_A.id(), true).toCompletableFuture().join().sessionId();
            FakeSession platform = provider.opened.getFirst();
            platform.emit(frame(TARGET_A, 1));
            await(() -> service.snapshot(OWNER, id).toCompletableFuture().join().isPresent());
            DesktopObservation first = observeCommitted(service, id);
            DesktopObservation newest = observeCommitted(service, id);

            assertEquals(DesktopActionResult.Status.STALE_FRAME,
                    service.perform(OWNER, id, click(1)).toCompletableFuture().join().status(),
                    "legacy actions without an observation ID must never replay input");
            assertEquals(DesktopActionResult.Status.STALE_FRAME,
                    service.perform(OWNER, id, click(1, first.observationId()))
                            .toCompletableFuture().join().status());
            assertEquals(DesktopActionResult.Status.STALE_FRAME,
                    service.perform(OWNER, id, new DesktopAction(DesktopAction.Kind.CLICK,
                            0, 0, 1, 1, 0, "", 1, newest.observationId(), "foreign-element", 0))
                            .toCompletableFuture().join().status());
            assertEquals(DesktopActionResult.Status.STALE_FRAME,
                    service.perform(OWNER, id, new DesktopAction(DesktopAction.Kind.CLICK,
                            1, 0, 1, 1, 0, "", 1, newest.observationId(), "", 0))
                            .toCompletableFuture().join().status());
            assertEquals(0, platform.actionCount.get());
            assertEquals(DesktopActionResult.Status.VERIFIED,
                    service.perform(OWNER, id, click(1, newest.observationId()))
                            .toCompletableFuture().join().status());
            assertEquals(1, platform.actionCount.get());
            assertEquals(DesktopActionResult.Status.STALE_FRAME,
                    service.perform(OWNER, id, click(1, newest.observationId()))
                            .toCompletableFuture().join().status(),
                    "consumed observations must not permit duplicate input");
            assertEquals(1, platform.actionCount.get());
        }
    }

    @Test
    void closingScopeDuringConsentCannotPublishOrLeakAPlatformSession() throws Exception {
        FakeProvider provider = new FakeProvider("test", true, List.of(TARGET_A));
        CountDownLatch consentEntered = new CountDownLatch(1);
        CountDownLatch releaseConsent = new CountDownLatch(1);
        DesktopConsentPort consent = (owner, target, purpose) -> {
            consentEntered.countDown();
            try { return releaseConsent.await(3, TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        };
        try (var service = new DefaultDesktopSessionService(List.of(provider), consent)) {
            var opening = service.open(OWNER, TARGET_A.id(), false).toCompletableFuture();
            assertTrue(consentEntered.await(3, TimeUnit.SECONDS));
            service.closeScope(OWNER.workspaceId(), OWNER.scopeId());
            releaseConsent.countDown();
            assertTrue(assertThrows(CompletionException.class, opening::join)
                    .getCause() instanceof IllegalStateException);
            assertTrue(provider.opened.stream().allMatch(session -> session.closed));
        } finally {
            releaseConsent.countDown();
        }
    }

    @Test
    void simultaneousActionsCannotPassTheUnknownResultGate() throws Exception {
        FakeProvider provider = new FakeProvider("test", true, List.of(TARGET_A));
        try (var service = new DefaultDesktopSessionService(List.of(provider), allowAll())) {
            String id = service.open(OWNER, TARGET_A.id(), true).toCompletableFuture().join().sessionId();
            FakeSession platform = provider.opened.getFirst();
            platform.emit(frame(TARGET_A, 1));
            await(() -> service.snapshot(OWNER, id).toCompletableFuture().join().isPresent());
            DesktopObservation observation = observeCommitted(service, id);
            platform.nextResult = new DesktopActionResult(DesktopActionResult.Status.UNKNOWN,
                    "queued without confirmation", 1);
            platform.actionEntered = new CountDownLatch(1);
            platform.actionRelease = new CountDownLatch(1);
            var first = service.perform(OWNER, id, click(1, observation.observationId()))
                    .toCompletableFuture();
            assertTrue(platform.actionEntered.await(3, TimeUnit.SECONDS));
            var second = service.perform(OWNER, id, click(1, observation.observationId()))
                    .toCompletableFuture();
            try {
                assertFalse(second.isDone(), "a second action must wait for the first result");
            } finally {
                platform.actionRelease.countDown();
            }
            assertEquals(DesktopActionResult.Status.UNKNOWN, first.join().status());
            assertEquals(DesktopActionResult.Status.DENIED, second.join().status());
            assertEquals(1, platform.actionCount.get());
        }
    }

    @Test
    void lostToolResultFencesTheTargetEvenWhenNativeActionLaterReportsSuccess() throws Exception {
        FakeProvider provider = new FakeProvider("test", true, List.of(TARGET_A));
        DesktopSessionOwner other = new DesktopSessionOwner(
                "workspace-a", "run-2", "agent", "message-2");
        try (var service = new DefaultDesktopSessionService(List.of(provider), allowAll())) {
            String firstId = service.open(OWNER, TARGET_A.id(), true, DesktopInputPolicy.SYSTEM_EXPLICIT)
                    .toCompletableFuture().join().sessionId();
            String secondId = service.open(other, TARGET_A.id(), true)
                    .toCompletableFuture().join().sessionId();
            FakeSession firstPlatform = provider.opened.get(0);
            FakeSession secondPlatform = provider.opened.get(1);
            assertTrue(service.authorizeForeground(OWNER, firstId).toCompletableFuture().join());
            service.captureObservation(OWNER, firstId).toCompletableFuture().join();
            firstPlatform.emit(frame(TARGET_A, 1));
            secondPlatform.emit(frame(TARGET_A, 1));
            await(() -> service.snapshot(OWNER, firstId).toCompletableFuture().join().isPresent()
                    && service.snapshot(other, secondId).toCompletableFuture().join().isPresent());
            DesktopObservation firstObserved = observeCommitted(service, OWNER, firstId);
            observeCommitted(service, other, secondId);
            firstPlatform.actionEntered = new CountDownLatch(1);
            firstPlatform.actionRelease = new CountDownLatch(1);
            DesktopSessionTools tools = new DesktopSessionTools(service, OWNER, Path.of("unused"));
            AtomicReference<String> lostResult = new AtomicReference<>();
            Thread caller = Thread.ofVirtual().start(() -> lostResult.set(tools.click(
                    firstId, firstObserved.observationId(), 1, "", 0, 0, 1, 1)));
            assertTrue(firstPlatform.actionEntered.await(3, TimeUnit.SECONDS));
            Thread closer = Thread.ofVirtual().start(() -> service.closeSession(OWNER, firstId));
            await(() -> {
                try { service.info(OWNER, firstId); return false; }
                catch (SecurityException closed) { return true; }
            });
            caller.interrupt();
            caller.join(TimeUnit.SECONDS.toMillis(3));
            assertFalse(caller.isAlive());
            assertTrue(lostResult.get().contains("禁止直接重试"));
            firstPlatform.actionRelease.countDown();
            closer.join(TimeUnit.SECONDS.toMillis(3));
            assertFalse(closer.isAlive());
            assertEquals(DesktopSessionState.Kind.PAUSED, service.state(other, secondId).kind());
            secondPlatform.emit(new DesktopFrame(TARGET_A.id(), 1,
                    System.currentTimeMillis() + 250, 1, 1, 4,
                    new byte[] { 0, 0, 0, (byte) 255 }, 2));
            await(() -> service.snapshot(other, secondId).toCompletableFuture().join()
                    .map(frame -> frame.contentRevision() == 2).orElse(false));
            DesktopObservation secondObserved = observeCommitted(service, other, secondId);
            assertEquals(DesktopActionResult.Status.STALE_FRAME,
                    service.perform(other, secondId,
                            click(1, firstObserved.observationId()))
                            .toCompletableFuture().join().status());
            assertEquals(DesktopActionResult.Status.VERIFIED,
                    service.perform(other, secondId,
                            click(1, secondObserved.observationId()))
                            .toCompletableFuture().join().status());
            assertEquals(1, firstPlatform.actionCount.get());
            assertEquals(1, secondPlatform.actionCount.get(),
                    "the next Run may act from a fresh baseline after native input has settled");
        } finally {
            for (FakeSession session : provider.opened)
                if (session.actionRelease != null) session.actionRelease.countDown();
        }
    }

    @Test
    void failureAfterAnAttemptedDispatchIsTreatedAsUnknown() {
        FakeProvider provider = new FakeProvider("test", true, List.of(TARGET_A));
        try (var service = new DefaultDesktopSessionService(List.of(provider), allowAll())) {
            String id = service.open(OWNER, TARGET_A.id(), true)
                    .toCompletableFuture().join().sessionId();
            FakeSession platform = provider.opened.getFirst();
            platform.emit(frame(TARGET_A, 1));
            await(() -> service.snapshot(OWNER, id).toCompletableFuture().join().isPresent());
            DesktopObservation observed = observeCommitted(service, id);
            platform.nextResult = new DesktopActionResult(DesktopActionResult.Status.FAILED,
                    "post-dispatch error", 1, DesktopActionResult.Mode.BACKGROUND_SEMANTIC,
                    DesktopActionResult.Reason.PLATFORM_FAILURE, true,
                    observed.observationId(), DesktopActionResult.NextStep.NONE);
            DesktopActionResult result = service.perform(OWNER, id,
                    click(1, observed.observationId())).toCompletableFuture().join();
            assertEquals(DesktopActionResult.Status.UNKNOWN, result.status());
            assertTrue(result.dispatchAttempted());
            assertEquals(DesktopActionResult.Status.DENIED,
                    service.perform(OWNER, id, click(1, observed.observationId()))
                            .toCompletableFuture().join().status());
            assertEquals(1, platform.actionCount.get());
        }
    }

    @Test
    void reopenedSessionNeedsFreshBaselineBeforeNewInput() {
        FakeProvider provider = new FakeProvider("test", true, List.of(TARGET_A));
        try (var service = new DefaultDesktopSessionService(List.of(provider), allowAll())) {
            String firstId = service.open(OWNER, TARGET_A.id(), true)
                    .toCompletableFuture().join().sessionId();
            FakeSession firstPlatform = provider.opened.getFirst();
            firstPlatform.emit(frame(TARGET_A, 1));
            await(() -> service.snapshot(OWNER, firstId).toCompletableFuture().join().isPresent());
            DesktopObservation before = observeCommitted(service, firstId);
            firstPlatform.nextResult = new DesktopActionResult(
                    DesktopActionResult.Status.UNKNOWN, "maybe sent", 1);
            assertEquals(DesktopActionResult.Status.UNKNOWN,
                    service.perform(OWNER, firstId, click(1, before.observationId()))
                            .toCompletableFuture().join().status());
            service.closeSession(OWNER, firstId);

            String reopenedId = service.open(OWNER, TARGET_A.id(), true)
                    .toCompletableFuture().join().sessionId();
            assertNotEquals(firstId, reopenedId);
            FakeSession reopened = provider.opened.getLast();
            assertEquals(DesktopActionResult.Status.DENIED,
                    service.perform(OWNER, reopenedId,
                            click(1, before.observationId())).toCompletableFuture().join().status());
            assertEquals(0, reopened.actionCount.get(), "reopening alone cannot clear uncertain input");
            reopened.emit(new DesktopFrame(TARGET_A.id(), 1,
                    System.currentTimeMillis() + 250, 1, 1, 4,
                    new byte[] { 0, 0, 0, (byte) 255 }));
            await(() -> service.snapshot(OWNER, reopenedId)
                    .toCompletableFuture().join().isPresent());
            DesktopObservation after = observeCommitted(service, reopenedId);
            DesktopActionResult next = service.perform(OWNER, reopenedId,
                    click(1, after.observationId())).toCompletableFuture().join();
            assertEquals(DesktopActionResult.Status.VERIFIED, next.status());
            assertTrue(next.dispatchAttempted());
            assertEquals(1, reopened.actionCount.get());
            assertTrue(service.reconcilePendingAction(OWNER, reopenedId,
                    before.observationId(), after.observationId())
                    .toCompletableFuture().join());
        }
    }

    @Test
    void anotherWorkspaceCannotRefreshAnUnknownInputFence() {
        FakeProvider provider = new FakeProvider("test", true, List.of(TARGET_A));
        DesktopSessionOwner foreign = new DesktopSessionOwner(
                "workspace-b", "run-2", "agent", "message-2");
        try (var service = new DefaultDesktopSessionService(List.of(provider), allowAll())) {
            String ownId = service.open(OWNER, TARGET_A.id(), true).toCompletableFuture().join().sessionId();
            String foreignId = service.open(foreign, TARGET_A.id(), true)
                    .toCompletableFuture().join().sessionId();
            FakeSession own = provider.opened.get(0);
            FakeSession other = provider.opened.get(1);
            own.emit(frame(TARGET_A, 1));
            await(() -> service.snapshot(OWNER, ownId).toCompletableFuture().join().isPresent());
            DesktopObservation before = observeCommitted(service, ownId);
            own.nextResult = new DesktopActionResult(DesktopActionResult.Status.UNKNOWN, "maybe sent", 1);
            assertEquals(DesktopActionResult.Status.UNKNOWN,
                    service.perform(OWNER, ownId, click(1, before.observationId()))
                            .toCompletableFuture().join().status());
            other.emit(new DesktopFrame(TARGET_A.id(), 1, System.currentTimeMillis() + 250,
                    1, 1, 4, new byte[] { 0, 0, 0, (byte) 255 }));
            await(() -> service.snapshot(foreign, foreignId).toCompletableFuture().join().isPresent());
            DesktopObservation foreignFrame = observeCommitted(service, foreign, foreignId);
            DesktopActionResult denied = service.perform(foreign, foreignId,
                    click(1, foreignFrame.observationId())).toCompletableFuture().join();
            assertEquals(DesktopActionResult.Status.DENIED, denied.status());
            assertEquals(DesktopActionResult.Reason.DELIVERY_UNCERTAIN, denied.reason());
            assertEquals(0, other.actionCount.get());
            assertFalse(service.reconcilePendingAction(foreign, foreignId,
                    before.observationId(), foreignFrame.observationId()).toCompletableFuture().join());
        }
    }

    @Test
    void uncertainInputNeedsAnInterpretedPostSettleFrame() {
        FakeProvider provider = new FakeProvider("test", true, List.of(TARGET_A));
        long captureBaseMillis = System.currentTimeMillis();
        Clock captureClock = Clock.fixed(java.time.Instant.ofEpochMilli(captureBaseMillis),
                java.time.ZoneOffset.UTC);
        try (var service = new DefaultDesktopSessionService(List.of(provider), allowAll(), captureClock)) {
            String id = service.open(OWNER, TARGET_A.id(), true).toCompletableFuture().join().sessionId();
            FakeSession platform = provider.opened.getFirst();
            platform.emit(new DesktopFrame(TARGET_A.id(), 1, captureBaseMillis,
                    1, 1, 4, new byte[] { 0, 0, 0, (byte) 255 }));
            await(() -> service.snapshot(OWNER, id).toCompletableFuture().join().isPresent());
            DesktopObservation before = observeCommitted(service, id);
            platform.nextResult = new DesktopActionResult(DesktopActionResult.Status.UNKNOWN, "maybe sent", 1);
            DesktopActionResult unknown = service.perform(OWNER, id, click(1, before.observationId()))
                    .toCompletableFuture().join();
            platform.emit(new DesktopFrame(TARGET_A.id(), 1, captureBaseMillis + 50,
                    1, 1, 4, new byte[] { 0, 0, 0, (byte) 255 }, 2));
            await(() -> service.snapshot(OWNER, id).toCompletableFuture().join()
                    .map(value -> value.contentRevision() == 2).orElse(false));
            assertTrue(service.captureObservation(OWNER, id).toCompletableFuture().join().isEmpty(),
                    "a frame captured within the input settle interval cannot establish a baseline");
            platform.emit(new DesktopFrame(TARGET_A.id(), 1, captureBaseMillis + 150,
                    1, 1, 4, new byte[] { 0, 0, 0, (byte) 255 }, 3));
            await(() -> service.snapshot(OWNER, id).toCompletableFuture().join()
                    .map(value -> value.contentRevision() == 3).orElse(false));
            assertTrue(service.captureObservation(OWNER, id).toCompletableFuture().join().isEmpty(),
                    "a frame at the settle boundary is not strictly after input settlement");
            platform.emit(new DesktopFrame(TARGET_A.id(), 1, captureBaseMillis + 250,
                    1, 1, 4, new byte[] { 0, 0, 0, (byte) 255 }, 4));
            await(() -> service.snapshot(OWNER, id).toCompletableFuture().join()
                    .map(value -> value.contentRevision() == 4).orElse(false));
            DesktopObservation after = service.captureObservation(OWNER, id)
                    .toCompletableFuture().join().orElseThrow();
            assertFalse(service.commitObservation(OWNER, id, after.observationId())
                    .toCompletableFuture().join(), "legacy un-interpreted commit cannot refresh input");
            assertEquals(DesktopActionResult.Status.DENIED,
                    service.perform(OWNER, id, click(1, after.observationId()))
                            .toCompletableFuture().join().status());
            assertTrue(service.commitObservation(OWNER, id, after.observationId(), List.of())
                    .toCompletableFuture().join());
            assertEquals(DesktopActionResult.Status.UNKNOWN, unknown.status(),
                    "refreshing input must leave the old business result unchanged");
            platform.nextResult = new DesktopActionResult(DesktopActionResult.Status.ACCEPTED, "input accepted", 1);
            assertEquals(DesktopActionResult.Status.ACCEPTED,
                    service.perform(OWNER, id, click(1, after.observationId()))
                            .toCompletableFuture().join().status());
            assertEquals(2, platform.actionCount.get());
        }
    }

    @Test
    void sentInputObservationUsesNativeDispatchSettleBoundaryWhenCallerBoundaryIsEarlier() {
        FakeProvider provider = new FakeProvider("test", true, List.of(TARGET_A));
        long captureBaseMillis = System.currentTimeMillis();
        Clock captureClock = Clock.fixed(java.time.Instant.ofEpochMilli(captureBaseMillis),
                java.time.ZoneOffset.UTC);
        try (var service = new DefaultDesktopSessionService(List.of(provider), allowAll(), captureClock)) {
            String id = service.open(OWNER, TARGET_A.id(), true).toCompletableFuture().join().sessionId();
            FakeSession platform = provider.opened.getFirst();
            platform.emit(new DesktopFrame(TARGET_A.id(), 1, captureBaseMillis,
                    1, 1, 4, new byte[] { 0, 0, 0, (byte) 255 }));
            await(() -> service.snapshot(OWNER, id).toCompletableFuture().join().isPresent());
            DesktopObservation before = observeCommitted(service, id);
            assertEquals(DesktopActionResult.Delivery.SENT,
                    service.perform(OWNER, id, click(1, before.observationId()))
                            .toCompletableFuture().join().delivery());

            long earlierCallerBoundary = captureBaseMillis - 1_000;
            platform.emit(new DesktopFrame(TARGET_A.id(), 1, captureBaseMillis + 150,
                    1, 1, 4, new byte[] { 0, 0, 0, (byte) 255 }, 2));
            await(() -> service.snapshot(OWNER, id).toCompletableFuture().join()
                    .map(value -> value.contentRevision() == 2).orElse(false));
            assertTrue(service.captureObservation(OWNER, id, earlierCallerBoundary)
                            .toCompletableFuture().join().isEmpty(),
                    "an early receipt must not shorten the native dispatch settle interval");

            platform.emit(new DesktopFrame(TARGET_A.id(), 1, captureBaseMillis + 151,
                    1, 1, 4, new byte[] { 0, 0, 0, (byte) 255 }, 3));
            await(() -> service.snapshot(OWNER, id).toCompletableFuture().join()
                    .map(value -> value.contentRevision() == 3).orElse(false));
            assertEquals(captureBaseMillis + 151, service.captureObservation(OWNER, id,
                    earlierCallerBoundary).toCompletableFuture().join().orElseThrow()
                    .frame().capturedAtMillis());
            assertEquals(1, platform.actionCount.get());
        }
    }

    @Test
    void observationCannotRefreshInputWhileNativeAttemptIsStillExecuting() throws Exception {
        FakeProvider provider = new FakeProvider("test", true, List.of(TARGET_A));
        try (var service = new DefaultDesktopSessionService(List.of(provider), allowAll())) {
            String id = service.open(OWNER, TARGET_A.id(), true).toCompletableFuture().join().sessionId();
            FakeSession platform = provider.opened.getFirst();
            platform.emit(frame(TARGET_A, 1));
            await(() -> service.snapshot(OWNER, id).toCompletableFuture().join().isPresent());
            DesktopObservation before = observeCommitted(service, id);
            platform.nextResult = new DesktopActionResult(DesktopActionResult.Status.UNKNOWN, "native uncertain", 1);
            platform.actionEntered = new CountDownLatch(1);
            platform.actionRelease = new CountDownLatch(1);
            var action = service.perform(OWNER, id, click(1, before.observationId())).toCompletableFuture();
            assertTrue(platform.actionEntered.await(3, TimeUnit.SECONDS));
            service.markDeliveryUncertain(OWNER, id, before.observationId());
            var observation = service.captureObservation(OWNER, id).toCompletableFuture();
            try {
                assertFalse(observation.isDone(), "capture shares the native input coordinator");
            } finally {
                platform.actionRelease.countDown();
            }
            assertEquals(DesktopActionResult.Status.UNKNOWN, action.join().status());
            assertTrue(observation.join().isEmpty(), "the pre-input frame is invalidated at native completion");
            assertEquals(DesktopSessionState.Kind.PAUSED, service.state(OWNER, id).kind());
            assertEquals(1, platform.actionCount.get());
        } finally {
            for (FakeSession session : provider.opened)
                if (session.actionRelease != null) session.actionRelease.countDown();
        }
    }

    @Test
    void inputAcrossOwnersOfTheSameWindowIsSerializedAndInvalidatesOldObservation()
            throws Exception {
        FakeProvider provider = new FakeProvider("test", true, List.of(TARGET_A));
        DesktopSessionOwner other = new DesktopSessionOwner("workspace-a", "run-2", "agent", "other");
        try (var service = new DefaultDesktopSessionService(List.of(provider), allowAll())) {
            String firstId = service.open(OWNER, TARGET_A.id(), true)
                    .toCompletableFuture().join().sessionId();
            String secondId = service.open(other, TARGET_A.id(), true)
                    .toCompletableFuture().join().sessionId();
            FakeSession first = provider.opened.get(0);
            FakeSession second = provider.opened.get(1);
            first.emit(frame(TARGET_A, 1));
            second.emit(frame(TARGET_A, 1));
            await(() -> service.snapshot(OWNER, firstId).toCompletableFuture().join().isPresent());
            await(() -> service.snapshot(other, secondId).toCompletableFuture().join().isPresent());
            DesktopObservation firstObservation = observeCommitted(service, firstId);
            DesktopObservation secondObservation = service.captureObservation(other, secondId)
                    .toCompletableFuture().join().orElseThrow();
            assertTrue(service.commitObservation(other, secondId,
                    secondObservation.observationId(), List.of()).toCompletableFuture().join());
            first.actionEntered = new CountDownLatch(1);
            first.actionRelease = new CountDownLatch(1);
            var firstAction = service.perform(OWNER, firstId,
                    click(1, firstObservation.observationId())).toCompletableFuture();
            assertTrue(first.actionEntered.await(3, TimeUnit.SECONDS));
            var secondAction = service.perform(other, secondId,
                    click(1, secondObservation.observationId())).toCompletableFuture();
            try { assertFalse(secondAction.isDone()); }
            finally { first.actionRelease.countDown(); }
            assertEquals(DesktopActionResult.Status.VERIFIED, firstAction.join().status());
            assertEquals(DesktopActionResult.Status.STALE_FRAME, secondAction.join().status());
            assertEquals(0, second.actionCount.get());
        }
    }

    @Test
    void actionEventsAreOwnerScopedOrderedAndNeverContainTypedContent() {
        FakeProvider provider = new FakeProvider("test", true, List.of(TARGET_A));
        try (var service = new DefaultDesktopSessionService(List.of(provider), allowAll())) {
            String id = service.open(OWNER, TARGET_A.id(), true).toCompletableFuture().join().sessionId();
            RecordingSubscriber<DesktopActionEvent> events = new RecordingSubscriber<>();
            service.actions(OWNER, id).subscribe(events);
            events.request(Long.MAX_VALUE);
            RecordingSubscriber<DesktopVirtualInputState> feedback = new RecordingSubscriber<>();
            service.virtualInputs(OWNER, id).subscribe(feedback);
            feedback.request(Long.MAX_VALUE);
            DesktopSessionOwner differentOrigin = new DesktopSessionOwner(
                    OWNER.workspaceId(), OWNER.scopeId(), OWNER.sourceKind(), "other-message");
            assertThrows(SecurityException.class, () -> service.actions(differentOrigin, id));

            FakeSession platform = provider.opened.getFirst();
            platform.emit(frame(TARGET_A, 1));
            await(() -> service.snapshot(OWNER, id).toCompletableFuture().join().isPresent());
            DesktopObservation initial = observeCommitted(service, id);
            String secret = "never-log-this-password";
            DesktopAction type = new DesktopAction(DesktopAction.Kind.TYPE,
                    0, 0, 1, 1, 0, secret, 1, initial.observationId(), initial.observationId() + ":e1", 0);
            assertEquals(DesktopActionResult.Status.VERIFIED,
                    service.perform(OWNER, id, type).toCompletableFuture().join().status());
            await(() -> events.items.size() >= 2);
            assertEquals(DesktopActionEvent.Phase.STARTED, events.items.get(0).phase());
            assertEquals(DesktopActionEvent.Phase.FINISHED, events.items.get(1).phase());
            assertEquals(DesktopActionResult.Status.VERIFIED, events.items.get(1).result());
            assertEquals(DesktopAction.Kind.TYPE, events.items.get(1).kind());
            assertEquals(1, events.items.get(1).windowGeneration());
            assertTrue(events.items.get(1).atMillis() >= events.items.get(0).atMillis());
            assertTrue(events.items.get(0).actionId() > 0);
            assertEquals(events.items.get(0).actionId(), events.items.get(1).actionId());
            assertEquals(DesktopActionResult.Delivery.SENT, events.items.get(1).delivery());
            await(() -> feedback.items.stream().anyMatch(value -> value.actionId() == events.items.get(1).actionId()
                    && value.phase() == DesktopVirtualInputState.Phase.FINISHED));
            assertTrue(feedback.items.stream().noneMatch(value -> value.phase() == DesktopVirtualInputState.Phase.PRESSED));
            assertTrue(events.items.stream().noneMatch(event -> event.toString().contains(secret)));

            platform.emit(new DesktopFrame(TARGET_A.id(), 1,
                    System.currentTimeMillis() + 10, 1, 1, 4,
                    new byte[] { 0, 0, 0, (byte) 255 }, 2));
            await(() -> service.snapshot(OWNER, id).toCompletableFuture().join()
                    .map(value -> value.contentRevision() == 2).orElse(false));
            DesktopObservation renewed = observeCommitted(service, id);
            platform.nextFailure = new IllegalStateException("internal native error");
            DesktopAction renewedType = new DesktopAction(DesktopAction.Kind.TYPE,
                    0, 0, 1, 1, 0, secret, 1, renewed.observationId(), renewed.observationId() + ":e1", 0);
            assertEquals(DesktopActionResult.Status.UNKNOWN,
                    service.perform(OWNER, id, renewedType).toCompletableFuture().join().status());
            await(() -> events.items.size() >= 4);
            assertEquals(DesktopActionEvent.Phase.STARTED, events.items.get(2).phase());
            assertEquals(DesktopActionResult.Status.UNKNOWN, events.items.get(3).result());
            assertTrue(events.items.get(2).actionId() > events.items.get(0).actionId());
            assertEquals(DesktopActionResult.Delivery.MAYBE_SENT, events.items.get(3).delivery());
            assertTrue(events.items.stream().noneMatch(event -> event.toString().contains(secret)));
            service.closeSession(OWNER, id);
            await(() -> events.completed.get() == 1);

            String observeOnly = service.open(OWNER, TARGET_A.id(), false)
                    .toCompletableFuture().join().sessionId();
            RecordingSubscriber<DesktopActionEvent> deniedEvents = new RecordingSubscriber<>();
            service.actions(OWNER, observeOnly).subscribe(deniedEvents);
            deniedEvents.request(Long.MAX_VALUE);
            assertEquals(DesktopActionResult.Status.DENIED,
                    service.perform(OWNER, observeOnly, click(1)).toCompletableFuture().join().status());
            await(() -> deniedEvents.items.size() >= 2);
            assertEquals(DesktopActionEvent.Phase.STARTED, deniedEvents.items.get(0).phase());
            assertEquals(DesktopActionResult.Status.DENIED, deniedEvents.items.get(1).result());
            assertEquals(DesktopActionResult.Delivery.NOT_SENT, deniedEvents.items.get(1).delivery());
        }
    }

    @Test
    void initialMinimizedWindowPublishesItsPauseReason() {
        DesktopTarget minimized = new DesktopTarget("test", "minimized", 101, "Fake App",
                "Window", 0, 0, 1, 1, DesktopTarget.MINIMIZED);
        FakeProvider provider = new FakeProvider("test", true, List.of(minimized));
        RecordingSubscriber<DesktopSessionState> states = new RecordingSubscriber<>();
        DesktopSessionObserver observer = new DesktopSessionObserver() {
            @Override public void opened(DesktopSessionOwner owner, DesktopSessionInfo info,
                                         com.javaclaw.desktop.api.DesktopSessionService service) {
                service.states(owner, info.sessionId()).subscribe(states);
                states.request(Long.MAX_VALUE);
            }
            @Override public void closed(String sessionId) {}
        };
        try (var service = new DefaultDesktopSessionService(List.of(provider), allowAll(),
                Clock.systemUTC(), observer)) {
            service.open(OWNER, minimized.id(), false).toCompletableFuture().join();
            await(() -> states.items.stream().anyMatch(state ->
                    state.kind() == DesktopSessionState.Kind.PAUSED
                            && state.detail().contains("最小化")));
        }
    }

    @Test
    void currentStateExplainsMissingFramesWithoutReturningStalePixels() {
        FakeProvider provider = new FakeProvider("test", true, List.of(TARGET_A));
        try (var service = new DefaultDesktopSessionService(List.of(provider), allowAll())) {
            String id = service.open(OWNER, TARGET_A.id(), true, DesktopInputPolicy.SYSTEM_EXPLICIT)
                    .toCompletableFuture().join().sessionId();
            assertEquals(DesktopSessionState.Kind.PAUSED, service.state(OWNER, id).kind());
            assertTrue(service.state(OWNER, id).detail().contains("首帧"));
            assertTrue(service.snapshot(OWNER, id).toCompletableFuture().join().isEmpty());
            assertTrue(service.authorizeForeground(OWNER, id).toCompletableFuture().join());
            assertEquals(DesktopSessionState.Kind.FOREGROUND_READY, service.state(OWNER, id).kind(),
                    "前台模式就绪但尚未取得可用于输入的新观察");
            assertTrue(service.snapshot(OWNER, id).toCompletableFuture().join().isEmpty());
            assertTrue(service.captureObservation(OWNER, id).toCompletableFuture().join().isEmpty(),
                    "the initial observation request prepares the foreground lease");

            FakeSession platform = provider.opened.getFirst();
            platform.emit(frame(TARGET_A, 1));
            await(() -> service.snapshot(OWNER, id).toCompletableFuture().join().isPresent());
            observeCommitted(service, id);
            await(() -> service.state(OWNER, id).kind() == DesktopSessionState.Kind.LIVE);

            await(() -> service.snapshot(OWNER, id).toCompletableFuture().join().isEmpty());
            assertEquals(DesktopSessionState.Kind.PAUSED, service.state(OWNER, id).kind());
            assertTrue(service.state(OWNER, id).detail().contains("失帧"));

            platform.target = new DesktopTarget("test", TARGET_A.id(), 101,
                    "Fake App", "Window", 0, 0, 1, 1, DesktopTarget.MINIMIZED);
            await(() -> service.state(OWNER, id).detail().contains("最小化"));
            assertTrue(service.snapshot(OWNER, id).toCompletableFuture().join().isEmpty());
            assertEquals(DesktopSessionState.Kind.PAUSED, service.state(OWNER, id).kind());

            DesktopSessionOwner other = new DesktopSessionOwner("other", "run-1", "agent", "message-1");
            assertThrows(SecurityException.class, () -> service.state(other, id));
        }
    }

    @Test
    void targetListSeparatesOwningApplicationFromAStatusItemsTitle() {
        DesktopTarget statusItem = new DesktopTarget("test", "status-item", 100,
                "控制中心", "org.example.reader", 0, 0, 20, 20,
                DesktopTarget.VISIBLE | DesktopTarget.SYSTEM_SURFACE);
        DesktopTarget readerWindow = new DesktopTarget("test", "reader-window", 101,
                "阅读器", "最近文件", 0, 0, 800, 600, DesktopTarget.VISIBLE);
        FakeProvider provider = new FakeProvider("test", true, List.of(statusItem, readerWindow));
        try (var service = new DefaultDesktopSessionService(List.of(provider), allowAll())) {
            DesktopSessionTools tools = new DesktopSessionTools(service, OWNER, Path.of("unused"));
            try (var capture = com.javaclaw.framework.spi.ToolEffectCapture.begin("desktop_session_targets")) {
                String listed = tools.targets();
                var rows = capture.data().path("targets");
                assertEquals("status-item", rows.get(0).path("targetId").asText());
                assertEquals("控制中心", rows.get(0).path("application").asText());
                assertEquals("org.example.reader", rows.get(0).path("title").asText());
                assertTrue(rows.get(0).path("systemSurface").asBoolean());
                assertEquals("reader-window", rows.get(1).path("targetId").asText());
                assertEquals("阅读器", rows.get(1).path("application").asText());
                assertEquals("最近文件", rows.get(1).path("title").asText());
                assertTrue(listed.contains("不要仅凭标题中的应用名或 bundle ID"), listed);
                assertFalse(listed.contains("status-item"), "display text does not duplicate the discovery rows");
            }
        }
    }

    @Test
    void latestPublisherRetainsOnlyNewestValueUntilDemandAndCompletesOnClose() {
        LatestPublisher<Integer> publisher = new LatestPublisher<>(Runnable::run);
        RecordingSubscriber<Integer> subscriber = new RecordingSubscriber<>();
        publisher.subscribe(subscriber);
        for (int value = 0; value < 100; value++) publisher.submit(value);
        assertTrue(subscriber.items.isEmpty());
        subscriber.request(1);
        assertEquals(List.of(99), subscriber.items);
        publisher.submit(100);
        publisher.submit(101);
        subscriber.request(1);
        assertEquals(List.of(99, 101), subscriber.items);
        publisher.close();
        assertEquals(1, subscriber.completed.get());
    }

    @Test
    void systemInputRejectsSetTextBeforeAnyDispatch() {
        FakeProvider provider = new FakeProvider("test", true, List.of(TARGET_A));
        try (var service = new DefaultDesktopSessionService(List.of(provider), allowAll())) {
            String id = service.open(OWNER, TARGET_A.id(), true, DesktopInputPolicy.SYSTEM_EXPLICIT)
                    .toCompletableFuture().join().sessionId();
            var action = new DesktopAction(DesktopAction.Kind.TYPE, 0, 0, 0, 0, 0, "replacement", 1,
                    "observation", "observation:e1", 0, DesktopAction.TextOperation.SET_TEXT);
            DesktopActionResult result = service.perform(OWNER, id, action).toCompletableFuture().join();
            assertEquals(DesktopActionResult.Status.UNSUPPORTED, result.status());
            assertFalse(result.dispatchAttempted());
            assertEquals(0, provider.opened.getFirst().actionCount.get());
            assertEquals(0, provider.opened.getFirst().prepareCount.get());
        }
    }

    @Test
    void strictRejectsUnadvertisedActionsAndActiveTargetBeforeDispatch() {
        FakeProvider provider = new FakeProvider("test", true, List.of(TARGET_A));
        try (var service = new DefaultDesktopSessionService(List.of(provider), allowAll())) {
            String id = service.open(OWNER, TARGET_A.id(), true).toCompletableFuture().join().sessionId();
            FakeSession platform = provider.opened.getFirst();
            platform.emit(frame(TARGET_A, 1));
            await(() -> service.snapshot(OWNER, id).toCompletableFuture().join().isPresent());
            DesktopObservation observed = observeCommitted(service, id);
            DesktopAction raw = new DesktopAction(DesktopAction.Kind.CLICK, 0, 0, 1, 1, 0, "", 1,
                    observed.observationId(), "", 0);
            DesktopActionResult unsupported = service.perform(OWNER, id, raw).toCompletableFuture().join();
            assertEquals(DesktopActionResult.Status.UNSUPPORTED, unsupported.status());
            assertFalse(unsupported.dispatchAttempted());
            platform.targetActive = true;
            DesktopActionResult active = service.perform(OWNER, id,
                    click(1, observed.observationId())).toCompletableFuture().join();
            assertEquals(DesktopActionResult.Reason.TARGET_ACTIVE, active.reason());
            assertFalse(active.dispatchAttempted());
            assertEquals(0, platform.actionCount.get());
        }
    }

    @Test
    void inputPolicyCannotChangeWhenReusingSession() {
        FakeProvider provider = new FakeProvider("test", true, List.of(TARGET_A));
        try (var service = new DefaultDesktopSessionService(List.of(provider), allowAll())) {
            var strict = service.open(OWNER, TARGET_A.id(), true).toCompletableFuture().join();
            assertThrows(CompletionException.class, () -> service.open(OWNER, TARGET_A.id(), true,
                    DesktopInputPolicy.SYSTEM_EXPLICIT).toCompletableFuture().join());
            assertEquals(DesktopInputPolicy.BACKGROUND_STRICT, service.info(OWNER, strict.sessionId()).inputPolicy());
            assertEquals(1, provider.opened.size());
        }
    }

    @Test
    void manualLeaseWaitsForDispatchThenFencesAllAutomaticInputAndOldObservations() throws Exception {
        FakeProvider provider = new FakeProvider("test", true, List.of(TARGET_A));
        DesktopSessionOwner other = new DesktopSessionOwner("workspace-a", "run-2", "agent", "manual");
        try (var service = new DefaultDesktopSessionService(List.of(provider), allowAll())) {
            String automatic = service.open(OWNER, TARGET_A.id(), true).toCompletableFuture().join().sessionId();
            String manual = service.open(other, TARGET_A.id(), true).toCompletableFuture().join().sessionId();
            FakeSession first = provider.opened.get(0);
            FakeSession second = provider.opened.get(1);
            first.emit(frame(TARGET_A, 1));
            second.emit(frame(TARGET_A, 1));
            await(() -> service.snapshot(OWNER, automatic).toCompletableFuture().join().isPresent()
                    && service.snapshot(other, manual).toCompletableFuture().join().isPresent());
            DesktopObservation initial = observeCommitted(service, automatic);
            first.actionEntered = new CountDownLatch(1);
            first.actionRelease = new CountDownLatch(1);
            var action = service.perform(OWNER, automatic, click(1, initial.observationId())).toCompletableFuture();
            assertTrue(first.actionEntered.await(3, TimeUnit.SECONDS));
            var acquiring = service.acquireManualControl(other, manual).toCompletableFuture();
            try { assertFalse(acquiring.isDone(), "lease waits until already-dispatched input returns"); }
            finally { first.actionRelease.countDown(); }
            assertEquals(DesktopActionResult.Status.VERIFIED, action.join().status());
            String lease = acquiring.join();
            assertEquals(DesktopActionResult.Reason.POLICY_BLOCKED,
                    service.perform(OWNER, automatic, click(1, initial.observationId()))
                            .toCompletableFuture().join().reason());
            second.emit(new DesktopFrame(TARGET_A.id(), 1, System.currentTimeMillis() + 250,
                    1, 1, 4, new byte[] { 0, 0, 1, (byte) 255 }, 2));
            await(() -> service.snapshot(other, manual).toCompletableFuture().join()
                    .map(frame -> frame.contentRevision() == 2).orElse(false));
            DesktopObservation observed = observeCommitted(service, other, manual);
            assertThrows(CompletionException.class, () -> service.performManual(other, manual,
                    "wrong-lease", click(1, observed.observationId())).toCompletableFuture().join());
            assertEquals(DesktopActionResult.Status.VERIFIED, service.performManual(other, manual,
                    lease, click(1, observed.observationId())).toCompletableFuture().join().status());
            service.releaseManualControl(other, manual, lease).toCompletableFuture().join();
            assertEquals(1, second.actionCount.get());
            assertThrows(CompletionException.class, () -> service.performManual(other, manual,
                    lease, click(1, observed.observationId())).toCompletableFuture().join());
        }
    }

    @Test
    void manualPermissionRefusalsPublishNewNotSentFeedbackWithoutDispatch() {
        FakeProvider provider = new FakeProvider("test", true, List.of(TARGET_A));
        AtomicBoolean controlAllowed = new AtomicBoolean(true);
        DesktopConsentPort policy = new DesktopConsentPort() {
            @Override public boolean request(DesktopSessionOwner owner, DesktopTarget target,
                                             Purpose purpose) { return true; }
            @Override public DesktopAvailability accessStatus(Purpose purpose) {
                boolean available = purpose != Purpose.CONTROL || controlAllowed.get();
                return new DesktopAvailability(available, "test", 0,
                        available ? "ready" : "control permission revoked");
            }
        };
        try (var service = new DefaultDesktopSessionService(List.of(provider), policy)) {
            String id = service.open(OWNER, TARGET_A.id(), true).toCompletableFuture().join().sessionId();
            String lease = service.acquireManualControl(OWNER, id).toCompletableFuture().join();
            RecordingSubscriber<DesktopActionEvent> events = new RecordingSubscriber<>();
            service.actions(OWNER, id).subscribe(events);
            events.request(Long.MAX_VALUE);
            RecordingSubscriber<DesktopVirtualInputState> feedback = new RecordingSubscriber<>();
            service.virtualInputs(OWNER, id).subscribe(feedback);
            feedback.request(Long.MAX_VALUE);
            FakeSession platform = provider.opened.getFirst();
            platform.emit(frame(TARGET_A, 1));
            await(() -> service.snapshot(OWNER, id).toCompletableFuture().join().isPresent());
            DesktopObservation observed = observeCommitted(service, id);
            DesktopAction action = click(1, observed.observationId());
            assertEquals(DesktopActionResult.Delivery.SENT,
                    service.performManual(OWNER, id, lease, action).toCompletableFuture().join().delivery());
            await(() -> events.items.size() == 2);
            long previousActionId = events.items.get(1).actionId();
            assertTrue(previousActionId > 0);
            await(() -> feedback.items.stream().anyMatch(value -> value.phase()
                    == DesktopVirtualInputState.Phase.FINISHED
                    && value.delivery() == DesktopActionResult.Delivery.SENT));

            controlAllowed.set(false);
            for (int attempt = 1; attempt <= 2; attempt++) {
                assertTrue(assertThrows(SecurityException.class,
                        () -> service.performManual(OWNER, id, lease, action))
                        .getMessage().contains("control permission revoked"));
                int eventCount = 2 + attempt * 2;
                await(() -> events.items.size() == eventCount);
                DesktopActionEvent started = events.items.get(eventCount - 2);
                DesktopActionEvent finished = events.items.get(eventCount - 1);
                assertEquals(DesktopActionEvent.Phase.STARTED, started.phase());
                assertEquals(DesktopActionEvent.Phase.FINISHED, finished.phase());
                assertTrue(started.actionId() > previousActionId,
                        "every rejected attempt must replace feedback for the prior action");
                assertEquals(started.actionId(), finished.actionId());
                assertEquals(DesktopActionResult.Status.DENIED, finished.result());
                assertEquals(DesktopActionResult.Reason.ACCESS_DENIED, finished.reason());
                assertEquals(DesktopActionResult.Delivery.NOT_SENT, finished.delivery());
                await(() -> feedback.items.stream().anyMatch(value ->
                        value.actionId() == finished.actionId()
                                && value.phase() == DesktopVirtualInputState.Phase.FINISHED
                                && value.delivery() == DesktopActionResult.Delivery.NOT_SENT
                                && !value.visible()));
                assertEquals(1, platform.actionCount.get(), "permission refusal cannot reach native input");
                previousActionId = started.actionId();
            }
        }
    }

    @Test
    void lostManualResultStaysFencedAcrossLeaseReleaseAndSessionReopen() throws Exception {
        FakeProvider provider = new FakeProvider("test", true, List.of(TARGET_A));
        try (var service = new DefaultDesktopSessionService(List.of(provider), allowAll())) {
            String id = service.open(OWNER, TARGET_A.id(), true).toCompletableFuture().join().sessionId();
            FakeSession platform = provider.opened.getFirst();
            platform.emit(frame(TARGET_A, 1));
            await(() -> service.snapshot(OWNER, id).toCompletableFuture().join().isPresent());
            String lease = service.acquireManualControl(OWNER, id).toCompletableFuture().join();
            DesktopObservation observed = observeCommitted(service, id);
            platform.actionEntered = new CountDownLatch(1);
            platform.actionRelease = new CountDownLatch(1);
            var sent = service.performManual(OWNER, id, lease, click(1, observed.observationId()))
                    .toCompletableFuture();
            assertTrue(platform.actionEntered.await(3, TimeUnit.SECONDS));
            service.markDeliveryUncertain(OWNER, id, observed.observationId());
            var releasing = service.releaseManualControl(OWNER, id, lease).toCompletableFuture();
            platform.actionRelease.countDown();
            assertEquals(DesktopActionResult.Status.VERIFIED, sent.join().status());
            service.acknowledgeActionResult(OWNER, id, observed.observationId());
            releasing.join();
            service.closeSession(OWNER, id);
            String reopened = service.open(OWNER, TARGET_A.id(), true)
                    .toCompletableFuture().join().sessionId();
            String nextLease = service.acquireManualControl(OWNER, reopened).toCompletableFuture().join();
            DesktopActionResult denied = service.performManual(OWNER, reopened, nextLease,
                    click(1, observed.observationId())).toCompletableFuture().join();
            assertEquals(DesktopActionResult.Reason.DELIVERY_UNCERTAIN, denied.reason());
            assertFalse(denied.dispatchAttempted());
            assertEquals(0, provider.opened.getLast().actionCount.get());
            service.releaseManualControl(OWNER, reopened, nextLease).toCompletableFuture().join();
        }
    }

    private static DesktopConsentPort allowAll() { return (owner, target, purpose) -> true; }

    private static DesktopTarget target(String id) {
        return new DesktopTarget("test", id, 101, "Fake App", "Window", 0, 0, 1, 1,
                DesktopTarget.VISIBLE);
    }

    private static DesktopFrame frame(DesktopTarget target, long generation) {
        return new DesktopFrame(target.id(), generation, System.currentTimeMillis(),
                1, 1, 4, new byte[] { 0, 0, 0, (byte) 255 });
    }

    private static DesktopAction click(long generation) {
        return new DesktopAction(DesktopAction.Kind.CLICK, 0, 0, 1, 1, 0, "", generation);
    }

    private static DesktopAction click(long generation, String observationId) {
        return new DesktopAction(DesktopAction.Kind.CLICK, 0, 0, 1, 1, 0, "", generation,
                observationId, observationId + ":e1", 0);
    }

    private static DesktopObservation observeCommitted(DefaultDesktopSessionService service, String id) {
        return observeCommitted(service, OWNER, id);
    }

    private static DesktopObservation observeCommitted(DefaultDesktopSessionService service,
                                                       DesktopSessionOwner owner, String id) {
        DesktopObservation observation = service.captureObservation(owner, id)
                .toCompletableFuture().join().orElseThrow();
        assertTrue(service.commitObservation(owner, id, observation.observationId(), List.of())
                .toCompletableFuture().join());
        return observation;
    }

    private static void await(BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return;
            try { Thread.sleep(10); }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            }
        }
        assertTrue(condition.getAsBoolean(), "condition did not become true within three seconds");
    }

    private static final class FakeProvider implements DesktopPlatformProvider {
        private final String id;
        volatile boolean available;
        private final List<DesktopTarget> targets;
        final AtomicInteger discoveryCount = new AtomicInteger();
        final AtomicInteger launchCount = new AtomicInteger();
        volatile DesktopApplicationLaunch nextLaunch = new DesktopApplicationLaunch(202, "");
        volatile List<DesktopTarget> targetsAfterLaunch;
        volatile RuntimeException discoveryFailureAfterLaunch;
        volatile int targetVisibleAfterDiscoveries;
        volatile String lastLaunchedApplication;
        final List<FakeSession> opened = new CopyOnWriteArrayList<>();

        FakeProvider(String id, boolean available, List<DesktopTarget> targets) {
            this.id = id;
            this.available = available;
            this.targets = targets;
        }

        @Override public String id() { return id; }

        @Override public DesktopAvailability probe() {
            return new DesktopAvailability(available, id,
                    available ? DesktopAvailability.CAPTURE : 0,
                    available ? "available" : "native library unavailable");
        }

        @Override public List<DesktopTarget> discoverTargets() {
            int count = discoveryCount.incrementAndGet();
            if (launchCount.get() > 0 && discoveryFailureAfterLaunch != null)
                throw discoveryFailureAfterLaunch;
            if (launchCount.get() > 0 && targetsAfterLaunch != null
                    && count > targetVisibleAfterDiscoveries) return targetsAfterLaunch;
            return targets;
        }

        @Override public DesktopApplicationLaunch launchApplication(String application) {
            launchCount.incrementAndGet();
            lastLaunchedApplication = application;
            return nextLaunch;
        }

        @Override public DesktopPlatformSession open(DesktopTarget target) {
            FakeSession session = new FakeSession(target);
            opened.add(session);
            return session;
        }
    }

    private static final class FakeSession implements DesktopPlatformSession {
        private volatile DesktopTarget target;
        private final BlockingQueue<DesktopFrame> frames = new LinkedBlockingQueue<>();
        final AtomicInteger actionCount = new AtomicInteger();
        final AtomicInteger prepareCount = new AtomicInteger();
        final AtomicInteger restoreCount = new AtomicInteger();
        final List<Boolean> foregroundModes = new CopyOnWriteArrayList<>();
        final AtomicInteger closeCount = new AtomicInteger();
        volatile CountDownLatch actionEntered;
        volatile CountDownLatch actionRelease;
        volatile DesktopActionResult nextResult =
                new DesktopActionResult(DesktopActionResult.Status.VERIFIED, "ok", 1);
        volatile RuntimeException nextFailure;
        volatile RuntimeException prepareFailure;
        volatile String diagnostics = "";
        volatile boolean targetActive;
        volatile boolean exposeElements = true;
        volatile boolean closed;

        FakeSession(DesktopTarget target) { this.target = target; }

        void emit(DesktopFrame frame) { frames.add(frame); }

        @Override public DesktopTarget currentTarget() { return target; }

        @Override public Optional<DesktopFrame> pollFrame(int timeoutMillis) {
            try { return Optional.ofNullable(frames.poll(timeoutMillis, TimeUnit.MILLISECONDS)); }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            }
        }

        @Override public DesktopActionResult perform(DesktopAction action, boolean foreground) {
            actionCount.incrementAndGet();
            foregroundModes.add(foreground);
            if (nextFailure != null) throw nextFailure;
            if (actionEntered != null) actionEntered.countDown();
            if (actionRelease != null) {
                try { actionRelease.await(3, TimeUnit.SECONDS); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            }
            return nextResult;
        }

        @Override public DesktopActionResult performClick(DesktopAction action, boolean foreground,
                com.javaclaw.desktop.spi.DesktopClickGuard guard) {
            return perform(action, foreground);
        }

        @Override public Optional<Boolean> isTargetActive() { return Optional.of(targetActive); }
        @Override public List<DesktopElement> elements(DesktopFrame frame) {
            return exposeElements ? List.of(new DesktopElement("e1", "button", "Test control", 0, 0,
                    frame.width(), frame.height(), DesktopElement.PRESS | DesktopElement.WRITE
                    | DesktopElement.INSERT_TEXT | DesktopElement.SET_TEXT | DesktopElement.SCROLL)) : List.of();
        }
        @Override public String elementDiagnostics() { return diagnostics; }

        @Override public void prepareForeground() {
            prepareCount.incrementAndGet();
            if (prepareFailure != null) throw prepareFailure;
        }

        @Override public void restoreForeground() { restoreCount.incrementAndGet(); }

        @Override public void close() {
            closed = true;
            closeCount.incrementAndGet();
        }
    }

    private static final class RecordingSubscriber<T> implements Flow.Subscriber<T> {
        final List<T> items = new CopyOnWriteArrayList<>();
        final AtomicInteger completed = new AtomicInteger();
        private Flow.Subscription subscription;

        @Override public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
        }

        void request(long count) { subscription.request(count); }

        @Override public void onNext(T item) { items.add(item); }

        @Override public void onError(Throwable error) { throw new AssertionError(error); }

        @Override public void onComplete() { completed.incrementAndGet(); }
    }
}
