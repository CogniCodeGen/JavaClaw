package com.javaclaw.server.site.account;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.CredentialMetadata;
import com.javaclaw.builtin.contracts.SiteAccountContracts;
import com.javaclaw.builtin.contracts.SiteAccountContracts.AccountProjection;
import com.javaclaw.builtin.contracts.SiteAccountContracts.AccountScope;
import com.javaclaw.builtin.contracts.SiteContracts;
import com.javaclaw.server.persistence.CommandIdentity;
import com.javaclaw.server.security.vault.ScopedCredentialVault;
import com.javaclaw.server.security.vault.ScopedCredentialVault.Mutation;
import com.javaclaw.server.site.account.SiteAccountDocuments.Account;

/** 账号秘密复合写入；调用方持有账号锁，全部 Vault 密文与账号版本在单个 H2 事务内提交。 */
final class SiteAccountSecretWrites {
    private final SiteAccountService accounts;
    private final SiteAccountDocuments documents;
    private final ScopedCredentialVault secrets;
    private final Clock clock;

    SiteAccountSecretWrites(
            SiteAccountService accounts, SiteAccountDocuments documents, ScopedCredentialVault secrets, Clock clock) {
        this.accounts = accounts;
        this.documents = documents;
        this.secrets = secrets;
        this.clock = clock;
    }

    AccountProjection credential(
            AccountScope scope, long security, long authority, CommandIdentity identity, byte[] bytes) {
        return write(new Confirmation(scope, security, authority), identity, bytes, Optional.empty());
    }

    AccountProjection login(
            SiteAccountContracts.StateLease target, CommandIdentity identity, byte[] credentials, byte[] state) {
        return write(
                new Confirmation(target.scope(), target.securityRevision(), target.siteAuthorityRevision()),
                identity,
                credentials,
                Optional.of(state));
    }

    private AccountProjection write(
            Confirmation target, CommandIdentity identity, byte[] credentials, Optional<byte[]> state) {
        try {
            Optional<AccountProjection> replay = accounts.recover(identity);
            if (replay.isPresent()) {
                return replay.orElseThrow();
            }
            SiteAccountSecretBytes.validate(credentials);
            state.ifPresent(SiteAccountSecretBytes::validateStorage);
            Account current = accounts.require(target.scope(), identity.expectedRevision(), target.security());
            Mutation password =
                    secrets.prepare(SiteAccountContracts.LOGIN_NAMESPACE, current.loginSecret(), credentials);
            List<Mutation> mutations = new ArrayList<>(List.of(password));
            try {
                Optional<CredentialMetadata> savedState = prepareState(current, state, mutations);
                AccountProjection result = accounts.commit(identity, mutations, tx -> {
                    Account locked = accounts.require(tx, target.scope(), current.revision(), target.security());
                    SiteContracts.Site site = documents.site(
                            tx, target.scope().workspaceId(), target.scope().siteId());
                    long authority = target.authority() < 0 ? locked.siteAuthorityRevision() : target.authority();
                    if (site.authorityRevision() != authority) {
                        throw new SecurityException("网站 Origin 或权限已改变，请刷新并重新确认密码保存");
                    }
                    Account next = locked.login(password.metadata(), savedState, authority, clock.instant());
                    documents.write(tx, target.scope().workspaceId(), next, locked.revision());
                    return accounts.projection(tx, target.scope(), next);
                });
                accounts.revoke(target.scope());
                return result;
            } finally {
                mutations.forEach(Mutation::close);
            }
        } finally {
            Arrays.fill(Objects.requireNonNull(credentials, "credentials"), (byte) 0);
            state.ifPresent(bytes -> Arrays.fill(bytes, (byte) 0));
        }
    }

    private Optional<CredentialMetadata> prepareState(
            Account current, Optional<byte[]> state, List<Mutation> mutations) {
        if (state.isPresent()) {
            Mutation saved = secrets.prepare(
                    SiteContracts.BROWSER_CREDENTIAL_NAMESPACE, current.browserState(), state.orElseThrow());
            mutations.add(saved);
            return Optional.of(saved.metadata());
        }
        current.browserState().ifPresent(previous -> mutations.add(secrets.prepareClear(previous)));
        return Optional.empty();
    }

    private record Confirmation(AccountScope scope, long security, long authority) {}
}
