package com.javaclaw.client.facade;

import java.util.Map;
import java.util.Objects;

import com.javaclaw.client.CommandOptions;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.ProviderConfigurationRpcContracts;
import com.javaclaw.protocol.WriteCommand;

/**
 * 冻结一次最终提交的密文、幂等身份与摘要；不持有明文秘密。
 *
 * <p>结果不明时必须用本对象查询原回执；不可重新密封并自动创建另一份保存请求。
 */
public final class PreparedProviderConfiguration {
    private final ProviderConfigurationRpcContracts.SavePayload payload;
    private final CommandOptions options;
    private final WriteCommand command;
    private final String requestDigest;

    /**
     * 冻结完整请求；密文必须已经使用最终保存专用用途封装。
     *
     * @param payload 完整配置及可选密文
     * @param options 本次逻辑保存的唯一身份
     */
    public PreparedProviderConfiguration(
            ProviderConfigurationRpcContracts.SavePayload payload, CommandOptions options) {
        this.payload = Objects.requireNonNull(payload, "payload");
        this.options = Objects.requireNonNull(options, "options");
        if (payload.configuration().expectedRevision() != options.expectedRevision()) {
            throw new IllegalArgumentException("configuration expected revision must match command");
        }
        CanonicalJson json = new CanonicalJson();
        command = new WriteCommand(options.idempotencyKey(), options.expectedRevision(), json.encode(payload));
        // 摘要保持服务端的两个 wire 字段；标准 Map 避免在命名模块中反射 SDK 私有类型。
        requestDigest = json.encode(
                        Map.of("expectedRevision", command.expectedRevision(), "payload", command.payload()))
                .sha256();
    }

    /** @return 不含明文的完整业务请求 */
    public ProviderConfigurationRpcContracts.SavePayload payload() {
        return payload;
    }

    /** @return 原逻辑提交身份 */
    public CommandOptions options() {
        return options;
    }

    /** @return 已冻结的原始 wire command */
    public WriteCommand command() {
        return command;
    }

    /** @return 与服务端 CommandIdentity 相同算法的 SHA-256 摘要 */
    public String requestDigest() {
        return requestDigest;
    }
}
