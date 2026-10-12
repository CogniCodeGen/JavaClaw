package com.javaclaw.desktop.ffm.windows;

import com.javaclaw.desktop.api.DesktopAction;
import com.javaclaw.desktop.api.DesktopActionResult;
import com.javaclaw.desktop.api.DesktopElement;
import com.javaclaw.desktop.api.DesktopFrame;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WindowsUiaTest {
    private static final WindowsWindows.Rect WINDOW = new WindowsWindows.Rect(100, 200, 200, 300);
    private static final WindowsWindows.Rect CONTROL = new WindowsWindows.Rect(110, 210, 130, 230);
    private static final DesktopFrame FRAME = frame(1, 1);

    @Test void dispatchReidentifiesTheSameRuntimeIdAndConsumesItsToken() {
        Fixture fixture = new Fixture();
        String token = fixture.observe();
        FakeNode replacement = new FakeNode(fixture.node.state);
        fixture.nodes = List.of(replacement);
        DesktopAction action = click(token, 1, 1);
        assertEquals(DesktopActionResult.Status.ACCEPTED, fixture.perform(action).status());
        assertEquals(0, fixture.node.dispatches);
        assertEquals(1, replacement.dispatches, "the observed apartment's object must not be reused");
        assertEquals(DesktopActionResult.Status.STALE_FRAME, fixture.perform(action).status());
        assertEquals(1, replacement.dispatches);
    }

    @Test void coordinatesWithoutAnObservedElementNeverChooseAnotherControl() {
        Fixture fixture = new Fixture();
        fixture.observe();
        assertEquals(DesktopActionResult.Status.UNSUPPORTED, fixture.perform(click("", 1, 1)).status());
        assertEquals(0, fixture.node.dispatches);
    }

    @Test void revisionMayAdvanceForAnimationButWindowAndGenerationCannotChange() {
        Fixture fixture = new Fixture();
        String token = fixture.observe();
        assertEquals(DesktopActionResult.Status.ACCEPTED, fixture.uia.perform(7, WINDOW, frame(1, 2),
                click(token, 1, 2), () -> true).status());
        token = fixture.observe();
        assertEquals(DesktopActionResult.Status.STALE_FRAME, fixture.uia.perform(8, WINDOW, FRAME,
                click(token, 1, 1), () -> true).status());
        token = fixture.observe();
        assertEquals(DesktopActionResult.Status.STALE_FRAME, fixture.uia.perform(7, WINDOW, frame(2, 2),
                click(token, 2, 2), () -> true).status());
        assertEquals(1, fixture.node.dispatches);
    }

    @Test void changedIdentityStateGeometryAndLabelAllRejectBeforeDispatch() {
        Fixture fixture = new Fixture();
        WindowsUia.State original = fixture.node.state;
        List<WindowsUia.State> changed = List.of(
                state(List.of(9), 12, CONTROL, 50000, "Go", true, false),
                state(original.runtimeId(), 13, CONTROL, 50000, "Go", true, false),
                state(original.runtimeId(), 12, new WindowsWindows.Rect(111, 210, 131, 230), 50000, "Go", true, false),
                state(original.runtimeId(), 12, CONTROL, 50002, "Go", true, false),
                state(original.runtimeId(), 12, CONTROL, 50000, "Delete", true, false),
                state(original.runtimeId(), 12, CONTROL, 50000, "Go", false, false),
                state(original.runtimeId(), 12, CONTROL, 50000, "Go", true, true));
        for (WindowsUia.State current : changed) {
            fixture.node.state = original;
            String token = fixture.observe();
            fixture.node.state = current;
            DesktopActionResult result = fixture.perform(click(token, 1, 1));
            assertEquals(DesktopActionResult.Status.STALE_FRAME, result.status());
            assertEquals(DesktopActionResult.Delivery.NOT_SENT, result.delivery());
        }
        assertEquals(0, fixture.node.dispatches);
    }

    @Test void duplicatedOrIncompleteRuntimeIdentityNeverDispatches() {
        Fixture fixture = new Fixture();
        String token = fixture.observe();
        fixture.nodes = List.of(fixture.node, new FakeNode(fixture.node.state));
        assertEquals(DesktopActionResult.Status.STALE_FRAME, fixture.perform(click(token, 1, 1)).status());
        fixture.nodes = List.of(fixture.node);
        token = fixture.observe();
        fixture.truncated = true;
        assertEquals(DesktopActionResult.Status.STALE_FRAME, fixture.perform(click(token, 1, 1)).status());
        assertEquals(0, fixture.node.dispatches);
    }

    @Test void activeTargetAndPreparationFailureAreNotUnknownDeliveries() {
        Fixture fixture = new Fixture();
        String token = fixture.observe();
        assertEquals(DesktopActionResult.Delivery.NOT_SENT, fixture.uia.perform(7, WINDOW, FRAME,
                click(token, 1, 1), () -> false).delivery());
        token = fixture.observe();
        fixture.node.prepareFailure = true;
        DesktopActionResult result = fixture.perform(click(token, 1, 1));
        assertEquals(DesktopActionResult.Status.FAILED, result.status());
        assertEquals(DesktopActionResult.Delivery.NOT_SENT, result.delivery());
        assertEquals(0, fixture.node.dispatches);
    }

    @Test void errorAfterTheMutationBoundaryIsUnknownAndCannotReplay() {
        Fixture fixture = new Fixture();
        String token = fixture.observe();
        fixture.node.dispatchStatus = 0x80004005;
        DesktopAction action = click(token, 1, 1);
        DesktopActionResult result = fixture.perform(action);
        assertEquals(DesktopActionResult.Status.UNKNOWN, result.status());
        assertEquals(DesktopActionResult.Delivery.MAYBE_SENT, result.delivery());
        assertEquals(DesktopActionResult.NextStep.RECONCILE, result.nextStep());
        assertEquals(DesktopActionResult.Status.STALE_FRAME, fixture.perform(action).status());
        assertEquals(1, fixture.node.dispatches);
    }

    @Test void targetActivationStopsAMultiStepScrollAfterTheFirstDispatch() {
        Fixture fixture = new Fixture();
        fixture.node.state = new WindowsUia.State(List.of(1, 2), 12, CONTROL, 50014, "", false,
                true, false, DesktopElement.SCROLL, 0);
        String token = fixture.observe();
        AtomicBoolean ready = new AtomicBoolean(true);
        fixture.node.onDispatch = () -> ready.set(false);
        DesktopAction action = new DesktopAction(DesktopAction.Kind.SCROLL, 15, 15, 1, 1, 3,
                "", 1, "observation", "observation:" + token, 1);
        DesktopActionResult result = fixture.uia.perform(7, WINDOW, FRAME, action, ready::get);
        assertEquals(DesktopActionResult.Status.UNKNOWN, result.status());
        assertEquals(1, fixture.node.dispatches);
    }

    @Test void insertTextCannotUseSetValueAndUnsupportedGesturesCannotDispatch() {
        Fixture fixture = new Fixture();
        fixture.node.state = new WindowsUia.State(List.of(1, 2), 12, CONTROL, 50004, "secret", false,
                true, false, DesktopElement.WRITE | DesktopElement.SET_TEXT, 0);
        String token = fixture.observe();
        assertEquals("", fixture.elements.getFirst().label());
        DesktopAction insert = new DesktopAction(DesktopAction.Kind.TYPE, 15, 15, 1, 1, 0, "hello", 1,
                "observation", "observation:" + token, 1, DesktopAction.TextOperation.INSERT_TEXT);
        assertEquals(DesktopActionResult.Status.UNSUPPORTED, fixture.perform(insert).status());
        token = fixture.observe();
        fixture.node.verified = true;
        DesktopAction replacement = new DesktopAction(DesktopAction.Kind.TYPE, 15, 15, 1, 1, 0, "", 1,
                "observation", "observation:" + token, 1, DesktopAction.TextOperation.SET_TEXT);
        assertEquals(DesktopActionResult.Status.VERIFIED, fixture.perform(replacement).status());
        assertEquals(WindowsUia.VALUE, fixture.node.lastPattern);
        assertEquals(1, fixture.node.dispatches);
        for (DesktopAction action : List.of(
                new DesktopAction(DesktopAction.Kind.CLICK, 15, 15, 3, 1, 0, "", 1, "observation", "e1", 1),
                new DesktopAction(DesktopAction.Kind.CLICK, 15, 15, 1, 2, 0, "", 1, "observation", "e1", 1),
                new DesktopAction(DesktopAction.Kind.KEY, 15, 15, 1, 1, 0, "ENTER", 1, "observation", "e1", 1)))
            assertFalse(WindowsUia.supported(action));
    }

    @Test void passwordAndEditableNamesAreRedactedAndCatalogIsBounded() {
        Fixture fixture = new Fixture();
        List<WindowsUia.Node> nodes = new ArrayList<>();
        for (int index = 0; index < 140; index++)
            nodes.add(new FakeNode(new WindowsUia.State(List.of(index), 12, CONTROL, 50000, "secret", true,
                    true, false, DesktopElement.PRESS, WindowsUia.INVOKE)));
        fixture.nodes = nodes;
        fixture.observe();
        assertEquals(128, fixture.elements.size());
        assertTrue(fixture.elements.stream().allMatch(element -> element.label().isEmpty()));
        assertTrue(fixture.uia.diagnostics().contains("truncated=true"));
    }

    @Test void passwordControlsPermitSetValueWithoutValueReadback() {
        Fixture fixture = new Fixture();
        fixture.node.state = new WindowsUia.State(List.of(1, 2), 12, CONTROL, 50004, "secret", true,
                true, false, DesktopElement.PRESS | DesktopElement.WRITE | DesktopElement.SET_TEXT, WindowsUia.INVOKE);
        String token = fixture.observe();
        assertEquals(DesktopElement.PRESS | DesktopElement.WRITE | DesktopElement.SET_TEXT,
                fixture.elements.getFirst().actions());
        assertEquals("", fixture.elements.getFirst().label());
        fixture.node.verified = true;
        DesktopAction action = new DesktopAction(DesktopAction.Kind.TYPE, 15, 15, 1, 1, 0, "value", 1,
                "observation", "observation:" + token, 1, DesktopAction.TextOperation.SET_TEXT);
        assertEquals(DesktopActionResult.Status.ACCEPTED, fixture.perform(action).status());
        assertEquals(1, fixture.node.dispatches);
        assertEquals(0, fixture.node.readbacks, "password contents must never be queried for verification");
    }

    @Test void nonClickSemanticActionsRequireTheExactObservedRevision() {
        Fixture fixture = new Fixture();
        fixture.node.state = new WindowsUia.State(List.of(1, 2), 12, CONTROL, 50004, "", false,
                true, false, DesktopElement.WRITE | DesktopElement.SET_TEXT | DesktopElement.SCROLL, 0);
        String token = fixture.observe();
        DesktopAction text = new DesktopAction(DesktopAction.Kind.TYPE, 15, 15, 1, 1, 0, "value", 1,
                "observation", "observation:" + token, 2, DesktopAction.TextOperation.SET_TEXT);
        assertEquals(DesktopActionResult.Status.STALE_FRAME,
                fixture.uia.perform(7, WINDOW, frame(1, 2), text, () -> true).status());
        token = fixture.observe();
        DesktopAction scroll = new DesktopAction(DesktopAction.Kind.SCROLL, 15, 15, 1, 1, 1, "", 1,
                "observation", "observation:" + token, 2);
        assertEquals(DesktopActionResult.Status.STALE_FRAME,
                fixture.uia.perform(7, WINDOW, frame(1, 2), scroll, () -> true).status());
        assertEquals(0, fixture.node.dispatches);
    }

    @Test void stateChangesDuringPreparationOrScrollStopBeforeTheNextDispatch() {
        Fixture fixture = new Fixture();
        String token = fixture.observe();
        fixture.node.onPrepare = () -> fixture.node.state = state(List.of(1, 2), 12, CONTROL, 50000, "Changed", true, false);
        assertEquals(DesktopActionResult.Status.STALE_FRAME, fixture.perform(click(token, 1, 1)).status());
        assertEquals(0, fixture.node.dispatches);
        fixture.node.onPrepare = () -> { };
        fixture.node.state = new WindowsUia.State(List.of(1, 2), 12, CONTROL, 50014, "", false,
                true, false, DesktopElement.SCROLL, 0);
        token = fixture.observe();
        fixture.node.onDispatch = () -> fixture.node.state = new WindowsUia.State(List.of(1, 2), 12, CONTROL, 50014, "", false,
                false, false, DesktopElement.SCROLL, 0);
        DesktopAction scroll = new DesktopAction(DesktopAction.Kind.SCROLL, 15, 15, 1, 1, 3, "", 1,
                "observation", "observation:" + token, 1);
        DesktopActionResult result = fixture.perform(scroll);
        assertEquals(DesktopActionResult.Status.UNKNOWN, result.status());
        assertEquals(DesktopActionResult.Delivery.MAYBE_SENT, result.delivery());
        assertEquals(1, fixture.node.dispatches);
    }

    @Test void finalCaptureFailureAfterPatternPreparationNeverDispatchesOrClaimsAnUnknownEffect() {
        Fixture fixture = new Fixture();
        fixture.node.state = new WindowsUia.State(List.of(1, 2), 12, CONTROL, 50004, "", false,
                true, false, DesktopElement.WRITE | DesktopElement.SET_TEXT, 0);
        String token = fixture.observe();
        AtomicBoolean frameUnchanged = new AtomicBoolean(true);
        fixture.node.onPrepare = () -> frameUnchanged.set(false);
        DesktopAction text = new DesktopAction(DesktopAction.Kind.TYPE, 15, 15, 1, 1, 0, "value", 1,
                "observation", "observation:" + token, 1, DesktopAction.TextOperation.SET_TEXT);
        DesktopActionResult result = fixture.uia.perform(7, WINDOW, FRAME, text, () -> true, frameUnchanged::get);
        assertEquals(DesktopActionResult.Status.STALE_FRAME, result.status());
        assertEquals(DesktopActionResult.Delivery.NOT_SENT, result.delivery());
        assertEquals(0, fixture.node.dispatches);
    }

    @Test void finalCaptureRunsOnceBeforeTheFirstScrollAndDoesNotCompareAgainstItsIntentionalUpdates() {
        Fixture fixture = new Fixture();
        fixture.node.state = new WindowsUia.State(List.of(1, 2), 12, CONTROL, 50014, "", false,
                true, false, DesktopElement.SCROLL, 0);
        String token = fixture.observe();
        var captures = new java.util.concurrent.atomic.AtomicInteger();
        DesktopAction scroll = new DesktopAction(DesktopAction.Kind.SCROLL, 15, 15, 1, 1, 3, "", 1,
                "observation", "observation:" + token, 1);
        DesktopActionResult result = fixture.uia.perform(7, WINDOW, FRAME, scroll, () -> true,
                () -> captures.incrementAndGet() == 1);
        assertEquals(DesktopActionResult.Status.ACCEPTED, result.status());
        assertEquals(3, fixture.node.dispatches);
        assertEquals(1, captures.get());
    }

    private static DesktopFrame frame(long generation, long revision) {
        return new DesktopFrame("target", generation, 1234, 100, 100, 400, new byte[40000], revision);
    }
    private static DesktopAction click(String token, long generation, long revision) {
        return new DesktopAction(DesktopAction.Kind.CLICK, 15, 15, 1, 1, 0, "", generation,
                "observation", token.isEmpty() ? "" : "observation:" + token, revision);
    }
    private static WindowsUia.State state(List<Integer> id, int pid, WindowsWindows.Rect bounds,
            int type, String label, boolean enabled, boolean offscreen) {
        return new WindowsUia.State(id, pid, bounds, type, label, false, enabled, offscreen,
                DesktopElement.PRESS, WindowsUia.INVOKE);
    }
    private static final class Fixture {
        final FakeNode node = new FakeNode(state(List.of(1, 2), 12, CONTROL, 50000, "Go", true, false));
        List<WindowsUia.Node> nodes = List.of(node);
        List<DesktopElement> elements;
        boolean truncated;
        final WindowsUia uia = new WindowsUia(window -> new WindowsUia.Tree() {
            @Override public List<WindowsUia.Node> nodes() { return nodes; }
            @Override public boolean truncated() { return truncated; }
            @Override public void close() { }
        });
        String observe() {
            elements = uia.elements(7, WINDOW, FRAME);
            return elements.getFirst().id();
        }
        DesktopActionResult perform(DesktopAction action) { return uia.perform(7, WINDOW, FRAME, action, () -> true); }
    }
    private static final class FakeNode implements WindowsUia.Node {
        WindowsUia.State state;
        int dispatches, dispatchStatus, lastPattern, readbacks;
        boolean prepareFailure, verified;
        Runnable onPrepare = () -> { };
        Runnable onDispatch = () -> { };
        FakeNode(WindowsUia.State state) { this.state = state; }
        @Override public WindowsUia.State state() { return state; }
        @Override public WindowsUia.Mutation prepare(int pattern, String text, int direction) {
            if (prepareFailure) throw new IllegalStateException("read-only pattern changed before mutation");
            onPrepare.run();
            lastPattern = pattern;
            return new WindowsUia.Mutation() {
                @Override public int dispatch() { dispatches++; onDispatch.run(); return dispatchStatus; }
                @Override public boolean confirms() { readbacks++; return verified; }
            };
        }
    }
}
