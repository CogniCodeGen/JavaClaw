package com.javaclaw.client.jpms;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.ProviderAdapter;
import com.javaclaw.api.ProviderAdapterOptions;
import com.javaclaw.api.ProviderAuthentication;
import com.javaclaw.api.ProviderConfiguration;
import com.javaclaw.api.ProviderConnectionSpec;
import com.javaclaw.api.ProviderCredentialChange;
import com.javaclaw.api.ProviderLifecycle;
import com.javaclaw.api.ProviderModelPurpose;
import com.javaclaw.api.ProviderModelSpec;
import com.javaclaw.client.CommandOptions;
import com.javaclaw.client.RpcClientConnection;
import com.javaclaw.client.facade.PreparedProviderConfiguration;
import com.javaclaw.client.facade.ProviderConfigurationClient;
import com.javaclaw.client.testkit.ScriptedRpcConnection;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.ProviderConfigurationRpcContracts;
import com.javaclaw.protocol.SessionSecretChannel;

/** 在独立 JVM 的命名 SDK 模块中检查最终保存请求，所有秘密均为本地测试数据。 */
public final class ProviderConfigurationModuleProbe {
    private ProviderConfigurationModuleProbe() {}

    /**
     * 检查保留与替换凭据的准备阶段，不允许发送任何 RPC。
     *
     * @param arguments 不使用启动参数
     * @throws Exception 本地测试连接无法释放时抛出
     */
    public static void main(String[] arguments) throws Exception {
        requireNamedModules();
        CanonicalJson json = new CanonicalJson();
        try (SessionSecretChannel secrets = SessionSecretChannel.open();
                RpcClientConnection connection = new RpcClientConnection(
                        new ScriptedRpcConnection(request -> {
                            throw new AssertionError("准备配置不能发送请求");
                        }),
                        json,
                        ignored -> {})) {
            ProviderConfigurationClient client = new ProviderConfigurationClient(connection, secrets.publicKey());
            for (ProviderCredentialChange change :
                    List.of(ProviderCredentialChange.KEEP, ProviderCredentialChange.REPLACE)) {
                verifyPrepared(client, secrets, change);
            }
            verifyFailureClearsSecret(client);
        }
    }

    private static void requireNamedModules() {
        Module client = PreparedProviderConfiguration.class.getModule();
        Module jackson = ModuleLayer.boot()
                .findModule("com.fasterxml.jackson.databind")
                .orElseThrow(() -> new AssertionError("Jackson 必须作为命名模块加载"));
        if (!client.isNamed() || !CanonicalJson.class.getModule().isNamed()) {
            throw new AssertionError("Client 和 Protocol 必须作为命名模块加载");
        }
        if (client.isOpen("com.javaclaw.client.facade", jackson)) {
            throw new AssertionError("准备配置不得依赖向 Jackson 开放 SDK 实现反射");
        }
    }

    private static void verifyPrepared(
            ProviderConfigurationClient client, SessionSecretChannel secrets, ProviderCredentialChange change) {
        char[] input = change == ProviderCredentialChange.REPLACE ? "module-test-key".toCharArray() : new char[0];
        PreparedProviderConfiguration prepared =
                client.prepare(configuration(change, 0), input, new CommandOptions("save", 0));
        requireCleared(input);
        verifyDigest(prepared, change);
        verifySealedSecret(prepared, secrets, change);
    }

    private static void verifyDigest(PreparedProviderConfiguration prepared, ProviderCredentialChange change) {
        // 直接检查既有规范 wire 文本，避免预期值也使用同一种反射实现而漏报契约变化。
        CanonicalPayload digestInput = new CanonicalPayload("{\"expectedRevision\":0,\"payload\":"
                + prepared.command().payload().json() + "}");
        if (!digestInput.sha256().equals(prepared.requestDigest())) {
            throw new AssertionError("摘要必须保持 expectedRevision 与原始 payload 的规范编码");
        }
        PreparedProviderConfiguration same =
                new PreparedProviderConfiguration(prepared.payload(), new CommandOptions("other", 0));
        if (!prepared.requestDigest().equals(same.requestDigest())
                || !prepared.command().payload().equals(same.command().payload())) {
            throw new AssertionError("摘要不包含幂等键且不能重新密封冻结 payload");
        }
        PreparedProviderConfiguration revised = new PreparedProviderConfiguration(
                new ProviderConfigurationRpcContracts.SavePayload(
                        configuration(change, 1), prepared.payload().secret()),
                new CommandOptions("revised", 1));
        if (prepared.requestDigest().equals(revised.requestDigest())) {
            throw new AssertionError("修改版本必须改变摘要");
        }
    }

    private static void verifySealedSecret(
            PreparedProviderConfiguration prepared, SessionSecretChannel secrets, ProviderCredentialChange change) {
        if (change == ProviderCredentialChange.REPLACE) {
            byte[] clear = secrets.unseal(
                    prepared.payload().secret().orElseThrow(), ProviderConfigurationRpcContracts.SAVE_PURPOSE);
            try {
                if (!Arrays.equals("module-test-key".getBytes(StandardCharsets.UTF_8), clear)) {
                    throw new AssertionError("保存密文必须使用最终保存用途封装");
                }
            } finally {
                Arrays.fill(clear, (byte) 0);
            }
        } else if (prepared.payload().secret().isPresent()) {
            throw new AssertionError("保留凭据不得携带新密文");
        }
    }

    private static void verifyFailureClearsSecret(ProviderConfigurationClient client) {
        char[] input = "failed-input".toCharArray();
        try {
            client.prepare(
                    configuration(ProviderCredentialChange.REPLACE, 0), input, new CommandOptions("mismatch", 1));
            throw new AssertionError("配置版本不匹配应被拒绝");
        } catch (IllegalArgumentException expected) {
            requireCleared(input);
        }
    }

    private static void requireCleared(char[] input) {
        if (!Arrays.equals(new char[input.length], input)) {
            throw new AssertionError("准备成功或失败均必须清零调用方秘密数组");
        }
    }

    private static ProviderConfiguration configuration(ProviderCredentialChange change, long revision) {
        ProviderConnectionSpec connection = new ProviderConnectionSpec(
                "模块测试",
                ProviderAdapter.OPENAI_COMPATIBLE,
                Optional.of(URI.create("https://model.example/v1")),
                ProviderAuthentication.API_KEY,
                Duration.ofSeconds(60),
                0,
                ProviderAdapterOptions.defaults(ProviderAdapter.OPENAI_COMPATIBLE));
        return new ProviderConfiguration(
                "module-provider",
                revision,
                connection,
                List.of(new ProviderModelSpec("chat", "对话", Set.of(ProviderModelPurpose.CHAT), OptionalInt.empty())),
                ProviderLifecycle.ACTIVE,
                change,
                0);
    }
}
