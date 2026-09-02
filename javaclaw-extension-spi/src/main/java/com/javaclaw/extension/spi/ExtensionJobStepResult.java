package com.javaclaw.extension.spi;

import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.ExecutionState;
import com.javaclaw.api.TurnId;

/**
 * 一个工作单元的提交结果。
 *
 * @param result 脱敏规范结果
 * @param checkpoint 下次恢复使用的完整 checkpoint
 * @param nextState 单元提交后的 Job 状态
 * @param turnId 本单元创建或等待的 Turn
 * @param effectReceiptKey 已提交副作用的 EffectReceipt 幂等键
 * @param inputWait 可选的权威 InputRequest 等待关联；仅 WAITING_INPUT 可携带
 */
public record ExtensionJobStepResult(
        CanonicalPayload result,
        CanonicalPayload checkpoint,
        ExecutionState nextState,
        Optional<TurnId> turnId,
        Optional<String> effectReceiptKey,
        Optional<ExtensionJobInputWait> inputWait) {
    /**
     * 创建不等待平台 InputRequest 的工作单元结果。
     *
     * @param result 脱敏规范结果
     * @param checkpoint 下次恢复使用的完整 checkpoint
     * @param nextState 单元提交后的 Job 状态
     * @param turnId 本单元创建或等待的 Turn
     * @param effectReceiptKey 已提交副作用的 EffectReceipt 幂等键
     */
    public ExtensionJobStepResult(
            CanonicalPayload result,
            CanonicalPayload checkpoint,
            ExecutionState nextState,
            Optional<TurnId> turnId,
            Optional<String> effectReceiptKey) {
        this(result, checkpoint, nextState, turnId, effectReceiptKey, Optional.empty());
    }

    /** 校验推进结果；失败由 Supervisor 统一记录稳定错误码。 */
    public ExtensionJobStepResult {
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(checkpoint, "checkpoint");
        Objects.requireNonNull(nextState, "nextState");
        turnId = Objects.requireNonNull(turnId, "turnId");
        effectReceiptKey = Objects.requireNonNull(effectReceiptKey, "effectReceiptKey")
                .map(value -> {
                    String normalized = value.strip();
                    if (normalized.isEmpty() || normalized.length() > 500) {
                        throw new IllegalArgumentException("effectReceiptKey length is invalid");
                    }
                    return normalized;
                });
        inputWait = Objects.requireNonNull(inputWait, "inputWait");
        if (nextState == ExecutionState.QUEUED || nextState == ExecutionState.FAILED) {
            throw new IllegalArgumentException("step result must not return QUEUED or FAILED");
        }
        if (inputWait.isPresent()) {
            ExtensionJobInputWait wait = inputWait.orElseThrow();
            if (nextState != ExecutionState.WAITING_INPUT
                    || turnId.filter(wait.turnId()::equals).isEmpty()) {
                throw new IllegalArgumentException("inputWait requires WAITING_INPUT and the same turnId");
            }
        }
    }
}
