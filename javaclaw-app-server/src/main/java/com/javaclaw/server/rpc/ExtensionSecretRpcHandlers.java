package com.javaclaw.server.rpc;

import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.ExtensionRpcContracts;
import com.javaclaw.protocol.ExtensionSecretRpcContracts;
import com.javaclaw.protocol.SessionSecretChannel;
import com.javaclaw.protocol.WriteCommand;
import com.javaclaw.server.persistence.CommandIdentity;

/** 仅向组合根明确注册的内置领域处理器提供私有秘密回调。 */
public final class ExtensionSecretRpcHandlers {
    private final Map<String, Handler> handlers;
    private final CanonicalJson json;

    /**
     * 构造密封入口，不使用反射或扩展返回值动态注册解密处理器。
     *
     * @param handlers 键为 extensionId/operation 的显式宿主处理器
     * @param json 共享 JSON 编码器
     */
    public ExtensionSecretRpcHandlers(Map<String, Handler> handlers, CanonicalJson json) {
        this.handlers = Map.copyOf(handlers);
        this.json = Objects.requireNonNull(json, "json");
    }

    /**
     * 注册唯一通用平台秘密路由。
     *
     * @param routes 组合根路由
     * @return 原路由 builder
     */
    public RpcRouter.Builder register(RpcRouter.Builder routes) {
        return routes.registerSession(ExtensionSecretRpcContracts.METHOD, this::command);
    }

    private CanonicalPayload command(CanonicalPayload parameters, SessionSecretChannel secrets) {
        WriteCommand command = json.decode(parameters, WriteCommand.class);
        ExtensionSecretRpcContracts.Payload payload =
                json.decode(command.payload(), ExtensionSecretRpcContracts.Payload.class);
        ExtensionRpcContracts.CallPayload call = payload.call();
        Handler handler = Optional.ofNullable(handlers.get(call.extensionId() + '/' + call.operation()))
                .orElseThrow(() -> new SecurityException("该扩展操作没有宿主秘密处理器"));
        CommandIdentity identity = CommandIdentity.from(ExtensionSecretRpcContracts.METHOD, command, json);
        handler.validate(call);
        Optional<ExtensionRpcContracts.CallResult> replay = handler.recover(identity);
        if (replay.isPresent()) {
            return json.encode(replay.orElseThrow());
        }
        byte[] plaintext = secrets.unseal(
                payload.secret(), ExtensionSecretRpcContracts.purpose(call, identity.expectedRevision()));
        try {
            return json.encode(handler.execute(call, identity, plaintext));
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }
    }

    /** 组合根注册的秘密领域处理器；不得将明文转成普通 DTO 或事件。 */
    public interface Handler {
        /**
         * 解封和幂等恢复前验证当前扩展启用状态及资源所有权。
         *
         * @param call 领域元数据
         */
        void validate(ExtensionRpcContracts.CallPayload call);

        /**
         * 在解封前恢复已提交命令，避免重试消费已使用的会话密文。
         *
         * @param identity 完整命令身份
         * @return 脱敏既有回执
         */
        Optional<ExtensionRpcContracts.CallResult> recover(CommandIdentity identity);

        /**
         * 在宿主内消费私有明文并原子提交领域状态。
         *
         * @param call 领域元数据
         * @param identity 完整命令身份
         * @param plaintext 回调返回后由入口清零的临时字节
         * @return 不含秘密的结果
         */
        ExtensionRpcContracts.CallResult execute(
                ExtensionRpcContracts.CallPayload call, CommandIdentity identity, byte[] plaintext);
    }
}
