package com.javaclaw.desktop.api;

/** Frame-relative input. Generation prevents acting on an obsolete window or popup. */
public record DesktopAction(Kind kind, int x, int y, int button, int clicks,
                            int amount, String text, long windowGeneration,
                            String observationId, String elementId, long contentRevision,
                            TextOperation textOperation) {
    public enum Kind { CLICK, TYPE, KEY, SCROLL }
    public enum TextOperation { INSERT_TEXT, SET_TEXT }

    public DesktopAction {
        if (kind == null) throw new IllegalArgumentException("action kind is required");
        text = text == null ? "" : text;
        observationId = observationId == null ? "" : observationId;
        elementId = elementId == null ? "" : elementId;
        textOperation = textOperation == null ? TextOperation.INSERT_TEXT : textOperation;
        if (windowGeneration < 1) throw new IllegalArgumentException("a current frame generation is required");
        if (contentRevision < 0) throw new IllegalArgumentException("invalid content revision");
        if (kind == Kind.CLICK && (button < 1 || button > 3 || clicks < 1 || clicks > 2))
            throw new IllegalArgumentException("button must be 1-3 and clicks must be 1-2");
        if (kind == Kind.SCROLL && (amount == 0 || amount < -10 || amount > 10))
            throw new IllegalArgumentException("scroll amount must be between -10 and 10, excluding zero");
        if ((kind == Kind.KEY && text.isBlank())
                || (kind == Kind.TYPE && text.isEmpty() && textOperation == TextOperation.INSERT_TEXT))
            throw new IllegalArgumentException("desktop text or key input is empty");
        if (text.length() > 8192) throw new IllegalArgumentException("desktop input is too long");
    }

    public DesktopAction(Kind kind, int x, int y, int button, int clicks, int amount,
            String text, long windowGeneration, String observationId, String elementId,
            long contentRevision) {
        this(kind, x, y, button, clicks, amount, text, windowGeneration, observationId,
                elementId, contentRevision, TextOperation.INSERT_TEXT);
    }

    /** Legacy arguments are representable for replay, but the service refuses them before input. */
    public DesktopAction(Kind kind, int x, int y, int button, int clicks,
                         int amount, String text, long windowGeneration) {
        this(kind, x, y, button, clicks, amount, text, windowGeneration, "", "", 0);
    }
}
