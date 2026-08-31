package com.javaclaw.sandbox.api;

/** Portable signal vocabulary. A backend rejects any signal it cannot implement exactly. */
public enum SandboxSignal {
    INTERRUPT,
    TERMINATE,
    KILL
}
