package com.javaclaw.server.extension.thirdparty;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.CanonicalPayload;

/** 第三方 Worker 与 Host 之间的受控回调协议值对象。 */
final class ThirdPartyWorkerProtocol {
    private ThirdPartyWorkerProtocol() {}

    /** Worker 可请求的 Host 托管操作。 */
    enum ActionKind {
        DOCUMENT_GET,
        DOCUMENT_PUT,
        BLOB_PUT,
        NETWORK_EXCHANGE
    }

    /**
     * Worker 在一轮响应中请求的托管操作。
     *
     * @param id 本轮内唯一的关联标识
     * @param kind 操作类别
     * @param arguments 规范参数
     */
    record Action(String id, ActionKind kind, CanonicalPayload arguments) {
        Action {
            id = Objects.requireNonNull(id, "id").strip();
            if (!id.matches("[A-Za-z0-9._-]{1,80}")) {
                throw new IllegalArgumentException("worker action id is invalid");
            }
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(arguments, "arguments");
        }
    }

    /**
     * Host 返回给下一轮 Worker 的操作结果。
     *
     * @param id 对应请求标识
     * @param ok 是否成功
     * @param payload 成功结果或空对象
     * @param error 失败详情
     */
    record ActionResult(String id, boolean ok, CanonicalPayload payload, Optional<Failure> error) {
        ActionResult {
            id = Objects.requireNonNull(id, "id").strip();
            Objects.requireNonNull(payload, "payload");
            error = Objects.requireNonNull(error, "error");
            if (ok == error.isPresent()) {
                throw new IllegalArgumentException("worker action result status is inconsistent");
            }
        }

        static ActionResult success(String id, CanonicalPayload payload) {
            return new ActionResult(id, true, payload, Optional.empty());
        }

        static ActionResult failure(String id, String code, String message) {
            return new ActionResult(id, false, new CanonicalPayload("{}"), Optional.of(new Failure(code, message)));
        }
    }

    /**
     * Worker 可处理的稳定失败。
     *
     * @param code 稳定错误码
     * @param message 已脱敏说明
     */
    record Failure(String code, String message) {
        Failure {
            code = text(code, "code");
            message = text(message, "message");
        }
    }

    static <T> List<T> immutable(List<T> values, String name) {
        return List.copyOf(Objects.requireNonNull(values, name));
    }

    private static String text(String value, String name) {
        String normalized = Objects.requireNonNull(value, name).strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }
}
