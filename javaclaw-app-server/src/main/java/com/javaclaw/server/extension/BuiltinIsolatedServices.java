package com.javaclaw.server.extension;

import java.time.Clock;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.browser.client.BrowserWorkerPort;
import com.javaclaw.builtin.contracts.BuiltinExtensionIds;
import com.javaclaw.builtin.contracts.KnowledgeContracts;
import com.javaclaw.builtin.contracts.SiteContracts;
import com.javaclaw.builtin.contracts.SkillContracts;
import com.javaclaw.extension.spi.IsolatedServiceInvocation;
import com.javaclaw.extension.spi.IsolatedServicePort;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.server.persistence.AttachmentService;
import com.javaclaw.server.persistence.H2Database;
import com.javaclaw.server.persistence.H2ManagedExtensionStore;
import com.javaclaw.server.security.PinnedHttpNetworkBroker;
import com.javaclaw.server.security.grant.PrivateNetworkGrantService;
import com.javaclaw.server.security.vault.SecretVaultService;

/** 组合根显式允许的内置进程外服务路由；未知 caller 与服务一律拒绝。 */
public final class BuiltinIsolatedServices implements IsolatedServicePort, AutoCloseable {
    private final SiteBrowserService browser;
    private final KnowledgeExtractionService knowledge;
    private final SkillResourceExecutionService skill;
    private final Optional<BrowserWorkerPort> browserWorker;

    private BuiltinIsolatedServices(
            SiteBrowserService browser,
            KnowledgeExtractionService knowledge,
            SkillResourceExecutionService skill,
            Optional<BrowserWorkerPort> browserWorker) {
        this.browser = Objects.requireNonNull(browser, "browser");
        this.knowledge = Objects.requireNonNull(knowledge, "knowledge");
        this.skill = Objects.requireNonNull(skill, "skill");
        this.browserWorker = Objects.requireNonNull(browserWorker, "browserWorker");
    }

    /**
     * 创建生产服务路由。
     *
     * <p>Browser 只在签名发行镜像路径显式配置时启用；IDEA 与缺少原生 Sandbox 的环境安全拒绝页面调用，不回退普通 {@link ProcessBuilder}。
     *
     * @param database data-v6 数据库
     * @param attachments Core Attachment 服务
     * @param vault Secret Vault
     * @param grants 私网授权服务
     * @param json 规范 JSON codec
     * @param clock 平台时钟
     * @return 尚未启动子进程的服务路由
     */
    public static BuiltinIsolatedServices production(
            H2Database database,
            AttachmentService attachments,
            SecretVaultService vault,
            PrivateNetworkGrantService grants,
            CanonicalJson json,
            Clock clock) {
        H2Database checkedDatabase = Objects.requireNonNull(database, "database");
        CanonicalJson checkedJson = Objects.requireNonNull(json, "json");
        Optional<com.javaclaw.browser.client.BrowserWorkerClient> worker =
                BrowserWorkerRuntimeFactory.create(checkedDatabase.dataRoot());
        SiteBrowserService site = worker.map(client -> SiteBrowserService.available(
                        client,
                        new H2ManagedExtensionStore(checkedDatabase, clock),
                        vault,
                        new PinnedHttpNetworkBroker()::exchangeBrowserSingleHop,
                        grants,
                        checkedJson))
                .orElseGet(() -> SiteBrowserService.unavailable(checkedJson));
        KnowledgeExtractionService knowledge = KnowledgeWorkerRuntimeFactory.create(checkedDatabase.dataRoot())
                .map(client -> KnowledgeExtractionService.available(attachments, client, checkedJson))
                .orElseGet(() -> KnowledgeExtractionService.unavailable(checkedJson));
        SkillResourceExecutionService skill =
                SkillResourceExecutionService.production(checkedDatabase.dataRoot(), attachments, checkedJson);
        return new BuiltinIsolatedServices(site, knowledge, skill, worker.map(client -> (BrowserWorkerPort) client));
    }

    /**
     * 创建不启用 Browser 的显式测试路由；页面调用始终安全拒绝。
     *
     * @return fail-closed 服务路由
     */
    public static BuiltinIsolatedServices browserUnavailable() {
        return new BuiltinIsolatedServices(
                SiteBrowserService.unavailable(new CanonicalJson()),
                KnowledgeExtractionService.unavailable(new CanonicalJson()),
                SkillResourceExecutionService.unavailable(new CanonicalJson()),
                Optional.empty());
    }

    /**
     * 返回不含进程路径的隔离 Worker 可用性。
     *
     * @return Browser、Knowledge 与 Skill Worker 状态
     */
    public Availability availability() {
        return new Availability(browser.isAvailable(), knowledge.isAvailable(), skill.isAvailable());
    }

    /**
     * 返回同一原生 Sandbox Browser Worker 的 OAuth 私有端口。
     *
     * @return 仅签名发行镜像且原生窗口能力已验证时存在
     */
    public Optional<BrowserWorkerPort> oauthBrowser() {
        return browserWorker.filter(BrowserWorkerPort::oauthAvailable);
    }

    @Override
    public CanonicalPayload invoke(IsolatedServiceInvocation invocation) throws Exception {
        IsolatedServiceInvocation checked = Objects.requireNonNull(invocation, "invocation");
        if (BuiltinExtensionIds.SITE.equals(checked.caller().value())) {
            return invokeSite(checked);
        }
        if (BuiltinExtensionIds.KNOWLEDGE.equals(checked.caller().value())
                && KnowledgeContracts.EXTRACTION_SERVICE.equals(checked.serviceId())) {
            return knowledge.extract(checked);
        }
        if (BuiltinExtensionIds.SKILL.equals(checked.caller().value())
                && SkillContracts.RESOURCE_EXECUTION_SERVICE.equals(checked.serviceId())) {
            return skill.invoke(checked);
        }
        throw new IllegalArgumentException("extension is not authorized for isolated service: " + checked.serviceId());
    }

    private CanonicalPayload invokeSite(IsolatedServiceInvocation invocation) throws Exception {
        return switch (invocation.serviceId()) {
            case SiteContracts.BROWSER_SNAPSHOT_SERVICE -> browser.snapshot(invocation);
            case SiteContracts.BROWSER_INVALIDATE_SERVICE -> browser.invalidate(invocation);
            case SiteContracts.BROWSER_LOGIN_BEGIN_SERVICE -> browser.loginBegin(invocation);
            case SiteContracts.BROWSER_LOGIN_STATUS_SERVICE -> browser.loginStatus(invocation);
            case SiteContracts.BROWSER_LOGIN_LIST_SERVICE -> browser.loginList(invocation);
            case SiteContracts.BROWSER_LOGIN_SAVE_SERVICE -> browser.loginSave(invocation);
            case SiteContracts.BROWSER_LOGIN_CANCEL_SERVICE -> browser.loginCancel(invocation);
            default ->
                throw new IllegalArgumentException(
                        "Site extension is not authorized for isolated service: " + invocation.serviceId());
        };
    }

    /** 关闭并销毁全部按需 Worker。 */
    @Override
    public void close() {
        knowledge.close();
        browser.close();
    }

    /**
     * 内置隔离 Worker 脱敏可用性。
     *
     * @param browser Browser Worker 发行镜像是否可用
     * @param knowledge Knowledge Worker 发行镜像是否可用
     * @param skillExecution Skill Java/JShell Native Sandbox 是否可用
     */
    public record Availability(boolean browser, boolean knowledge, boolean skillExecution) {}
}
