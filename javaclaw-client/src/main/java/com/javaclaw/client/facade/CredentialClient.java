package com.javaclaw.client.facade;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.CredentialClearReceipt;
import com.javaclaw.api.CredentialMetadata;
import com.javaclaw.api.CredentialRef;
import com.javaclaw.api.VaultManagementReceipt;
import com.javaclaw.api.VaultStatus;
import com.javaclaw.client.CommandOptions;
import com.javaclaw.client.RpcClientConnection;
import com.javaclaw.protocol.CredentialRpcContracts;
import com.javaclaw.protocol.SealedSecret;
import com.javaclaw.protocol.SessionKeyInfo;
import com.javaclaw.protocol.SessionSecretSealer;

/** Secret Vault 状态、写入、轮换和清除的强类型 facade。 */
public final class CredentialClient {
    private final RpcClientConnection connection;
    private final SessionKeyInfo sessionKey;

    /**
     * 创建 facade。
     *
     * @param connection 已初始化连接
     * @param sessionKey initialize 返回的会话公钥
     */
    public CredentialClient(RpcClientConnection connection, SessionKeyInfo sessionKey) {
        this.connection = Objects.requireNonNull(connection, "connection");
        this.sessionKey = Objects.requireNonNull(sessionKey, "sessionKey");
    }

    /**
     * 读取脱敏 Vault 状态。
     *
     * @return READY 或 VAULT_LOCKED 状态
     */
    public VaultStatus status() {
        return connection.query("credential/status", Map.of(), VaultStatus.class);
    }

    /**
     * 系统凭据设施恢复后重新尝试解封主密钥。
     *
     * @return 刷新后的脱敏状态
     */
    public VaultStatus refresh() {
        return connection.query("vault/refresh", Map.of(), VaultStatus.class);
    }

    /**
     * 读取一个引用的非敏感元数据。
     *
     * @param reference Vault 引用
     * @return 引用仍有效时的元数据
     */
    public Optional<CredentialMetadata> read(CredentialRef reference) {
        return connection
                .query(
                        "credential/read",
                        new CredentialRpcContracts.ReadPayload(reference),
                        CredentialRpcContracts.ReadResult.class)
                .credential();
    }

    /**
     * 列出一个命名空间中的脱敏凭据元数据。
     *
     * @param namespace MCP、OAuth、Site 或 Browser 命名空间；Provider 不通过此通用目录暴露
     * @return 按 opaque ID 排序的不可变元数据列表
     */
    public List<CredentialMetadata> list(String namespace) {
        String checkedNamespace = namespace(namespace);
        return connection
                .query(
                        "credential/list",
                        new CredentialRpcContracts.ListPayload(checkedNamespace),
                        CredentialRpcContracts.ListResult.class)
                .credentials();
    }

    /**
     * 在进入 JSON-RPC 前封装并创建 Secret。
     *
     * @param namespace MCP、OAuth、Site 或 Browser 命名空间；Provider 必须使用复合命令
     * @param secret 敏感字符；调用方应在返回后清零
     * @param options expected revision 必须为 0；网络重试必须复用同一完整请求
     * @return 脱敏元数据
     */
    public CredentialMetadata create(String namespace, char[] secret, CommandOptions options) {
        String checkedNamespace = namespace(namespace);
        SealedSecret sealed = SessionSecretSealer.seal(
                sessionKey, purpose(checkedNamespace, "create"), Objects.requireNonNull(secret, "secret"));
        return connection.command(
                "credential/create",
                new CredentialRpcContracts.CreatePayload(checkedNamespace, sealed),
                options,
                CredentialMetadata.class);
    }

    /**
     * 保持 CredentialRef 不变并轮换 Secret。
     *
     * @param reference Vault 引用
     * @param secret 新敏感字符；调用方应在返回后清零
     * @param options expected revision 必须匹配当前版本
     * @return 新版本脱敏元数据
     */
    public CredentialMetadata rotate(CredentialRef reference, char[] secret, CommandOptions options) {
        CredentialRef checkedReference = genericReference(reference);
        SealedSecret sealed = SessionSecretSealer.seal(
                sessionKey, purpose(checkedReference.namespace(), "rotate"), Objects.requireNonNull(secret, "secret"));
        return connection.command(
                "credential/rotate",
                new CredentialRpcContracts.RotatePayload(checkedReference, sealed),
                options,
                CredentialMetadata.class);
    }

    /**
     * 清除 Secret 并使引用立即失效。
     *
     * @param reference Vault 引用
     * @param options expected revision 必须匹配当前版本
     * @return 幂等清除回执
     */
    public CredentialClearReceipt clear(CredentialRef reference, CommandOptions options) {
        CredentialRef checkedReference = genericReference(reference);
        return connection.command(
                "credential/clear",
                new CredentialRpcContracts.ClearPayload(checkedReference),
                options,
                CredentialClearReceipt.class);
    }

    /**
     * 原子轮换 Vault 主密钥，不改变 CredentialRef 或业务 revision。
     *
     * @param options expected revision 必须为 0
     * @return 可安全重放的脱敏回执
     */
    public VaultManagementReceipt rotateMasterKey(CommandOptions options) {
        return connection.command(
                "vault/masterKey/rotate",
                new CredentialRpcContracts.MasterKeyRotatePayload(),
                options,
                VaultManagementReceipt.class);
    }

    /**
     * 永久清除全部 Secret 并使旧 CredentialRef 失效。
     *
     * @param confirmation 必须精确为 {@code RESET VAULT}
     * @param options expected revision 必须为 0
     * @return 可安全重放的脱敏回执
     */
    public VaultManagementReceipt reset(String confirmation, CommandOptions options) {
        return connection.command(
                "vault/reset",
                new CredentialRpcContracts.ResetPayload(confirmation),
                options,
                VaultManagementReceipt.class);
    }

    private static String purpose(String namespace, String operation) {
        return "credential/" + namespace + '/' + operation;
    }

    private static String namespace(String value) {
        String checked = Objects.requireNonNull(value, "namespace").strip();
        if (!checked.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,239}")) {
            throw new IllegalArgumentException("namespace contains unsupported characters");
        }
        if ("provider".equals(checked)) {
            throw new IllegalArgumentException("Provider Secret must use ProviderClient composite methods");
        }
        return checked;
    }

    private static CredentialRef genericReference(CredentialRef reference) {
        CredentialRef checked = Objects.requireNonNull(reference, "reference");
        namespace(checked.namespace());
        return checked;
    }
}
