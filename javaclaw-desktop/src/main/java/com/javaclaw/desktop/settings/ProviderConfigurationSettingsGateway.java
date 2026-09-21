package com.javaclaw.desktop.settings;

import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.ProviderConfiguration;
import com.javaclaw.api.ProviderConfigurationResult;
import com.javaclaw.api.ProviderModelPreviewRequest;
import com.javaclaw.api.ProviderModelPreviewResult;
import com.javaclaw.client.CommandOptions;
import com.javaclaw.client.facade.PreparedProviderConfiguration;
import com.javaclaw.desktop.DesktopNotificationSubscription;

/** 管理页完整配置工作流的窄 SDK 网关；旧实现默认拒绝新增能力。 */
public interface ProviderConfigurationSettingsGateway {
    /**
     * 观察当前配置工作流依赖的会话失效；关闭后释放订阅。
     *
     * @param listener UI 线程上的临时秘密清理回调
     * @return 可关闭订阅
     */
    default DesktopNotificationSubscription onProviderConfigurationSessionInvalidated(Runnable listener) {
        return () -> {};
    }

    /** @return 当前已协商会话是否支持完整配置；查询不产生网络模型调用 */
    default CompletionStage<Boolean> providerConfigurationSupported() {
        return CompletableFuture.completedFuture(false);
    }

    /**
     * 预览当前草稿；接管并清零输入秘密数组。
     *
     * @param request 连接草稿与代次
     * @param secret 临时秘密，非替换意图使用空数组
     * @param cancellation 弹窗或草稿取消信号
     * @return 有界目录
     */
    default CompletionStage<ProviderModelPreviewResult> previewProviderModels(
            ProviderModelPreviewRequest request, char[] secret, CancellationToken cancellation) {
        Arrays.fill(secret, '\0');
        return CompletableFuture.failedFuture(new UnsupportedOperationException("请升级服务端以使用模型配置"));
    }

    /**
     * 为一次保存冻结密文请求；接管并清零输入秘密，不写服务端。
     *
     * @param configuration 最终非敏感配置
     * @param secret 临时秘密，非替换意图使用空数组
     * @param options 一次逻辑保存身份
     * @return 不含明文的冻结请求
     */
    default CompletionStage<PreparedProviderConfiguration> prepareProviderConfiguration(
            ProviderConfiguration configuration, char[] secret, CommandOptions options) {
        Arrays.fill(secret, '\0');
        return CompletableFuture.failedFuture(new UnsupportedOperationException("请升级服务端以使用模型配置"));
    }

    /**
     * 提交原冻结请求，不自动重新密封。
     *
     * @param prepared 原逻辑保存
     * @return 已提交事实
     */
    default CompletionStage<ProviderConfigurationResult> saveProviderConfiguration(
            PreparedProviderConfiguration prepared) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("请升级服务端以使用模型配置"));
    }

    /**
     * 查询原保存身份。
     *
     * @param prepared 原逻辑保存
     * @return 找到的已提交事实；为空仍表示结果未知
     */
    default CompletionStage<Optional<ProviderConfigurationResult>> providerConfigurationResult(
            PreparedProviderConfiguration prepared) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("请升级服务端以使用模型配置"));
    }
}
