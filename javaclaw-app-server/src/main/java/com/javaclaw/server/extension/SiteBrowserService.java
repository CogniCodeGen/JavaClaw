package com.javaclaw.server.extension;

import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.javaclaw.api.BrokerRequest;
import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.CredentialMetadata;
import com.javaclaw.api.PrivateNetworkGrantRef;
import com.javaclaw.api.PrivateNetworkPurpose;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.browser.client.BrowserNetworkResult;
import com.javaclaw.browser.client.BrowserWorkerPort;
import com.javaclaw.browser.protocol.BrowserWorkerProtocol;
import com.javaclaw.builtin.contracts.BuiltinExtensionIds;
import com.javaclaw.builtin.contracts.SiteContracts;
import com.javaclaw.extension.spi.ExtensionId;
import com.javaclaw.extension.spi.IsolatedServiceInvocation;
import com.javaclaw.extension.spi.VersionedDocument;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.server.persistence.CommandIdentity;
import com.javaclaw.server.persistence.H2ManagedExtensionStore;
import com.javaclaw.server.security.BrowserBrokerResponse;
import com.javaclaw.server.security.grant.PrivateNetworkGrantService;
import com.javaclaw.server.security.vault.SecretVaultService;

/** Site authority、Vault、Browser Worker 与宿主 Network Broker 的安全组合服务。 */
final class SiteBrowserService implements AutoCloseable {
    private static final ExtensionId SITE_ID = new ExtensionId(BuiltinExtensionIds.SITE);
    private static final String DOCUMENTS = "documents.";
    private static final String LOGIN_RECEIPTS = "browser-login-receipts.";
    private static final int MAXIMUM_CREDENTIAL_BYTES = 16 * 1024;
    private static final Set<String> CONTROLLED_HEADERS = Set.of(
            "accept-encoding",
            "authorization",
            "connection",
            "content-length",
            "expect",
            "host",
            "keep-alive",
            "proxy-authorization",
            "proxy-connection",
            "te",
            "trailer",
            "transfer-encoding",
            "upgrade");

    private final Optional<Runtime> runtime;
    private final CanonicalJson json;

    private SiteBrowserService(Optional<Runtime> runtime, CanonicalJson json) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.json = Objects.requireNonNull(json, "json");
    }

    static SiteBrowserService available(
            BrowserWorkerPort browser,
            H2ManagedExtensionStore documents,
            SecretVaultService vault,
            SiteNetworkBroker broker,
            PrivateNetworkGrantService grants,
            CanonicalJson json) {
        Runtime runtime = new Runtime(browser, documents, vault, broker, grants, new ConcurrentHashMap<>());
        return new SiteBrowserService(Optional.of(runtime), json);
    }

    static SiteBrowserService unavailable(CanonicalJson json) {
        return new SiteBrowserService(Optional.empty(), json);
    }

    boolean isAvailable() {
        return runtime.isPresent();
    }

    CanonicalPayload snapshot(IsolatedServiceInvocation invocation) throws Exception {
        Runtime currentRuntime = runtime.orElseThrow(
                () -> new IllegalStateException("Browser Worker packaged Sandbox runtime is unavailable"));
        SiteContracts.SnapshotTask task = json.decode(invocation.request(), SiteContracts.SnapshotTask.class);
        requireAuthority(currentRuntime, invocation.workspaceId(), task.site());
        byte[] emptyState = new byte[0];
        if (task.site().credential().kind() != SiteContracts.CredentialKind.BROWSER_STORAGE) {
            return currentRuntime
                    .browser()
                    .snapshot(
                            task,
                            emptyState,
                            (request, body, cancellation) -> exchange(currentRuntime, invocation, task, request, body),
                            invocation.cancellation());
        }
        return currentRuntime
                .vault()
                .use(
                        task.site().credential().reference().orElseThrow(),
                        storageState -> currentRuntime
                                .browser()
                                .snapshot(
                                        task,
                                        storageState,
                                        (request, body, cancellation) ->
                                                exchange(currentRuntime, invocation, task, request, body),
                                        invocation.cancellation()));
    }

    CanonicalPayload invalidate(IsolatedServiceInvocation invocation) {
        SiteContracts.AuthorityInvalidation invalidation =
                json.decode(invocation.request(), SiteContracts.AuthorityInvalidation.class);
        runtime.ifPresent(
                value -> value.browser().invalidate(invalidation.siteId(), invalidation.currentAuthorityRevision()));
        return json.encode(Map.of());
    }

    CanonicalPayload loginBegin(IsolatedServiceInvocation invocation) throws Exception {
        Runtime currentRuntime = requireLoginRuntime();
        cleanupBindings(currentRuntime);
        SiteContracts.LoginBeginTask task = json.decode(invocation.request(), SiteContracts.LoginBeginTask.class);
        SiteContracts.Site site = requireAuthority(currentRuntime, invocation.workspaceId(), task.site());
        LoginBinding binding = new LoginBinding(invocation.workspaceId(), site);
        LoginBinding prior = currentRuntime.loginBindings().putIfAbsent(task.sessionId(), binding);
        if (prior != null && !prior.equals(binding)) {
            throw new IllegalArgumentException("login session is bound to another Workspace or Site authority");
        }
        try {
            SiteContracts.LoginSession session = beginLogin(currentRuntime, invocation, task);
            return json.encode(session);
        } catch (Exception failure) {
            currentRuntime.loginBindings().remove(task.sessionId(), binding);
            throw failure;
        }
    }

    CanonicalPayload loginStatus(IsolatedServiceInvocation invocation) {
        Runtime currentRuntime = requireLoginRuntime();
        SiteContracts.LoginControlRequest request =
                json.decode(invocation.request(), SiteContracts.LoginControlRequest.class);
        requireBinding(currentRuntime, invocation.workspaceId(), request.sessionId());
        return json.encode(currentRuntime.browser().loginStatus(request.sessionId()));
    }

    CanonicalPayload loginList(IsolatedServiceInvocation invocation) {
        if (runtime.isEmpty()) {
            return json.encode(new SiteContracts.LoginSessionList(List.of(), false));
        }
        Runtime currentRuntime = runtime.orElseThrow();
        if (!currentRuntime.browser().interactiveLoginAvailable()) {
            return json.encode(new SiteContracts.LoginSessionList(List.of(), false));
        }
        cleanupBindings(currentRuntime);
        List<SiteContracts.LoginSession> sessions = currentRuntime.loginBindings().entrySet().stream()
                .filter(entry -> entry.getValue().workspaceId().equals(invocation.workspaceId()))
                .map(entry -> status(currentRuntime, entry.getKey()))
                .flatMap(Optional::stream)
                .sorted(java.util.Comparator.comparing(SiteContracts.LoginSession::startedAt)
                        .reversed())
                .toList();
        return json.encode(new SiteContracts.LoginSessionList(
                sessions, currentRuntime.browser().interactiveLoginAvailable()));
    }

    CanonicalPayload loginSave(IsolatedServiceInvocation invocation) throws Exception {
        Runtime currentRuntime = requireLoginRuntime();
        SiteContracts.LoginSaveTask task = json.decode(invocation.request(), SiteContracts.LoginSaveTask.class);
        Optional<SiteContracts.LoginSaveCommit> recovered = recoverSave(currentRuntime, invocation.workspaceId(), task);
        if (recovered.isPresent()) {
            return json.encode(recovered.orElseThrow());
        }
        LoginBinding binding = requireBinding(currentRuntime, invocation.workspaceId(), task.sessionId());
        SiteContracts.Site current = requireAuthority(currentRuntime, invocation.workspaceId(), binding.site());
        String scope = "site/browser/login/save/" + invocation.workspaceId() + "/" + current.id();
        CommandIdentity identity = new CommandIdentity(scope, task.idempotencyKey(), 0, task.requestDigest());
        CredentialMetadata credential = currentRuntime
                .vault()
                .recoverCredential(identity)
                .orElseGet(() -> currentRuntime
                        .browser()
                        .saveLogin(
                                task.sessionId(),
                                state -> currentRuntime
                                        .vault()
                                        .create(identity, SiteContracts.BROWSER_CREDENTIAL_NAMESPACE, state)));
        SiteContracts.LoginSession session = currentRuntime.browser().loginStatus(task.sessionId());
        SiteContracts.LoginSaveCommit result =
                commitSavedSite(currentRuntime, invocation.workspaceId(), current, credential, session, task);
        currentRuntime.browser().invalidate(current.id(), result.site().authorityRevision());
        return json.encode(result);
    }

    CanonicalPayload loginCancel(IsolatedServiceInvocation invocation) {
        Runtime currentRuntime = requireLoginRuntime();
        SiteContracts.LoginControlRequest request =
                json.decode(invocation.request(), SiteContracts.LoginControlRequest.class);
        requireBinding(currentRuntime, invocation.workspaceId(), request.sessionId());
        return json.encode(currentRuntime.browser().cancelLogin(request.sessionId()));
    }

    private SiteContracts.LoginSession beginLogin(
            Runtime currentRuntime, IsolatedServiceInvocation invocation, SiteContracts.LoginBeginTask task) {
        if (task.site().credential().kind() != SiteContracts.CredentialKind.BROWSER_STORAGE) {
            return currentRuntime
                    .browser()
                    .beginLogin(
                            task,
                            new byte[0],
                            (request, body, cancellation) ->
                                    exchange(currentRuntime, invocation, task.site(), task.timeout(), request, body),
                            invocation.cancellation());
        }
        return currentRuntime
                .vault()
                .use(
                        task.site().credential().reference().orElseThrow(),
                        state -> currentRuntime
                                .browser()
                                .beginLogin(
                                        task,
                                        state,
                                        (request, body, cancellation) -> exchange(
                                                currentRuntime, invocation, task.site(), task.timeout(), request, body),
                                        invocation.cancellation()));
    }

    private Optional<SiteContracts.LoginSession> status(Runtime currentRuntime, String sessionId) {
        try {
            return Optional.of(currentRuntime.browser().loginStatus(sessionId));
        } catch (IllegalArgumentException missing) {
            currentRuntime.loginBindings().remove(sessionId);
            return Optional.empty();
        }
    }

    private void cleanupBindings(Runtime currentRuntime) {
        currentRuntime.loginBindings().keySet().forEach(sessionId -> status(currentRuntime, sessionId));
    }

    private Runtime requireLoginRuntime() {
        Runtime currentRuntime = runtime.orElseThrow(
                () -> new IllegalStateException("Browser Worker packaged Sandbox runtime is unavailable"));
        if (!currentRuntime.browser().interactiveLoginAvailable()) {
            throw new UnsupportedOperationException(
                    "Browser interactive login is not verified for this packaged native runtime");
        }
        return currentRuntime;
    }

    private static LoginBinding requireBinding(Runtime runtime, WorkspaceId workspaceId, String sessionId) {
        LoginBinding binding = runtime.loginBindings().get(sessionId);
        if (binding == null || !binding.workspaceId().equals(workspaceId)) {
            throw new IllegalArgumentException("Browser login session does not exist in this Workspace");
        }
        return binding;
    }

    private BrowserNetworkResult exchange(
            Runtime currentRuntime,
            IsolatedServiceInvocation invocation,
            SiteContracts.SnapshotTask task,
            BrowserWorkerProtocol.NetworkRequest request,
            byte[] body)
            throws Exception {
        return exchange(currentRuntime, invocation, task.site(), task.timeout(), request, body);
    }

    private BrowserNetworkResult exchange(
            Runtime currentRuntime,
            IsolatedServiceInvocation invocation,
            SiteContracts.Site frozen,
            java.time.Duration timeout,
            BrowserWorkerProtocol.NetworkRequest request,
            byte[] body)
            throws Exception {
        SiteContracts.Site current = requireAuthority(currentRuntime, invocation.workspaceId(), frozen);
        if (!current.allowedOrigins().contains(SiteContracts.originOf(request.uri()))) {
            throw new SecurityException("Browser request Origin is outside the current Site authority");
        }
        LinkedHashMap<String, List<String>> headers = filteredHeaders(request.headers());
        BrowserExchangeContext context = new BrowserExchangeContext(currentRuntime, invocation, frozen, timeout);
        if (!current.origin().equals(SiteContracts.originOf(request.uri()))) {
            return broker(context, current, request, headers, body);
        }
        return switch (current.credential().kind()) {
            case BEARER, API_KEY_HEADER ->
                currentRuntime
                        .vault()
                        .use(
                                current.credential().reference().orElseThrow(),
                                secret -> broker(
                                        context, current, request, authenticated(current, headers, secret), body));
            case NONE, BROWSER_STORAGE -> broker(context, current, request, headers, body);
        };
    }

    private BrowserNetworkResult broker(
            BrowserExchangeContext context,
            SiteContracts.Site current,
            BrowserWorkerProtocol.NetworkRequest request,
            Map<String, List<String>> headers,
            byte[] body)
            throws Exception {
        BrokerRequest brokerRequest = new BrokerRequest(
                request.uri(),
                request.method(),
                headers,
                body,
                BrowserWorkerProtocol.MAXIMUM_NETWORK_BYTES,
                context.timeout());
        BrowserBrokerResponse response = context.runtime()
                .broker()
                .exchange(
                        brokerRequest,
                        context.invocation().effectivePermissions(),
                        context.invocation().cancellation(),
                        (origin, addresses) -> authorizePrivate(
                                context.runtime().grants(),
                                current,
                                context.invocation().workspaceId(),
                                origin,
                                addresses),
                        () -> requireAuthority(
                                context.runtime(), context.invocation().workspaceId(), context.frozen()));
        byte[] responseBody = response.body();
        try {
            BrowserWorkerProtocol.NetworkResponse metadata = new BrowserWorkerProtocol.NetworkResponse(
                    response.statusCode(), response.headers(), response.truncated());
            return new BrowserNetworkResult(metadata, responseBody);
        } finally {
            Arrays.fill(responseBody, (byte) 0);
        }
    }

    private Optional<SiteContracts.LoginSaveCommit> recoverSave(
            Runtime runtime, WorkspaceId workspaceId, SiteContracts.LoginSaveTask task) throws Exception {
        Optional<VersionedDocument> document = runtime.documents()
                .inTransaction(SITE_ID, transaction -> transaction.get(LOGIN_RECEIPTS + workspaceId, receiptKey(task)));
        if (document.isEmpty()) {
            return Optional.empty();
        }
        StoredSaveReceipt receipt = json.decode(document.orElseThrow().payload(), StoredSaveReceipt.class);
        if (!receipt.requestDigest().equals(task.requestDigest())) {
            throw new IllegalArgumentException("login save idempotency key is bound to another request");
        }
        return Optional.of(receipt.result());
    }

    private SiteContracts.LoginSaveCommit commitSavedSite(
            Runtime runtime,
            WorkspaceId workspaceId,
            SiteContracts.Site frozen,
            CredentialMetadata credential,
            SiteContracts.LoginSession session,
            SiteContracts.LoginSaveTask task)
            throws Exception {
        return runtime.documents().inTransaction(SITE_ID, transaction -> {
            Optional<VersionedDocument> recovered = transaction.get(LOGIN_RECEIPTS + workspaceId, receiptKey(task));
            if (recovered.isPresent()) {
                StoredSaveReceipt receipt = json.decode(recovered.orElseThrow().payload(), StoredSaveReceipt.class);
                if (!receipt.requestDigest().equals(task.requestDigest())) {
                    throw new IllegalArgumentException("login save idempotency key is bound to another request");
                }
                return receipt.result();
            }
            VersionedDocument stored = transaction
                    .get(DOCUMENTS + workspaceId, frozen.id())
                    .orElseThrow(() -> new SecurityException("Site no longer exists"));
            SiteContracts.Site current = json.decode(stored.payload(), SiteContracts.Site.class);
            requireSameAuthority(stored, current, frozen);
            SiteContracts.Site updated = browserCredentialSite(current, credential);
            transaction.put(DOCUMENTS + workspaceId, updated.id(), current.revision(), json.encode(updated));
            SiteContracts.LoginSaveCommit result = new SiteContracts.LoginSaveCommit(updated, credential, session);
            StoredSaveReceipt receipt = new StoredSaveReceipt(task.requestDigest(), result);
            transaction.put(LOGIN_RECEIPTS + workspaceId, receiptKey(task), 0, json.encode(receipt));
            return result;
        });
    }

    private static SiteContracts.Site browserCredentialSite(SiteContracts.Site current, CredentialMetadata credential) {
        return new SiteContracts.Site(
                current.id(),
                Math.addExact(current.revision(), 1),
                Math.addExact(current.authorityRevision(), 1),
                current.name(),
                current.origin(),
                current.allowedOrigins(),
                new SiteContracts.SiteCredential(
                        SiteContracts.CredentialKind.BROWSER_STORAGE,
                        Optional.of(credential.reference()),
                        Optional.empty()),
                current.privateNetworkGrant(),
                current.enabled(),
                credential.updatedAt());
    }

    private static void requireSameAuthority(
            VersionedDocument stored, SiteContracts.Site current, SiteContracts.Site frozen) {
        if (stored.revision() != frozen.revision()
                || current.revision() != frozen.revision()
                || current.authorityRevision() != frozen.authorityRevision()
                || !current.enabled()
                || !current.origin().equals(frozen.origin())
                || !current.allowedOrigins().equals(frozen.allowedOrigins())
                || !current.credential().equals(frozen.credential())
                || !current.privateNetworkGrant().equals(frozen.privateNetworkGrant())) {
            throw new SecurityException("Site authority changed while Browser login was active");
        }
    }

    private String receiptKey(SiteContracts.LoginSaveTask task) {
        return json.encode(Map.of("idempotencyKey", task.idempotencyKey())).sha256();
    }

    private SiteContracts.Site requireAuthority(
            Runtime currentRuntime, WorkspaceId workspaceId, SiteContracts.Site frozen) throws Exception {
        VersionedDocument document = currentRuntime
                .documents()
                .inTransaction(
                        SITE_ID,
                        transaction -> transaction
                                .get(DOCUMENTS + workspaceId, frozen.id())
                                .orElseThrow(() -> new SecurityException("Site no longer exists")));
        SiteContracts.Site current = json.decode(document.payload(), SiteContracts.Site.class);
        if (document.revision() != current.revision()
                || !current.enabled()
                || current.authorityRevision() != frozen.authorityRevision()
                || !current.origin().equals(frozen.origin())
                || !current.allowedOrigins().equals(frozen.allowedOrigins())
                || !current.credential().equals(frozen.credential())
                || !current.privateNetworkGrant().equals(frozen.privateNetworkGrant())) {
            throw new SecurityException("Site authority changed or was disabled");
        }
        return current;
    }

    private static void authorizePrivate(
            PrivateNetworkGrantService grants,
            SiteContracts.Site site,
            WorkspaceId workspaceId,
            java.net.URI origin,
            Set<String> addresses) {
        PrivateNetworkGrantRef reference = site.privateNetworkGrant()
                .orElseThrow(() -> new SecurityException("private Site Origin requires an explicit grant"));
        grants.requireAuthorized(
                reference.id(), reference.revision(), workspaceId, PrivateNetworkPurpose.SITE, origin, addresses);
    }

    private static LinkedHashMap<String, List<String>> filteredHeaders(Map<String, List<String>> source) {
        LinkedHashMap<String, List<String>> result = new LinkedHashMap<>();
        source.forEach((name, values) -> {
            String normalized = name.toLowerCase(java.util.Locale.ROOT);
            if (!CONTROLLED_HEADERS.contains(normalized)) {
                result.put(normalized, List.copyOf(values));
            }
        });
        return result;
    }

    private static Map<String, List<String>> authenticated(
            SiteContracts.Site site, Map<String, List<String>> source, byte[] secret) throws Exception {
        LinkedHashMap<String, List<String>> result = new LinkedHashMap<>(source);
        String value = credentialText(secret);
        if (site.credential().kind() == SiteContracts.CredentialKind.BEARER) {
            result.put("authorization", List.of("Bearer " + value));
        } else {
            result.put(site.credential().apiKeyHeader().orElseThrow(), List.of(value));
        }
        return result;
    }

    private static String credentialText(byte[] secret) throws Exception {
        if (secret.length == 0 || secret.length > MAXIMUM_CREDENTIAL_BYTES) {
            throw new SecurityException("Site credential length is outside the allowed range");
        }
        String value = StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(secret))
                .toString();
        if (value.chars().anyMatch(character -> character < 0x20 || character == 0x7f)) {
            throw new SecurityException("Site credential contains a control character");
        }
        return value;
    }

    @Override
    public void close() {
        runtime.ifPresent(value -> value.browser().close());
    }

    private record Runtime(
            BrowserWorkerPort browser,
            H2ManagedExtensionStore documents,
            SecretVaultService vault,
            SiteNetworkBroker broker,
            PrivateNetworkGrantService grants,
            ConcurrentHashMap<String, LoginBinding> loginBindings) {
        private Runtime {
            Objects.requireNonNull(browser, "browser");
            Objects.requireNonNull(documents, "documents");
            Objects.requireNonNull(vault, "vault");
            Objects.requireNonNull(broker, "broker");
            Objects.requireNonNull(grants, "grants");
            Objects.requireNonNull(loginBindings, "loginBindings");
        }
    }

    private record LoginBinding(WorkspaceId workspaceId, SiteContracts.Site site) {
        private LoginBinding {
            Objects.requireNonNull(workspaceId, "workspaceId");
            Objects.requireNonNull(site, "site");
        }
    }

    /** 单次 Broker 交换冻结的宿主边界；同组权限依赖作为整体传递，避免调用点参数漂移。 */
    private record BrowserExchangeContext(
            Runtime runtime,
            IsolatedServiceInvocation invocation,
            SiteContracts.Site frozen,
            java.time.Duration timeout) {
        private BrowserExchangeContext {
            Objects.requireNonNull(runtime, "runtime");
            Objects.requireNonNull(invocation, "invocation");
            Objects.requireNonNull(frozen, "frozen");
            Objects.requireNonNull(timeout, "timeout");
        }
    }

    private record StoredSaveReceipt(String requestDigest, SiteContracts.LoginSaveCommit result) {
        private StoredSaveReceipt {
            requestDigest = Objects.requireNonNull(requestDigest, "requestDigest");
            Objects.requireNonNull(result, "result");
        }
    }
}
