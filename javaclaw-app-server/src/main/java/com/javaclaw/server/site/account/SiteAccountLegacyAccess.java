package com.javaclaw.server.site.account;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import com.javaclaw.api.CredentialMetadata;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.builtin.contracts.SiteAccountContracts;
import com.javaclaw.builtin.contracts.SiteAccountContracts.AccountProjection;
import com.javaclaw.builtin.contracts.SiteAccountContracts.AccountScope;
import com.javaclaw.builtin.contracts.SiteAccountContracts.StateLease;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.server.persistence.CommandIdentity;
import com.javaclaw.server.security.vault.SecretOperation;

/** 旧单网站 Browser 入口对默认账号的宿主适配；不再从已迁移的 Site 引用回退秘密。 */
public final class SiteAccountLegacyAccess implements AutoCloseable {
    private final SiteAccountService accounts;
    private final CanonicalJson json;
    private final AutoCloseable subscription;
    private final ConcurrentHashMap<String, StateLease> loginLeases = new ConcurrentHashMap<>();

    /**
     * 复用唯一账号服务和 JSON 编码器。
     *
     * @param accounts 当前运行时共享的账号服务
     * @param json 规范 JSON 编码器
     * @param cancelSession 撤权时关闭旧 Worker 会话的操作，在账号锁外异步执行
     */
    public SiteAccountLegacyAccess(SiteAccountService accounts, CanonicalJson json, Consumer<String> cancelSession) {
        this.accounts = Objects.requireNonNull(accounts, "accounts");
        this.json = Objects.requireNonNull(json, "json");
        Objects.requireNonNull(cancelSession, "cancelSession");
        subscription = accounts.onSecurityChanged(scope -> loginLeases.forEach((sessionId, lease) -> {
            if (lease.scope().equals(scope) && loginLeases.remove(sessionId, lease)) {
                accounts.releaseStateLease(lease);
                // 撤权已同步移除保存权；Worker 停止可能等待反向 Broker，不能占用账号锁。
                CompletableFuture.runAsync(() -> cancelSession.accept(sessionId));
            }
        }));
    }

    /**
     * 冻结一次旧 snapshot 使用的默认账号；无默认账号时只能无状态浏览。
     *
     * @param workspace 网站所属 Workspace
     * @param siteId 网站 ID
     * @return 无秘密的冻结账号身份
     */
    public Binding select(WorkspaceId workspace, String siteId) {
        return selected(workspace, siteId)
                .map(value -> binding(workspace, value))
                .orElseGet(Binding::empty);
    }

    /**
     * 为显式人工登录冻结默认账号和唯一写租约；仅在网站没有任何账号时创建默认账号。
     *
     * @param workspace 网站所属 Workspace
     * @param siteId 网站 ID
     * @param sessionId 用户启动的登录会话身份
     * @return 与该登录会话绑定的账号身份
     */
    public Binding begin(WorkspaceId workspace, String siteId, String sessionId) {
        StateLease existing = loginLeases.get(sessionId);
        if (existing != null) {
            if (!existing.scope().workspaceId().equals(workspace)
                    || !existing.scope().siteId().equals(siteId)) {
                throw new SecurityException("旧登录会话已绑定其他网站");
            }
            return binding(existing);
        }
        AccountProjection account =
                selected(workspace, siteId).orElseGet(() -> createDefault(workspace, siteId, sessionId));
        StateLease lease =
                accounts.acquireStateLease(new AccountScope(workspace, siteId, account.accountId()), sessionId);
        StateLease prior = loginLeases.putIfAbsent(sessionId, lease);
        return binding(prior == null ? lease : prior);
    }

    /**
     * 在私有回调中消费冻结账号登录态，消费前后均复核撤权。
     *
     * @param binding 本次操作已冻结的账号身份
     * @param operation 不得泄露状态的私有消费操作
     * @param <T> 非敏感结果类型
     * @return 消费结果
     * @throws Exception 账号已撤权或消费失败
     */
    public <T> T use(Binding binding, SecretOperation<T> operation) throws Exception {
        if (binding.scope().isEmpty()) {
            return operation.use(new byte[0]);
        }
        return accounts.useState(binding.scope().orElseThrow(), binding.securityRevision(), operation);
    }

    /**
     * 在 DNS 解析后的 Socket 边界重新检查账号，防止已撤销 Cookie 被继续发送。
     *
     * @param binding 网络请求所属冻结账号
     */
    public void require(Binding binding) {
        try {
            use(binding, ignored -> null);
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new SecurityException("旧浏览器账号授权已失效", failure);
        }
    }

    /**
     * 保存旧人工登录的私有状态，复用账号 CAS 与原子 Vault 事务。
     *
     * @param sessionId 启动时冻结的登录会话
     * @param identity 旧入口的幂等保存身份
     * @param state Worker 私有状态字节，账号服务负责清零
     * @return 已提交状态的 opaque Vault 元数据，仅供内部兼容回执使用
     */
    public CredentialMetadata save(String sessionId, CommandIdentity identity, byte[] state) {
        StateLease lease = Optional.ofNullable(loginLeases.get(sessionId))
                .orElseThrow(() -> new SecurityException("旧登录账号保存租约不存在"));
        synchronized (accounts) {
            AccountProjection result = accounts.saveState(lease, identity, state);
            return accounts.require(lease.scope(), result.revision(), result.securityRevision())
                    .browserState()
                    .orElseThrow();
        }
    }

    /**
     * 恢复已提交的旧保存，避免 Worker 进入 SAVED 后再次请求导出状态。
     *
     * @param sessionId 原登录会话
     * @param identity 原幂等身份
     * @return 已提交账号状态的 opaque 元数据；无回执时为空
     */
    public Optional<CredentialMetadata> recover(String sessionId, CommandIdentity identity) {
        synchronized (accounts) {
            return accounts.recover(identity).map(result -> {
                StateLease lease = Optional.ofNullable(loginLeases.get(sessionId))
                        .orElseThrow(() -> new SecurityException("旧登录账号保存租约不存在"));
                return accounts.require(lease.scope(), result.revision(), result.securityRevision())
                        .browserState()
                        .orElseThrow();
            });
        }
    }

    /**
     * 释放登录会话的状态写权，不清除用户保存的数据。
     *
     * @param sessionId 已取消、保存或消失的登录会话
     */
    public void release(String sessionId) {
        StateLease lease = loginLeases.remove(sessionId);
        if (lease != null) {
            accounts.releaseStateLease(lease);
        }
    }

    @Override
    public void close() {
        try {
            subscription.close();
        } catch (Exception failure) {
            throw new IllegalStateException("释放旧登录账号监听器失败", failure);
        } finally {
            List.copyOf(loginLeases.keySet()).forEach(this::release);
        }
    }

    private Optional<AccountProjection> selected(WorkspaceId workspace, String siteId) {
        return accounts.list(workspace, siteId).accounts().stream()
                .filter(AccountProjection::defaultAccount)
                .findFirst();
    }

    private AccountProjection createDefault(WorkspaceId workspace, String siteId, String sessionId) {
        if (!accounts.list(workspace, siteId).accounts().isEmpty()) {
            throw new IllegalStateException("请先在账号管理中选择默认账号");
        }
        var payload = json.encode(new SiteAccountContracts.CreateRequest(siteId, "默认账号"));
        CommandIdentity identity = new CommandIdentity(
                "site/account/legacy/create/" + workspace + '/' + siteId, sessionId, 0, payload.sha256());
        return accounts.command(workspace, "account/create", payload, identity);
    }

    private static Binding binding(WorkspaceId workspace, AccountProjection account) {
        return new Binding(
                Optional.of(new AccountScope(workspace, account.siteId(), account.accountId())),
                account.securityRevision());
    }

    private static Binding binding(StateLease lease) {
        return new Binding(Optional.of(lease.scope()), lease.securityRevision());
    }

    /**
     * 旧入口冻结的账号授权；无账号不携带任何历史单凭据。
     *
     * @param scope 账号所有权；空表示匿名浏览
     * @param securityRevision 冻结安全版本；匿名时为零
     */
    public record Binding(Optional<AccountScope> scope, long securityRevision) {
        /** 复制非空 Optional。 */
        public Binding {
            scope = Objects.requireNonNull(scope, "scope");
        }

        private static Binding empty() {
            return new Binding(Optional.empty(), 0);
        }
    }
}
