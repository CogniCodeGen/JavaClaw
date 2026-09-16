package com.javaclaw.server.site.account;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.CredentialMetadata;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.builtin.contracts.SiteAccountContracts.AccountProjection;
import com.javaclaw.builtin.contracts.SiteAccountContracts.AccountSavedTimes;
import com.javaclaw.builtin.contracts.SiteAccountContracts.AccountScope;
import com.javaclaw.builtin.contracts.SiteContracts;
import com.javaclaw.extension.spi.ExtensionTransaction;
import com.javaclaw.extension.spi.VersionedDocument;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.server.persistence.PersistenceException;
import com.javaclaw.server.security.vault.SecretVaultService;

/** 现有 Site 托管文档中的账号记录、索引与单凭据兼容迁移。 */
final class SiteAccountDocuments {
    private final CanonicalJson json;
    private final Clock clock;
    private final SecretVaultService vault;

    SiteAccountDocuments(CanonicalJson json, Clock clock, SecretVaultService vault) {
        this.json = json;
        this.clock = clock;
        this.vault = vault;
    }

    SiteContracts.Site site(ExtensionTransaction transaction, WorkspaceId workspace, String siteId) {
        VersionedDocument stored = transaction
                .get("documents." + workspace.value(), siteId)
                .orElseThrow(() -> PersistenceException.invalidRequest("网站不存在"));
        SiteContracts.Site site = json.decode(stored.payload(), SiteContracts.Site.class);
        if (site.revision() != stored.revision()) {
            throw new IllegalStateException("网站记录版本不一致");
        }
        return site;
    }

    Index migrate(ExtensionTransaction transaction, WorkspaceId workspace, SiteContracts.Site site) {
        Optional<VersionedDocument> existing = transaction.get(indexCollection(workspace), site.id());
        if (existing.isPresent()) {
            return json.decode(existing.orElseThrow().payload(), Index.class);
        }
        Optional<String> defaultAccount = Optional.empty();
        if (site.credential().kind() == SiteContracts.CredentialKind.BROWSER_STORAGE) {
            Optional<CredentialMetadata> state =
                    vault.metadata(site.credential().reference().orElseThrow());
            if (state.isPresent()) {
                String id = "legacy-"
                        + json.encode(new SiteKey(workspace, site.id()))
                                .sha256()
                                .substring(0, 32);
                Account account = new Account(
                        id,
                        site.id(),
                        1,
                        1,
                        1,
                        site.authorityRevision(),
                        "默认账号",
                        true,
                        Optional.empty(),
                        state,
                        clock.instant());
                transaction.put(collection(workspace, site.id()), id, 0, json.encode(account));
                defaultAccount = Optional.of(id);
            }
        }
        // 显式索引同时是迁移标记；清空账号后不能再次从历史单凭据生成账号。
        Index index = new Index(1, defaultAccount);
        transaction.put(indexCollection(workspace), site.id(), 0, json.encode(index));
        return index;
    }

    List<Account> list(ExtensionTransaction transaction, WorkspaceId workspace, String siteId) {
        return transaction.list(collection(workspace, siteId), "", 500).stream()
                .map(value -> json.decode(value.payload(), Account.class))
                .toList();
    }

    Account require(ExtensionTransaction transaction, AccountScope scope) {
        VersionedDocument stored = transaction
                .get(collection(scope.workspaceId(), scope.siteId()), scope.accountId())
                .orElseThrow(() -> PersistenceException.invalidRequest("账号不存在或已删除"));
        Account account = json.decode(stored.payload(), Account.class);
        if (account.revision() != stored.revision() || !account.siteId().equals(scope.siteId())) {
            throw new IllegalStateException("账号文档与所有权不一致");
        }
        return account;
    }

    void write(ExtensionTransaction transaction, WorkspaceId workspace, Account next, long expectedRevision) {
        transaction.put(collection(workspace, next.siteId()), next.accountId(), expectedRevision, json.encode(next));
    }

    void delete(ExtensionTransaction transaction, AccountScope scope, long expectedRevision) {
        transaction.delete(collection(scope.workspaceId(), scope.siteId()), scope.accountId(), expectedRevision);
    }

    Index setDefault(ExtensionTransaction transaction, WorkspaceId workspace, String siteId, Optional<String> account) {
        Index current = json.decode(
                transaction
                        .get(indexCollection(workspace), siteId)
                        .orElseThrow()
                        .payload(),
                Index.class);
        Index next = new Index(Math.addExact(current.revision(), 1), account);
        transaction.put(indexCollection(workspace), siteId, current.revision(), json.encode(next));
        return next;
    }

    AccountProjection projection(Account account, Index index) {
        return new AccountProjection(
                account.accountId(),
                account.siteId(),
                account.revision(),
                account.securityRevision(),
                account.stateRevision(),
                account.name(),
                account.enabled(),
                index.defaultAccount().filter(account.accountId()::equals).isPresent(),
                account.loginSecret().isPresent(),
                account.browserState().isPresent(),
                account.updatedAt());
    }

    AccountSavedTimes savedTimes(Account account) {
        // 元数据随账号和密文原子提交；读取它不需要解密，也不依赖账号名称的变更时间。
        return new AccountSavedTimes(
                account.loginSecret().map(CredentialMetadata::updatedAt),
                account.browserState().map(CredentialMetadata::updatedAt));
    }

    private String collection(WorkspaceId workspace, String siteId) {
        return "accounts." + workspace.value() + '.'
                + json.encode(new SiteKey(workspace, siteId)).sha256().substring(0, 32);
    }

    private static String indexCollection(WorkspaceId workspace) {
        return "account-index." + workspace.value();
    }

    record Index(long revision, Optional<String> defaultAccount) {}

    record SiteKey(WorkspaceId workspaceId, String siteId) {}

    record Account(
            String accountId,
            String siteId,
            long revision,
            long securityRevision,
            long stateRevision,
            long siteAuthorityRevision,
            String name,
            boolean enabled,
            Optional<CredentialMetadata> loginSecret,
            Optional<CredentialMetadata> browserState,
            Instant updatedAt) {
        Account {
            Objects.requireNonNull(accountId, "accountId");
            Objects.requireNonNull(siteId, "siteId");
            Objects.requireNonNull(name, "name");
            loginSecret = Objects.requireNonNull(loginSecret, "loginSecret");
            browserState = Objects.requireNonNull(browserState, "browserState");
            Objects.requireNonNull(updatedAt, "updatedAt");
        }

        Account metadata(String nextName, boolean nextEnabled, Instant now) {
            return new Account(
                    accountId,
                    siteId,
                    revision + 1,
                    securityRevision + (enabled == nextEnabled ? 0 : 1),
                    stateRevision,
                    siteAuthorityRevision,
                    nextName,
                    nextEnabled,
                    loginSecret,
                    browserState,
                    now);
        }

        Account login(
                CredentialMetadata credential, Optional<CredentialMetadata> state, long siteAuthority, Instant now) {
            return new Account(
                    accountId,
                    siteId,
                    revision + 1,
                    securityRevision + 1,
                    stateRevision + 1,
                    siteAuthority,
                    name,
                    enabled,
                    Optional.of(credential),
                    state,
                    now);
        }

        Account state(CredentialMetadata state, Instant now) {
            return new Account(
                    accountId,
                    siteId,
                    revision + 1,
                    securityRevision,
                    stateRevision + 1,
                    siteAuthorityRevision,
                    name,
                    enabled,
                    loginSecret,
                    Optional.of(state),
                    now);
        }

        Account logout(Instant now) {
            return new Account(
                    accountId,
                    siteId,
                    revision + 1,
                    securityRevision + 1,
                    stateRevision + 1,
                    siteAuthorityRevision,
                    name,
                    enabled,
                    loginSecret,
                    Optional.empty(),
                    now);
        }
    }
}
