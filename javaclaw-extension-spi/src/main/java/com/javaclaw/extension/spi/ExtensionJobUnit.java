package com.javaclaw.extension.spi;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.TurnId;

/**
 * Job 中一个可独立恢复的工作单元。
 *
 * @param jobId 所属 Job
 * @param sequence 单调递增序号
 * @param unitId 扩展生成的确定性单元标识
 * @param intent 执行副作用前已提交的规范意图
 * @param state 单元状态
 * @param result 完成或失败时的脱敏结果
 * @param checkpoint 完成时提交给 Job 的 checkpoint
 * @param turnId 本单元创建的 Turn；没有时为空
 * @param effectReceiptKey 已提交副作用的 EffectReceipt 幂等键；没有时为空
 * @param errorCode 失败稳定码；其他状态为空
 * @param createdAt 意图提交时间
 * @param completedAt 终态提交时间
 */
public record ExtensionJobUnit(
        String jobId,
        long sequence,
        String unitId,
        CanonicalPayload intent,
        ExtensionJobUnitState state,
        Optional<CanonicalPayload> result,
        Optional<CanonicalPayload> checkpoint,
        Optional<TurnId> turnId,
        Optional<String> effectReceiptKey,
        Optional<String> errorCode,
        Instant createdAt,
        Optional<Instant> completedAt) {
    /** 校验工作单元提交不变量。 */
    public ExtensionJobUnit {
        jobId = identifier(jobId, "jobId", 240);
        if (sequence < 1) {
            throw new IllegalArgumentException("sequence must be positive");
        }
        unitId = identifier(unitId, "unitId", 240);
        Objects.requireNonNull(intent, "intent");
        Objects.requireNonNull(state, "state");
        result = Objects.requireNonNull(result, "result");
        checkpoint = Objects.requireNonNull(checkpoint, "checkpoint");
        turnId = Objects.requireNonNull(turnId, "turnId");
        effectReceiptKey = optionalText(effectReceiptKey, "effectReceiptKey", 500);
        errorCode = optionalText(errorCode, "errorCode", 160);
        Objects.requireNonNull(createdAt, "createdAt");
        completedAt = Objects.requireNonNull(completedAt, "completedAt");
        boolean terminal = state != ExtensionJobUnitState.INTENT_RECORDED;
        if (terminal != completedAt.isPresent() || terminal != result.isPresent()) {
            throw new IllegalArgumentException("terminal unit requires result and completedAt");
        }
        if ((state == ExtensionJobUnitState.COMPLETED) != checkpoint.isPresent()) {
            throw new IllegalArgumentException("only completed unit requires checkpoint");
        }
        if ((state == ExtensionJobUnitState.FAILED) != errorCode.isPresent()) {
            throw new IllegalArgumentException("only failed unit requires errorCode");
        }
        completedAt.ifPresent(value -> {
            if (value.isBefore(createdAt)) {
                throw new IllegalArgumentException("completedAt must not be before createdAt");
            }
        });
    }

    private static Optional<String> optionalText(Optional<String> value, String name, int limit) {
        return Objects.requireNonNull(value, name).map(entry -> identifier(entry, name, limit));
    }

    private static String identifier(String value, String name, int limit) {
        String normalized = Objects.requireNonNull(value, name).strip();
        if (normalized.isEmpty() || normalized.length() > limit) {
            throw new IllegalArgumentException(name + " length is invalid");
        }
        return normalized;
    }
}
