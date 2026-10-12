package com.javaclaw.desktop.api;

import java.util.List;

/** Public semantic abilities of the current observation, never a promise about an application. */
public record DesktopInputCapabilities(long windowGeneration, boolean press,
        boolean insertText, boolean setText, boolean scroll, String detail) {
    public static DesktopInputCapabilities forElements(DesktopFrame frame, List<DesktopElement> elements) {
        int flags = elements.stream().mapToInt(DesktopElement::actions).reduce(0, (a, b) -> a | b);
        return new DesktopInputCapabilities(frame.windowGeneration(), (flags & DesktopElement.PRESS) != 0,
                (flags & DesktopElement.INSERT_TEXT) != 0, (flags & DesktopElement.SET_TEXT) != 0,
                (flags & DesktopElement.SCROLL) != 0,
                "仅支持当前观察明确提供的公开控件动作；不模拟任意坐标、组合键或系统键鼠");
    }

    public boolean supports(DesktopAction action, List<DesktopElement> elements) {
        if (action.windowGeneration() != windowGeneration || action.elementId().isBlank()) return false;
        return elements.stream().filter(element -> element.id().equals(action.elementId()))
                .anyMatch(element -> supportsElement(action, element));
    }

    public static boolean supportsElement(DesktopAction action, DesktopElement element) {
        int required = switch (action.kind()) {
            case CLICK -> action.button() == 1 && action.clicks() == 1 ? DesktopElement.PRESS : 0;
            case TYPE -> action.textOperation() == DesktopAction.TextOperation.SET_TEXT
                    ? DesktopElement.SET_TEXT : DesktopElement.INSERT_TEXT;
            case SCROLL -> DesktopElement.SCROLL;
            case KEY -> 0;
        };
        return required != 0 && (element.actions() & required) != 0;
    }
}
