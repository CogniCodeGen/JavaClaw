package com.javaclaw.sandbox.api;

import java.util.Objects;

/**
 * 一次 Launcher 执行的互斥成功/失败信封，nonce 必须与父进程请求匹配。
 *
 * @param nonce 父进程生成的非空白一次性关联值，用于核验管道消息来源
 * @param result 成功结果；失败时为 null
 * @param error 失败说明；成功时为 null，不得包含凭据
 */
public record SandboxLaunchResponse(String nonce, SandboxResult result, String error) {
    /** 要求 result/error 恰好一个非空，避免父进程将模糊响应误判为执行成功。 */
    public SandboxLaunchResponse {
        nonce = Objects.requireNonNull(nonce, "nonce");
        if ((result == null) == (error == null)) {
            throw new IllegalArgumentException("response requires exactly one of result or error");
        }
    }

    /** 创建带原 nonce 的成功响应；result 必须非空。 */
    public static SandboxLaunchResponse success(String nonce, SandboxResult result) {
        return new SandboxLaunchResponse(nonce, Objects.requireNonNull(result), null);
    }

    /** 创建带原 nonce 的失败响应；error 必须非空且应预先脱敏。 */
    public static SandboxLaunchResponse failure(String nonce, String error) {
        return new SandboxLaunchResponse(nonce, null, Objects.requireNonNull(error));
    }
}
