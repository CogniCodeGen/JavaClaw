package com.javaclaw.framework.core;

import com.javaclaw.framework.spi.EffectReceiptV1;

/** A recoverable safety pause with the identity of the prior effect blocking dispatch. */
public final class PendingEffectObservationRequiredException extends ToolPermissionDeniedException {
    public enum Reason { DELIVERY_UNCERTAIN, OBSERVATION_ALREADY_CONSUMED, EFFECT_ALREADY_ATTEMPTED }

    private final String sourceRunId;
    private final String invocationId;
    private final String resourceKey;
    private final EffectReceiptV1.Status status;
    private final String delivery;
    private final Reason reason;

    public PendingEffectObservationRequiredException(String sourceRunId, String invocationId,
            String resourceKey, EffectReceiptV1.Status status, String delivery, Reason reason) {
        super("non-idempotent effect already attempted in this conversation; observe its effect before any retry");
        this.sourceRunId = sourceRunId;
        this.invocationId = invocationId;
        this.resourceKey = resourceKey;
        this.status = status;
        this.delivery = delivery;
        this.reason = reason;
    }

    public String sourceRunId() { return sourceRunId; }
    public String invocationId() { return invocationId; }
    public String resourceKey() { return resourceKey; }
    public EffectReceiptV1.Status status() { return status; }
    public String delivery() { return delivery; }
    public Reason reason() { return reason; }

    public String diagnostic() {
        return switch (reason) {
            case DELIVERY_UNCERTAIN -> "先前操作可能已经生效，尚无可靠的操作结果证明；已暂停本次输入，请核验先前操作的结果。";
            case OBSERVATION_ALREADY_CONSUMED -> "先前操作已使用这份界面观察；已暂停重复输入，请重新观察并使用新的定位结果。";
            case EFFECT_ALREADY_ATTEMPTED -> "先前操作已经提交；已暂停重复执行，请核验先前操作的结果。";
        };
    }
}
