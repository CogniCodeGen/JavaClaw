package com.javaclaw.server.site.account;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.CredentialMetadata;
import com.javaclaw.api.CredentialRef;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.builtin.contracts.BuiltinExtensionIds;
import com.javaclaw.builtin.contracts.SiteAccountContracts;
import com.javaclaw.builtin.contracts.SiteAccountContracts.AccountList;
import com.javaclaw.builtin.contracts.SiteAccountContracts.AccountProjection;
import com.javaclaw.builtin.contracts.SiteAccountContracts.AccountScope;
import com.javaclaw.builtin.contracts.SiteAccountContracts.StateLease;
import com.javaclaw.builtin.contracts.SiteContracts;
import com.javaclaw.extension.spi.ExtensionId;
import com.javaclaw.extension.spi.ExtensionTransaction;
import com.javaclaw.extension.spi.IsolatedServiceInvocation;
import com.javaclaw.extension.spi.ManagedExtensionStore.TransactionWork;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.server.persistence.CommandIdentity;
import com.javaclaw.server.persistence.H2Database;
import com.javaclaw.server.persistence.H2ManagedExtensionStore;
import com.javaclaw.server.persistence.PersistenceException;
import com.javaclaw.server.security.vault.ScopedCredentialVault;
import com.javaclaw.server.security.vault.ScopedCredentialVault.Mutation;
import com.javaclaw.server.security.vault.SecretOperation;
import com.javaclaw.server.security.vault.SecretVaultService;
import com.javaclaw.server.security.vault.VaultException;
import com.javaclaw.server.site.account.SiteAccountDocuments.Account;
import com.javaclaw.server.site.account.SiteAccountDocuments.Index;

/**
 * Site 托管账号与现有 Vault 的复合应用服务。
 *
 * <p>命令在实例内串行，领域版本和 Vault 密文以同一 H2 事务提交。登录态有唯一写租约， 保存时重新校验网站、安全版本和当前租约；自动保存不会递增安全版本，撤权后的迟到保存永不恢复账号。
 */
public final class SiteAccountService {
    private static final ExtensionId SITE = new ExtensionId(BuiltinExtensionIds.SITE);
    private final H2ManagedExtensionStore store;
    private final SiteAccountDocuments documents;
    private final SiteAccountSecretWrites loginWrites;
    private final SiteRegistrationStore registration;
    private final SecretVaultService vault;
    private final ScopedCredentialVault secrets;
    private final CanonicalJson json;
    private final Clock clock;
    private final ConcurrentHashMap<AccountScope, StateLease> leases = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<Consumer<AccountScope>> securityListeners = new CopyOnWriteArrayList<>();
    private final AtomicLong generations = new AtomicLong();
    private final ConcurrentHashMap<AccountScope, Set<CredentialRef>> watchedReferences = new ConcurrentHashMap<>();
    private final ThreadLocal<Boolean> ownSecretMutation = ThreadLocal.withInitial(() -> false);

    /**
     * 使用现有数据库和 Vault 构造网站账号服务，不创建独立存储。
     *
     * @param database data-v6 数据库
     * @param vault 宿主密钥库
     * @param json 共享规范 JSON 编码器
     * @param clock 平台时钟
     */
    public SiteAccountService(H2Database database, SecretVaultService vault, CanonicalJson json, Clock clock) {
        store = new H2ManagedExtensionStore(database, clock);
        this.vault = Objects.requireNonNull(vault, "vault");
        secrets = vault.scopedCredentials();
        this.json = Objects.requireNonNull(json, "json");
        this.clock = Objects.requireNonNull(clock, "clock");
        documents = new SiteAccountDocuments(json, clock, vault);
        loginWrites = new SiteAccountSecretWrites(this, documents, secrets, clock);
        registration = new SiteRegistrationStore(store, documents, secrets, json, clock);
        secrets.onChange(references -> {
            if (ownSecretMutation.get()) {
                return;
            }
            if (references.isEmpty()) {
                List<AccountScope> affected = List.copyOf(watchedReferences.keySet());
                leases.clear();
                affected.forEach(this::notifySecurity);
            } else {
                watchedReferences.forEach((scope, owned) -> {
                    if (owned.stream().anyMatch(references::contains)) {
                        revoke(scope);
                    }
                });
            }
        });
    }

    /** @return 与当前账号共用数据库和 Vault 的网站登记复合写入端口 */
    public SiteRegistrationStore registrations() {
        return registration;
    }

    /**
     * 列出网站账号，并幂等迁移旧 BROWSER_STORAGE 默认账号。
     *
     * @param workspace 所属 Workspace
     * @param siteId 网站 ID
     * @return 不含秘密或 Vault 引用的账号列表
     */
    public synchronized AccountList list(WorkspaceId workspace, String siteId) {
        return transaction(tx -> {
            SiteContracts.Site site = documents.site(tx, workspace, siteId);
            Index index = documents.migrate(tx, workspace, site);
            List<Account> accounts = documents.list(tx, workspace, siteId);
            return new AccountList(
                    accounts.stream()
                            .map(account -> documents.projection(account, index))
                            .toList(),
                    accounts.stream().collect(Collectors.toUnmodifiableMap(Account::accountId, documents::savedTimes)));
        });
    }

    /**
     * 在现有 IsolatedServicePort 中处理 Site 的账号管理任务。
     *
     * @param invocation 已绑定可信 Workspace 的扩展调用
     * @return 非敏感领域投影
     */
    public CanonicalPayload invoke(IsolatedServiceInvocation invocation) {
        if (!SITE.equals(invocation.caller()) || !SiteAccountContracts.SERVICE.equals(invocation.serviceId())) {
            throw new SecurityException("账号服务只允许 Site 扩展调用");
        }
        invocation.cancellation().throwIfCancelled();
        SiteAccountContracts.ServiceRequest request =
                json.decode(invocation.request(), SiteAccountContracts.ServiceRequest.class);
        if (request.operation().equals("account/list")) {
            String site = json.decode(request.payload(), SiteAccountContracts.ListRequest.class)
                    .siteId();
            return json.encode(list(invocation.workspaceId(), site));
        }
        String method = "site/" + invocation.workspaceId().value() + '/' + request.operation();
        CommandIdentity identity = new CommandIdentity(
                method,
                request.idempotencyKey(),
                request.expectedRevision(),
                json.encode(new CommandBinding(invocation.workspaceId(), request))
                        .sha256());
        return json.encode(command(invocation.workspaceId(), request.operation(), request.payload(), identity));
    }

    /**
     * 通过 Site 的显式领域命令管理账号。
     *
     * @param workspace 可信调用上下文中的 Workspace
     * @param operation account/create、update、default、logout 或 delete
     * @param payload 仅包含账号元数据
     * @param identity 完整幂等命令身份
     * @return 已提交的账号脱敏结果
     */
    public synchronized AccountProjection command(
            WorkspaceId workspace, String operation, CanonicalPayload payload, CommandIdentity identity) {
        Optional<AccountProjection> replay = recover(identity);
        if (replay.isPresent()) {
            return replay.orElseThrow();
        }
        return switch (operation) {
            case "account/create" ->
                create(workspace, json.decode(payload, SiteAccountContracts.CreateRequest.class), identity);
            case "account/update" ->
                update(workspace, json.decode(payload, SiteAccountContracts.UpdateRequest.class), identity);
            case "account/default", "account/logout", "account/delete" ->
                control(workspace, json.decode(payload, SiteAccountContracts.Selection.class), operation, identity);
            default -> throw PersistenceException.invalidRequest("未知网站账号命令");
        };
    }

    /**
     * 原子保存私有通道提交的用户名密码包并撤销旧登录态。
     *
     * @param scope 可信所有权
     * @param expectedSecurityRevision 用户确认的安全版本
     * @param identity 幂等身份；expected revision 为账号文档版本
     * @param plaintext 用户名 UTF-8、一个 NUL、密码 UTF-8；本方法接管清零责任
     * @return 账号脱敏状态
     */
    public synchronized AccountProjection setCredential(
            AccountScope scope, long expectedSecurityRevision, CommandIdentity identity, byte[] plaintext) {
        return setCredential(scope, expectedSecurityRevision, -1, identity, plaintext);
    }

    /**
     * 保存用户确认的精确网站权限版本下的账号密码。
     *
     * @param scope 可信账号所有权
     * @param expectedSecurityRevision 账号安全版本
     * @param expectedSiteAuthorityRevision 用户确认的网站权限版本；负值仅允许沿用账号现有绑定
     * @param identity 文档版本和幂等身份
     * @param plaintext 私有用户名 NUL 密码字节，本方法负责清零
     * @return 脱敏账号投影
     */
    public synchronized AccountProjection setCredential(
            AccountScope scope,
            long expectedSecurityRevision,
            long expectedSiteAuthorityRevision,
            CommandIdentity identity,
            byte[] plaintext) {
        return loginWrites.credential(
                scope, expectedSecurityRevision, expectedSiteAuthorityRevision, identity, plaintext);
    }

    /**
     * 原子保存明确确认的用户名密码和刚完成登录的状态，然后撤销旧安全绑定。
     *
     * @param confirmation 用户确认时冻结的唯一状态写租约，包含账号和网站安全版本
     * @param identity 账号文档期望版本与幂等身份
     * @param credentials Worker 私有用户名 NUL 密码字节，本方法负责清零
     * @param state Worker 私有登录状态，本方法负责清零
     * @return 一次递增安全版本和登录态版本后的脱敏状态
     */
    public synchronized AccountProjection setLogin(
            StateLease confirmation, CommandIdentity identity, byte[] credentials, byte[] state) {
        try {
            Optional<AccountProjection> replay = recover(identity);
            if (replay.isPresent()) {
                return replay.orElseThrow();
            }
            requireLease(confirmation);
            return loginWrites.login(confirmation, identity, credentials, state);
        } finally {
            Arrays.fill(credentials, (byte) 0);
            Arrays.fill(state, (byte) 0);
        }
    }

    /**
     * 获取账号唯一保存租约；其他会话持有时拒绝，不隐式接管。
     *
     * @param scope 可信账号所有权
     * @param sessionId 浏览器会话身份
     * @return 当前保存租约；同会话再次调用可取得最新 stateRevision
     */
    public synchronized StateLease acquireStateLease(AccountScope scope, String sessionId) {
        Account current = usable(scope, -1);
        StateLease existing = leases.get(scope);
        if (existing != null && !existing.sessionId().equals(sessionId)) {
            throw new IllegalStateException("账号登录态正在由另一浏览器会话保存");
        }
        long generation = existing == null ? generations.incrementAndGet() : existing.generation();
        StateLease next = new StateLease(
                scope,
                sessionId,
                current.securityRevision(),
                current.stateRevision(),
                current.siteAuthorityRevision(),
                generation);
        leases.put(scope, next);
        return next;
    }

    /**
     * 在同库事务内保存私有 storage state，拒绝过期租约和旧 Cookie 覆盖。
     *
     * @param lease 当前唯一写租约
     * @param identity 完整保存幂等身份
     * @param state Worker 私有字节；调用方仍拥有数组，本方法返回前清零
     * @return 保存后的脱敏账号状态
     */
    public synchronized AccountProjection saveState(StateLease lease, CommandIdentity identity, byte[] state) {
        try {
            Optional<AccountProjection> replay = recover(identity);
            if (replay.isPresent()) {
                return replay.orElseThrow();
            }
            requireLease(lease);
            SiteAccountSecretBytes.validateStorage(state);
            Account current = usable(lease.scope(), lease.securityRevision());
            if (current.stateRevision() != lease.stateRevision()) {
                throw PersistenceException.revisionConflict("登录态已由更新的会话保存");
            }
            try (Mutation mutation =
                    secrets.prepare(SiteContracts.BROWSER_CREDENTIAL_NAMESPACE, current.browserState(), state)) {
                AccountProjection result = commit(identity, List.of(mutation), tx -> {
                    requireLease(lease);
                    Account locked = require(tx, lease.scope(), current.revision(), lease.securityRevision());
                    requireUsable(tx, lease.scope(), locked);
                    Account next = locked.state(mutation.metadata(), clock.instant());
                    documents.write(tx, lease.scope().workspaceId(), next, locked.revision());
                    return projection(tx, lease.scope(), next);
                });
                leases.replace(
                        lease.scope(),
                        lease,
                        new StateLease(
                                lease.scope(),
                                lease.sessionId(),
                                lease.securityRevision(),
                                result.stateRevision(),
                                lease.siteAuthorityRevision(),
                                lease.generation()));
                return result;
            }
        } finally {
            Arrays.fill(Objects.requireNonNull(state, "state"), (byte) 0);
        }
    }

    /**
     * 释放本会话保存权，旧租约不能释放其他会话。
     *
     * @param lease 本会话持有的租约；允许是其此前 stateRevision
     */
    public void releaseStateLease(StateLease lease) {
        leases.computeIfPresent(
                lease.scope(),
                (scope, current) -> current.generation() == lease.generation()
                                && current.sessionId().equals(lease.sessionId())
                        ? null
                        : current);
    }

    /**
     * 在锁外宿主 callback 中使用用户名密码，秘密不可逸出 callback。消费前后校验安全版本，期间撤权使结果失效。
     *
     * @param scope 可信账号所有权
     * @param expectedSecurityRevision 本次浏览器绑定安全版本
     * @param operation 私有字节消费操作
     * @param <T> 不含秘密的结果类型
     * @return 消费结果
     */
    public <T> T useCredential(AccountScope scope, long expectedSecurityRevision, SecretOperation<T> operation) {
        try {
            return useSecret(scope, expectedSecurityRevision, false, operation);
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new VaultException("使用账号密码的私有操作失败", failure);
        }
    }

    /**
     * 在锁外宿主 callback 中使用已保存登录态，未保存时传入空数组。消费前后校验安全版本，返回前清零副本。
     *
     * @param scope 可信账号所有权
     * @param expectedSecurityRevision 本次浏览器绑定安全版本
     * @param operation 私有字节消费操作
     * @param <T> 不含秘密的结果类型
     * @return 消费结果
     * @throws Exception 消费操作失败
     */
    public <T> T useState(AccountScope scope, long expectedSecurityRevision, SecretOperation<T> operation)
            throws Exception {
        return useSecret(scope, expectedSecurityRevision, true, operation);
    }

    private <T> T useSecret(AccountScope scope, long securityRevision, boolean state, SecretOperation<T> operation)
            throws Exception {
        Optional<CredentialMetadata> selected = secretSnapshot(scope, securityRevision, state);
        // Vault 锁内只复制解密结果；Worker 与 Broker 等待必须在账号和 Vault 锁外，保证注销能即时撤权。
        byte[] copy = selected.map(metadata -> vault.use(metadata.reference(), byte[]::clone))
                .orElseGet(() -> new byte[0]);
        try {
            requireSnapshot(scope, securityRevision, state, selected);
            T result = Objects.requireNonNull(operation, "operation").use(copy);
            requireSnapshot(scope, securityRevision, state, selected);
            return result;
        } finally {
            Arrays.fill(copy, (byte) 0);
        }
    }

    private synchronized Optional<CredentialMetadata> secretSnapshot(
            AccountScope scope, long securityRevision, boolean state) {
        Account current = usable(scope, securityRevision);
        Optional<CredentialMetadata> selected = state ? current.browserState() : current.loginSecret();
        if (!state && selected.isEmpty()) {
            throw new IllegalStateException("账号尚未保存密码");
        }
        selected.ifPresent(metadata -> {
            if (!vault.metadata(metadata.reference()).equals(Optional.of(metadata))) {
                throw new SecurityException("账号秘密绑定已改变，请重新确认登录");
            }
        });
        return selected;
    }

    private void requireSnapshot(
            AccountScope scope, long securityRevision, boolean state, Optional<CredentialMetadata> expected) {
        if (!secretSnapshot(scope, securityRevision, state).equals(expected)) {
            throw new SecurityException("账号秘密已在使用期间改变");
        }
    }

    /**
     * 注册账号撤销通知，供 Browser host 立即关闭旧账号会话。
     *
     * @param listener 不得阻塞等待其他账号命令的监听器
     * @return 幂等退订句柄；宿主关闭时必须释放
     */
    public AutoCloseable onSecurityChanged(Consumer<AccountScope> listener) {
        Consumer<AccountScope> checked = Objects.requireNonNull(listener, "listener");
        securityListeners.add(checked);
        return () -> securityListeners.remove(checked);
    }

    /**
     * 解封前恢复已成功提交的命令。
     *
     * @param identity 完整幂等身份
     * @return 已提交投影，没有时为空
     */
    public Optional<AccountProjection> recover(CommandIdentity identity) {
        return secrets.recover(identity, AccountProjection.class);
    }

    private AccountProjection create(
            WorkspaceId workspace, SiteAccountContracts.CreateRequest request, CommandIdentity identity) {
        if (identity.expectedRevision() != 0) {
            throw PersistenceException.revisionConflict("创建账号的 expected revision 必须为零");
        }
        list(workspace, request.siteId());
        return commit(identity, List.of(), tx -> {
            SiteContracts.Site site = documents.site(tx, workspace, request.siteId());
            Index index = documents.migrate(tx, workspace, site);
            if (documents.list(tx, workspace, request.siteId()).size() >= 100) {
                throw new IllegalStateException("网站账号数量已达到上限");
            }
            Account account = new Account(
                    UUID.randomUUID().toString(),
                    site.id(),
                    1,
                    1,
                    0,
                    site.authorityRevision(),
                    request.name(),
                    true,
                    Optional.empty(),
                    Optional.empty(),
                    clock.instant());
            documents.write(tx, workspace, account, 0);
            if (index.defaultAccount().isEmpty()) {
                index = documents.setDefault(tx, workspace, site.id(), Optional.of(account.accountId()));
            }
            return documents.projection(account, index);
        });
    }

    private AccountProjection update(
            WorkspaceId workspace, SiteAccountContracts.UpdateRequest request, CommandIdentity identity) {
        AccountScope scope = scope(workspace, request.selection());
        Account current = require(scope, identity.expectedRevision(), -1);
        AccountProjection result = commit(identity, List.of(), tx -> {
            Account locked = require(tx, scope, current.revision(), -1);
            Account next = locked.metadata(request.name(), request.enabled(), clock.instant());
            documents.write(tx, workspace, next, locked.revision());
            return projection(tx, scope, next);
        });
        if (current.enabled() != request.enabled()) {
            revoke(scope);
        }
        return result;
    }

    private AccountProjection control(
            WorkspaceId workspace, SiteAccountContracts.Selection request, String operation, CommandIdentity identity) {
        AccountScope scope = scope(workspace, request);
        Account current = require(scope, identity.expectedRevision(), -1);
        List<Mutation> mutations = new ArrayList<>();
        if (!operation.equals("account/default")) {
            current.browserState().ifPresent(value -> mutations.add(secrets.prepareClear(value)));
        }
        if (operation.equals("account/delete")) {
            current.loginSecret().ifPresent(value -> mutations.add(secrets.prepareClear(value)));
        }
        try {
            AccountProjection result = commit(identity, mutations, tx -> control(tx, scope, current, operation));
            if (!operation.equals("account/default")) {
                revoke(scope);
            }
            return result;
        } finally {
            mutations.forEach(Mutation::close);
        }
    }

    private AccountProjection control(ExtensionTransaction tx, AccountScope scope, Account expected, String operation) {
        Account current = require(tx, scope, expected.revision(), -1);
        if (operation.equals("account/default")) {
            if (!current.enabled()) {
                throw new IllegalStateException("不能将已禁用账号设为默认");
            }
            return documents.projection(
                    current,
                    documents.setDefault(tx, scope.workspaceId(), scope.siteId(), Optional.of(scope.accountId())));
        }
        Account next = current.logout(clock.instant());
        if (operation.equals("account/delete")) {
            documents.delete(tx, scope, current.revision());
            Index index =
                    documents.migrate(tx, scope.workspaceId(), documents.site(tx, scope.workspaceId(), scope.siteId()));
            if (index.defaultAccount().filter(scope.accountId()::equals).isPresent()) {
                documents.setDefault(tx, scope.workspaceId(), scope.siteId(), Optional.empty());
            }
        } else {
            documents.write(tx, scope.workspaceId(), next, current.revision());
        }
        return projection(tx, scope, next);
    }

    Account require(AccountScope scope, long revision, long securityRevision) {
        list(scope.workspaceId(), scope.siteId());
        return transaction(tx -> require(tx, scope, revision, securityRevision));
    }

    Account require(ExtensionTransaction tx, AccountScope scope, long revision, long securityRevision) {
        Account account = documents.require(tx, scope);
        if (revision >= 0 && account.revision() != revision
                || securityRevision >= 0 && account.securityRevision() != securityRevision) {
            throw PersistenceException.revisionConflict("账号版本或安全版本已改变");
        }
        return account;
    }

    private Account usable(AccountScope scope, long securityRevision) {
        Account account = require(scope, -1, securityRevision);
        transaction(tx -> {
            requireUsable(tx, scope, account);
            return null;
        });
        watchedReferences.put(
                scope,
                java.util.stream.Stream.concat(account.loginSecret().stream(), account.browserState().stream())
                        .map(value -> value.reference())
                        .collect(java.util.stream.Collectors.toUnmodifiableSet()));
        return account;
    }

    private void requireUsable(ExtensionTransaction tx, AccountScope scope, Account account) {
        SiteContracts.Site site = documents.site(tx, scope.workspaceId(), scope.siteId());
        if (!site.enabled() || !account.enabled() || site.authorityRevision() != account.siteAuthorityRevision()) {
            throw new SecurityException("网站或账号权限已改变，请重新确认账号登录");
        }
    }

    private void requireLease(StateLease lease) {
        if (!lease.equals(leases.get(lease.scope()))) {
            throw new SecurityException("账号保存租约已失效");
        }
    }

    AccountProjection projection(ExtensionTransaction tx, AccountScope scope, Account account) {
        return documents.projection(
                account,
                documents.migrate(tx, scope.workspaceId(), documents.site(tx, scope.workspaceId(), scope.siteId())));
    }

    AccountProjection commit(
            CommandIdentity identity, List<Mutation> mutations, TransactionWork<AccountProjection> work) {
        ownSecretMutation.set(true);
        try {
            return secrets.commit(
                    identity,
                    mutations,
                    AccountProjection.class,
                    connection -> store.inExistingTransaction(SITE, connection, work));
        } finally {
            ownSecretMutation.remove();
        }
    }

    private <T> T transaction(TransactionWork<T> work) {
        try {
            return store.inTransaction(SITE, work);
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new PersistenceException("网站账号事务失败", failure);
        }
    }

    private static AccountScope scope(WorkspaceId workspace, SiteAccountContracts.Selection selection) {
        return new AccountScope(workspace, selection.siteId(), selection.accountId());
    }

    void revoke(AccountScope scope) {
        leases.remove(scope);
        watchedReferences.remove(scope);
        notifySecurity(scope);
    }

    private void notifySecurity(AccountScope scope) {
        securityListeners.forEach(listener -> listener.accept(scope));
    }

    private record CommandBinding(WorkspaceId workspaceId, SiteAccountContracts.ServiceRequest request) {}
}
