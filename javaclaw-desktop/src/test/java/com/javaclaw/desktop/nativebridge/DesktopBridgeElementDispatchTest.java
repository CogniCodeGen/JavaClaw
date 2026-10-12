package com.javaclaw.desktop.nativebridge;

import com.javaclaw.desktop.api.DesktopAction;
import com.javaclaw.desktop.api.DesktopActionResult;
import com.javaclaw.desktop.ffm.DesktopSemanticAction;
import org.junit.jupiter.api.Test;
import static com.javaclaw.desktop.nativebridge.DesktopBridgeTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

class DesktopBridgeElementDispatchTest {
    @Test void snapshotTokensAreUnsignedNonzeroAndObservationScoped() {
        assertEquals("e4294967295", DesktopBridge.elementId(-1));
        assertEquals(-1, DesktopBridge.publicElementIndexFor(action(DesktopAction.Kind.CLICK,
                OBS + ":e4294967295", DesktopAction.TextOperation.INSERT_TEXT)).orElseThrow());
        for (String invalid : new String[] { OBS + ":e0", OBS + ":e4294967296", "other:e1" })
            assertThrows(IllegalArgumentException.class, () -> DesktopBridge.publicElementIndexFor(
                    action(DesktopAction.Kind.CLICK, invalid, DesktopAction.TextOperation.INSERT_TEXT)));
    }

    @Test void unsupportedActionsNeverReachOperatingSystem() {
        var api = new Api();
        var bridge = new DesktopBridge(api);
        try (var session = bridge.open(WINDOW)) {
            for (var action : new DesktopAction[] {
                    action(DesktopAction.Kind.CLICK, OBS + ":v0", DesktopAction.TextOperation.INSERT_TEXT),
                    action(DesktopAction.Kind.KEY, OBS + ":e7", DesktopAction.TextOperation.INSERT_TEXT),
                    new DesktopAction(DesktopAction.Kind.CLICK, 1, 1, 3, 1, 0, "", 1, OBS, OBS + ":e7", 1) }) {
                var result = bridge.perform(session, action, false);
                assertEquals(DesktopActionResult.Status.UNSUPPORTED, result.status());
                assertEquals(DesktopActionResult.Delivery.NOT_SENT, result.delivery());
            }
            assertEquals(0, api.session.attempts);
            assertEquals("", bridge.elementDiagnostics(session));
        }
    }

    @Test void insertionAndReplacementRemainDifferentSingleAttemptOperations() {
        var api = new Api();
        var bridge = new DesktopBridge(api);
        try (var session = bridge.open(WINDOW)) {
            for (var operation : DesktopAction.TextOperation.values()) {
                var action = action(DesktopAction.Kind.TYPE, OBS + ":e27", operation);
                var request = DesktopSemanticAction.admit(action).orElseThrow();
                assertEquals(27, request.elementToken());
                assertEquals(operation == DesktopAction.TextOperation.INSERT_TEXT
                        ? DesktopSemanticAction.Operation.INSERT_TEXT : DesktopSemanticAction.Operation.SET_TEXT,
                        request.operation());
                bridge.perform(session, action, false);
                assertSame(action, api.session.lastAction);
            }
            assertEquals(2, api.session.attempts);
        }
    }
}
