package com.javaclaw.runtime;

import com.javaclaw.api.CancellationToken;

/** 支持 Provider 原生 conversation compaction 的可选模型扩展。 */
@FunctionalInterface
public interface NativeCompactionSupport {
    /**
     * 压缩 opaque state。
     *
     * @param request 请求
     * @param cancellation 取消信号
     * @return 新 state
     * @throws Exception Provider 失败
     */
    NativeCompactionResult compact(NativeCompactionRequest request, CancellationToken cancellation) throws Exception;
}
