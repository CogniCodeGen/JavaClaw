package com.javaclaw.server.site.account;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.javaclaw.api.WorkspaceId;
import com.javaclaw.builtin.contracts.BuiltinExtensionIds;
import com.javaclaw.builtin.contracts.SiteAccountContracts;
import com.javaclaw.builtin.contracts.SiteContracts;
import com.javaclaw.builtin.contracts.SiteRegistrationContracts;
import com.javaclaw.builtin.contracts.SiteRegistrationContracts.Session;
import com.javaclaw.extension.spi.ExtensionId;
import com.javaclaw.extension.spi.ExtensionTransaction;
import com.javaclaw.extension.spi.VersionedDocument;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.server.persistence.CommandIdentity;
import com.javaclaw.server.persistence.H2ManagedExtensionStore;
import com.javaclaw.server.persistence.PersistenceException;
import com.javaclaw.server.security.vault.ScopedCredentialVault;
import com.javaclaw.server.security.vault.ScopedCredentialVault.Mutation;

/** 网站登记的同库事务；Site、默认账号、密文与幂等回执同时成功，任何失败均不得留下半成品。 */
public final class SiteRegistrationStore {
    private static final ExtensionId SITE = new ExtensionId(BuiltinExtensionIds.SITE);
    private final H2ManagedExtensionStore store;
    private final SiteAccountDocuments documents;
    private final ScopedCredentialVault secrets;
    private final CanonicalJson json;
    private final Clock clock;

    SiteRegistrationStore(
            H2ManagedExtensionStore store,
            SiteAccountDocuments documents,
            ScopedCredentialVault secrets,
            CanonicalJson json,
            Clock clock) {
        this.store = store;
        this.documents = documents;
        this.secrets = secrets;
        this.json = json;
        this.clock = clock;
    }

    /**
     * @param identity 完整幂等身份
     * @return 已提交脱敏结果，恢复不访问 Worker 或解密秘密
     */
    public Optional<Session> recover(CommandIdentity identity) {
        return secrets.recover(identity, Session.class);
    }

    /**
     * @param workspace 可信 Workspace
     * @param sessionId 登记 UUID
     * @return 同 Workspace 的持久会话状态
     */
    public Optional<Session> read(WorkspaceId workspace, String sessionId) {
        try {
            return store.inTransaction(
                    SITE,
                    tx -> tx.get(collection(workspace), sessionId)
                            .map(value -> json.decode(value.payload(), Session.class)));
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new PersistenceException("读取网站登记失败", failure);
        }
    }

    /**
     * @param workspace 可信 Workspace
     * @param identity 命令身份
     * @param session 非敏感会话
     * @return 原子保存或恢复的状态
     */
    public Session record(WorkspaceId workspace, CommandIdentity identity, Session session) {
        return secrets.commit(
                identity,
                List.of(),
                Session.class,
                connection -> store.inExistingTransaction(SITE, connection, tx -> write(tx, workspace, session)));
    }

    /**
     * @param workspace 可信 Workspace
     * @param request 用户确认
     * @param status Worker 同一次导出的脱敏观察
     * @param identity 完整幂等身份
     * @param state 私有 storage state；本方法负责清零
     * @param credentials 可选用户名 NUL 密码；空数组表示未选择；本方法负责清零
     * @param requireCurrent 事务内重新检查登记代次、权限与取消；不得执行网络或 Vault 写入
     * @return 原子提交后的登记结果
     */
    public synchronized Session complete(
            WorkspaceId workspace,
            SiteRegistrationContracts.CompleteRequest request,
            SiteRegistrationContracts.WorkerStatus status,
            CommandIdentity identity,
            byte[] state,
            byte[] credentials,
            Runnable requireCurrent) {
        List<Mutation> mutations = new ArrayList<>();
        try {
            Optional<Session> recovered = recover(identity);
            if (recovered.isPresent()) {
                return recovered.orElseThrow();
            }
            validateConfirmation(request, status);
            SiteAccountSecretBytes.validateStorage(state);
            if (credentials.length > 0) {
                SiteAccountSecretBytes.validate(credentials);
            }
            if (request.credentialId().isPresent() != (credentials.length > 0)) {
                throw new SecurityException("密码候选与私有导出不一致");
            }
            Mutation browser = secrets.prepare(SiteContracts.BROWSER_CREDENTIAL_NAMESPACE, Optional.empty(), state);
            mutations.add(browser);
            Optional<Mutation> password = credentials.length == 0
                    ? Optional.empty()
                    : Optional.of(secrets.prepare(SiteAccountContracts.LOGIN_NAMESPACE, Optional.empty(), credentials));
            password.ifPresent(mutations::add);
            return secrets.commit(
                    identity,
                    mutations,
                    Session.class,
                    connection -> store.inExistingTransaction(SITE, connection, tx -> {
                        requireCurrent.run();
                        return create(tx, workspace, request, status, browser, password);
                    }));
        } finally {
            mutations.forEach(Mutation::close);
            Arrays.fill(state, (byte) 0);
            Arrays.fill(credentials, (byte) 0);
        }
    }

    private Session create(
            ExtensionTransaction tx,
            WorkspaceId workspace,
            SiteRegistrationContracts.CompleteRequest request,
            SiteRegistrationContracts.WorkerStatus status,
            Mutation state,
            Optional<Mutation> password) {
        Session previous = tx.get(collection(workspace), request.sessionId())
                .map(value -> json.decode(value.payload(), Session.class))
                .orElseThrow();
        if (previous.state() != SiteRegistrationContracts.State.ACTIVE
                || previous.access().generation() != request.expectedGeneration()
                || !previous.access().allowedOrigins().equals(status.access().allowedOrigins())
                || !previous.access().expiresAt().equals(status.access().expiresAt())
                || !previous.access().expiresAt().isAfter(clock.instant())) {
            throw new IllegalStateException("登记会话已结束");
        }
        URI uri = status.page().uri().orElseThrow(() -> new IllegalStateException("当前页面没有可保存地址"));
        URI origin = SiteContracts.originOf(uri);
        requireNewOrigin(tx, workspace, origin);
        String siteId = "site-" + stable(request.sessionId() + ":site");
        String accountId = stable(request.sessionId() + ":account");
        SiteContracts.Site site = new SiteContracts.Site(
                siteId,
                1,
                1,
                request.name(),
                origin,
                status.access().allowedOrigins(),
                SiteContracts.SiteCredential.none(),
                Optional.empty(),
                true,
                clock.instant());
        tx.put("documents." + workspace, site.id(), 0, json.encode(site));
        createDefaultAccount(tx, workspace, site, accountId, state, password);
        tx.put(
                "registration-addresses." + workspace,
                siteId,
                0,
                json.encode(Map.of("uri", uri, "title", status.page().title())));
        String originKey = json.encode(Map.of("origin", origin)).sha256();
        String origins = "registration-origins." + workspace;
        long revision =
                tx.get(origins, originKey).map(VersionedDocument::revision).orElse(0L);
        tx.put(origins, originKey, revision, json.encode(Map.of("siteId", siteId, "origin", origin)));
        Session completed = new Session(
                request.sessionId(),
                SiteRegistrationContracts.State.COMPLETED,
                status.access(),
                status.page(),
                Optional.of(new SiteRegistrationContracts.Completed(siteId, accountId, origin)));
        write(tx, workspace, completed);
        tx.appendEvent("site.registration.completed", json.encode(Map.of("workspaceId", workspace, "siteId", siteId)));
        return completed;
    }

    private void createDefaultAccount(
            ExtensionTransaction tx,
            WorkspaceId workspace,
            SiteContracts.Site site,
            String accountId,
            Mutation state,
            Optional<Mutation> password) {
        documents.migrate(tx, workspace, site);
        SiteAccountDocuments.Account account = new SiteAccountDocuments.Account(
                accountId,
                site.id(),
                1,
                1,
                1,
                site.authorityRevision(),
                "默认账号",
                true,
                password.map(Mutation::metadata),
                Optional.of(state.metadata()),
                clock.instant());
        documents.write(tx, workspace, account, 0);
        documents.setDefault(tx, workspace, site.id(), Optional.of(accountId));
    }

    private static void validateConfirmation(
            SiteRegistrationContracts.CompleteRequest request, SiteRegistrationContracts.WorkerStatus status) {
        if (!request.sessionId().equals(status.sessionId())
                || status.state() != SiteRegistrationContracts.State.ACTIVE
                || request.expectedGeneration() != status.access().generation()
                || request.expectedPageRevision() != status.page().pageRevision()) {
            throw new SecurityException("登记保存确认与实际页面或授权代次不一致");
        }
        URI origin =
                SiteContracts.originOf(status.page().uri().orElseThrow(() -> new IllegalStateException("当前页面没有可保存地址")));
        if (!status.access().allowedOrigins().contains(origin)) {
            throw new SecurityException("登记页面尚未得到用户授权");
        }
        request.credentialId().ifPresent(id -> {
            boolean owned = status.page().candidates().stream()
                    .anyMatch(candidate ->
                            candidate.id().equals(id) && candidate.origin().equals(origin));
            if (!owned) {
                throw new SecurityException("密码候选不属于当前网站页面");
            }
        });
    }

    private void requireNewOrigin(ExtensionTransaction tx, WorkspaceId workspace, URI origin) {
        String cursor = "";
        while (true) {
            List<VersionedDocument> page = tx.list("documents." + workspace, cursor, 500);
            for (VersionedDocument value : page) {
                if (json.decode(value.payload(), SiteContracts.Site.class)
                        .origin()
                        .equals(origin)) {
                    throw new IllegalStateException("该来源已存在网站，请选择已有网站管理账号");
                }
            }
            if (page.size() < 500) {
                return;
            }
            cursor = page.getLast().key();
        }
    }

    private Session write(ExtensionTransaction tx, WorkspaceId workspace, Session session) {
        long revision = tx.get(collection(workspace), session.sessionId())
                .map(VersionedDocument::revision)
                .orElse(0L);
        tx.put(collection(workspace), session.sessionId(), revision, json.encode(session));
        return session;
    }

    private static String collection(WorkspaceId workspace) {
        return "registrations." + workspace;
    }

    private static String stable(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8)).toString();
    }
}
