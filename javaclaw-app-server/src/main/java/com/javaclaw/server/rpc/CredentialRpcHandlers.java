package com.javaclaw.server.rpc;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.CredentialClearReceipt;
import com.javaclaw.api.CredentialMetadata;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.CredentialRpcContracts;
import com.javaclaw.protocol.SessionSecretChannel;
import com.javaclaw.protocol.WriteCommand;
import com.javaclaw.server.persistence.CommandIdentity;
import com.javaclaw.server.security.vault.SecretVaultService;

/** Secret Vault 的强类型、会话加密 RPC 处理器。 */
public final class CredentialRpcHandlers {
    private final SecretVaultService vault;
    private final CanonicalJson json;

    /**
     * 创建处理器。
     *
     * @param vault Vault 应用服务
     * @param json 规范 JSON codec
     */
    public CredentialRpcHandlers(SecretVaultService vault, CanonicalJson json) {
        this.vault = Objects.requireNonNull(vault, "vault");
        this.json = Objects.requireNonNull(json, "json");
    }

    /**
     * 注册 Vault Core 方法。
     *
     * @param routes 组合根路由 Builder
     * @return 当前 Builder
     */
    public RpcRouter.Builder register(RpcRouter.Builder routes) {
        return routes.register("credential/status", this::status)
                .register("credential/read", this::read)
                .register("credential/list", this::list)
                .registerSession("credential/create", this::create)
                .registerSession("credential/rotate", this::rotate)
                .register("credential/clear", this::clear)
                .register("vault/refresh", this::refresh)
                .register("vault/masterKey/rotate", this::rotateMasterKey)
                .register("vault/reset", this::reset);
    }

    private CanonicalPayload status(CanonicalPayload ignored) {
        return json.encode(vault.status());
    }

    private CanonicalPayload read(CanonicalPayload params) {
        CredentialRpcContracts.ReadPayload payload = json.decode(params, CredentialRpcContracts.ReadPayload.class);
        return json.encode(new CredentialRpcContracts.ReadResult(vault.metadata(payload.reference())));
    }

    private CanonicalPayload list(CanonicalPayload params) {
        CredentialRpcContracts.ListPayload payload = json.decode(params, CredentialRpcContracts.ListPayload.class);
        rejectProviderNamespace(payload.namespace());
        return json.encode(new CredentialRpcContracts.ListResult(vault.listMetadata(payload.namespace())));
    }

    private CanonicalPayload create(CanonicalPayload params, SessionSecretChannel secrets) {
        WriteCommand command = json.decode(params, WriteCommand.class);
        CommandIdentity identity = CommandIdentity.from("credential/create", command, json);
        Optional<CredentialMetadata> recovered = vault.recoverCredential(identity);
        if (recovered.isPresent()) {
            return json.encode(recovered.orElseThrow());
        }
        CredentialRpcContracts.CreatePayload payload =
                json.decode(command.payload(), CredentialRpcContracts.CreatePayload.class);
        rejectProviderNamespace(payload.namespace());
        byte[] plaintext = secrets.unseal(payload.secret(), purpose(payload.namespace(), "create"));
        try {
            return json.encode(vault.create(identity, payload.namespace(), plaintext));
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }
    }

    private CanonicalPayload rotate(CanonicalPayload params, SessionSecretChannel secrets) {
        WriteCommand command = json.decode(params, WriteCommand.class);
        CommandIdentity identity = CommandIdentity.from("credential/rotate", command, json);
        Optional<CredentialMetadata> recovered = vault.recoverCredential(identity);
        if (recovered.isPresent()) {
            return json.encode(recovered.orElseThrow());
        }
        CredentialRpcContracts.RotatePayload payload =
                json.decode(command.payload(), CredentialRpcContracts.RotatePayload.class);
        rejectProviderNamespace(payload.reference().namespace());
        byte[] plaintext =
                secrets.unseal(payload.secret(), purpose(payload.reference().namespace(), "rotate"));
        try {
            return json.encode(vault.rotate(identity, payload.reference(), plaintext));
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }
    }

    private CanonicalPayload clear(CanonicalPayload params) {
        WriteCommand command = json.decode(params, WriteCommand.class);
        CommandIdentity identity = CommandIdentity.from("credential/clear", command, json);
        Optional<CredentialClearReceipt> recovered = vault.recoverClear(identity);
        if (recovered.isPresent()) {
            return json.encode(recovered.orElseThrow());
        }
        CredentialRpcContracts.ClearPayload payload =
                json.decode(command.payload(), CredentialRpcContracts.ClearPayload.class);
        rejectProviderNamespace(payload.reference().namespace());
        return json.encode(vault.clear(identity, payload.reference()));
    }

    private CanonicalPayload refresh(CanonicalPayload ignored) {
        return json.encode(vault.refresh());
    }

    private CanonicalPayload rotateMasterKey(CanonicalPayload params) {
        WriteCommand command = json.decode(params, WriteCommand.class);
        json.decode(command.payload(), CredentialRpcContracts.MasterKeyRotatePayload.class);
        return json.encode(vault.rotateMasterKey(CommandIdentity.from("vault/masterKey/rotate", command, json)));
    }

    private CanonicalPayload reset(CanonicalPayload params) {
        WriteCommand command = json.decode(params, WriteCommand.class);
        json.decode(command.payload(), CredentialRpcContracts.ResetPayload.class);
        return json.encode(vault.reset(CommandIdentity.from("vault/reset", command, json)));
    }

    private static String purpose(String namespace, String operation) {
        return "credential/" + namespace + '/' + operation;
    }

    private static void rejectProviderNamespace(String namespace) {
        if ("provider".equals(namespace)) {
            throw new IllegalArgumentException("Provider Secret 只能通过 provider/credential 原子复合命令变更");
        }
    }
}
