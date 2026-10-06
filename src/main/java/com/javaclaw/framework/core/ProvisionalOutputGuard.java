package com.javaclaw.framework.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.javaclaw.framework.api.RunId;
import com.javaclaw.framework.api.RunRequest;
import com.javaclaw.framework.spi.OutputGuard;

import java.util.Objects;

/** Internal opt-in to a pure draft check; unknown extension guards remain final-only. */
public final class ProvisionalOutputGuard implements OutputGuard {
    private static final ScopedValue<Boolean> ACTIVE = ScopedValue.newInstance();
    private final OutputGuard finalGuard;
    private final boolean checkDraft;

    private ProvisionalOutputGuard(OutputGuard finalGuard, boolean checkDraft) {
        this.finalGuard = Objects.requireNonNull(finalGuard, "finalGuard");
        this.checkDraft = checkDraft;
    }

    public static ProvisionalOutputGuard checked(OutputGuard guard) {
        return new ProvisionalOutputGuard(guard, true);
    }

    public static ProvisionalOutputGuard finalEffect(OutputGuard guard) {
        return new ProvisionalOutputGuard(guard, false);
    }

    /** Host call-stack marker, never a forgeable RunRequest attribute. */
    public static boolean checkingDraft() { return ACTIVE.isBound() && ACTIVE.get(); }

    public JsonNode validateDraft(JsonNode output, RunRequest request, RunId runId) {
        if (!checkDraft) return output;
        try {
            return ScopedValue.where(ACTIVE, true).call(() -> finalGuard.validate(output, request, runId));
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException("provisional output check failed", failure);
        }
    }

    @Override public JsonNode validate(JsonNode output, RunRequest request, RunId runId) {
        return finalGuard.validate(output, request, runId);
    }
}
