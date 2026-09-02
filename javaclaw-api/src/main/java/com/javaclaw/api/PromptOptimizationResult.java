package com.javaclaw.api;

import java.util.Objects;
import java.util.Optional;

/**
 * 从权威 Turn 与 Item 即时投影的 Prompt 优化结果。
 *
 * @param state 任务状态
 * @param turnRevision 当前 Turn revision，可用于取消命令的乐观锁
 * @param content READY 时的草稿正文；其他状态为空
 * @param contentDigest READY 时正文的 SHA-256；其他状态为空
 * @param errorCode 失败代码；没有失败时为空
 */
public record PromptOptimizationResult(
        PromptOptimizationState state,
        long turnRevision,
        Optional<String> content,
        Optional<String> contentDigest,
        Optional<String> errorCode) {
    /** 校验状态与可选输出的一致性。 */
    public PromptOptimizationResult {
        Objects.requireNonNull(state, "state");
        turnRevision = Preconditions.positive(turnRevision, "turnRevision");
        content = Objects.requireNonNull(content, "content").map(String::strip).filter(value -> !value.isEmpty());
        contentDigest = Objects.requireNonNull(contentDigest, "contentDigest")
                .map(value -> Preconditions.digest(value, "contentDigest"));
        errorCode = Objects.requireNonNull(errorCode, "errorCode")
                .map(String::strip)
                .filter(value -> !value.isEmpty());
        boolean ready = state == PromptOptimizationState.READY;
        if (ready != content.isPresent() || ready != contentDigest.isPresent()) {
            throw new IllegalArgumentException("READY state must carry content and digest only");
        }
    }
}
