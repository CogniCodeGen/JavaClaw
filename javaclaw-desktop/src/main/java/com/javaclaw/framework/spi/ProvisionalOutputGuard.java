package com.javaclaw.framework.spi;

import com.fasterxml.jackson.databind.JsonNode;
import com.javaclaw.framework.api.RunId;
import com.javaclaw.framework.api.RunRequest;

import java.util.Objects;

/** 显式选择纯草稿检查；未知扩展守卫仍只在最终输出阶段执行。 */
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

    /** 宿主调用栈标记，不接受可伪造的 RunRequest 属性。 */
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
