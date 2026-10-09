package com.javaclaw.desktop.api;

/** Host-selected immutable input policy. A background session can never promote itself. */
public enum DesktopInputPolicy {
    BACKGROUND_STRICT,
    SYSTEM_EXPLICIT
}
