package com.javaclaw.server.rpc;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

import com.javaclaw.builtin.contracts.SiteAccountContracts;
import com.javaclaw.builtin.contracts.SiteAccountContracts.AccountProjection;
import com.javaclaw.builtin.contracts.SiteAccountContracts.AccountScope;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.ExtensionRpcContracts;
import com.javaclaw.server.persistence.CommandIdentity;
import com.javaclaw.server.site.account.SiteAccountService;

/** Site 账号秘密在通用扩展密封入口中的宿主适配器。 */
public final class SiteAccountSecretHandler implements ExtensionSecretRpcHandlers.Handler {
    private final SiteAccountService accounts;
    private final CanonicalJson json;
    private final Consumer<ExtensionRpcContracts.CallPayload> authorization;

    /**
     * 构造账号秘密适配器。
     *
     * @param accounts 权威账号服务
     * @param json 共享 JSON 编码器
     * @param authorization 组合根验证实时扩展状态和 Workspace 的操作
     */
    public SiteAccountSecretHandler(
            SiteAccountService accounts,
            CanonicalJson json,
            Consumer<ExtensionRpcContracts.CallPayload> authorization) {
        this.accounts = Objects.requireNonNull(accounts, "accounts");
        this.json = Objects.requireNonNull(json, "json");
        this.authorization = Objects.requireNonNull(authorization, "authorization");
    }

    @Override
    public void validate(ExtensionRpcContracts.CallPayload call) {
        authorization.accept(call);
        json.decode(call.payload(), SiteAccountContracts.CredentialRequest.class);
    }

    @Override
    public Optional<ExtensionRpcContracts.CallResult> recover(CommandIdentity identity) {
        return accounts.recover(identity).map(this::result);
    }

    @Override
    public ExtensionRpcContracts.CallResult execute(
            ExtensionRpcContracts.CallPayload call, CommandIdentity identity, byte[] plaintext) {
        SiteAccountContracts.CredentialRequest request =
                json.decode(call.payload(), SiteAccountContracts.CredentialRequest.class);
        AccountScope scope = new AccountScope(
                call.workspaceId(),
                request.selection().siteId(),
                request.selection().accountId());
        return result(accounts.setCredential(
                scope,
                request.expectedSecurityRevision(),
                request.expectedSiteAuthorityRevision(),
                identity,
                plaintext));
    }

    private ExtensionRpcContracts.CallResult result(AccountProjection account) {
        return new ExtensionRpcContracts.CallResult(json.encode(account), account.revision());
    }
}
