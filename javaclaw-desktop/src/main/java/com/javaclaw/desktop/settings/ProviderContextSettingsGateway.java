package com.javaclaw.desktop.settings;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import com.javaclaw.api.ModelContextLimits;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.client.CommandOptions;

/** 模型容量设置的 SDK 异步边界；默认实现使旧设置宿主明确报告能力未提供。 */
public interface ProviderContextSettingsGateway {
    /**
     * 读取精确模型版本容量。
     *
     * @param provider 精确引用
     * @return JavaFX 线程完成的容量结果
     */
    default CompletionStage<ModelContextLimits> modelContextLimits(ProviderRef provider) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("服务端未提供模型容量设置"));
    }

    /**
     * 以新 Provider revision 保存容量。
     *
     * @param limits 旧版本引用和新值，空值表示未知
     * @param options 幂等与期望版本
     * @return 已提交的新版本容量
     */
    default CompletionStage<ModelContextLimits> updateModelContextLimits(
            ModelContextLimits limits, CommandOptions options) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("服务端未提供模型容量设置"));
    }
}
