package com.javaclaw.desktop.ffm;

import com.javaclaw.desktop.api.DesktopAction;
import com.javaclaw.desktop.nativebridge.DesktopBridge;
import java.util.Objects;
import java.util.Optional;

/** Admission shared by the public AX and UI Automation semantic paths. */
public final class DesktopSemanticAction {
    public enum Operation { PRESS, INSERT_TEXT, SET_TEXT, SCROLL }
    public record Request(int elementToken, Operation operation) { }

    private DesktopSemanticAction() { }

    public static Optional<Request> admit(DesktopAction action) {
        Objects.requireNonNull(action, "action");
        if (action.kind() == DesktopAction.Kind.KEY
                || action.kind() == DesktopAction.Kind.CLICK && (action.button() != 1 || action.clicks() != 1))
            return Optional.empty();
        var token = DesktopBridge.publicElementIndexFor(action);
        if (token.isEmpty()) return Optional.empty();
        Operation operation = switch (action.kind()) {
            case CLICK -> Operation.PRESS;
            case TYPE -> action.textOperation() == DesktopAction.TextOperation.INSERT_TEXT
                    ? Operation.INSERT_TEXT : Operation.SET_TEXT;
            case SCROLL -> Operation.SCROLL;
            case KEY -> throw new AssertionError("key admission already rejected");
        };
        return Optional.of(new Request(token.getAsInt(), operation));
    }
}
