package com.javaclaw.server.extension;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.CredentialMetadata;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.builtin.contracts.BuiltinExtensionIds;
import com.javaclaw.builtin.contracts.SiteContracts;
import com.javaclaw.extension.spi.ExtensionId;
import com.javaclaw.extension.spi.VersionedDocument;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.server.persistence.H2ManagedExtensionStore;

/** 旧登录保存的幂等回执；账号模式只确认已提交 state，不修改 Site 或其 HTTP 凭据。 */
final class SiteBrowserSaveReceipts {
    private static final ExtensionId SITE_ID = new ExtensionId(BuiltinExtensionIds.SITE);
    private static final String DOCUMENTS = "documents.";
    private static final String LOGIN_RECEIPTS = "browser-login-receipts.";
    private final CanonicalJson json;

    SiteBrowserSaveReceipts(CanonicalJson json) {
        this.json = json;
    }

    Optional<SiteContracts.LoginSaveCommit> recover(
            H2ManagedExtensionStore documents, WorkspaceId workspaceId, SiteContracts.LoginSaveTask task)
            throws Exception {
        Optional<VersionedDocument> document = documents.inTransaction(
                SITE_ID, transaction -> transaction.get(LOGIN_RECEIPTS + workspaceId, receiptKey(task)));
        if (document.isEmpty()) {
            return Optional.empty();
        }
        StoredSaveReceipt receipt = json.decode(document.orElseThrow().payload(), StoredSaveReceipt.class);
        if (!receipt.requestDigest().equals(task.requestDigest())) {
            throw new IllegalArgumentException("login save idempotency key is bound to another request");
        }
        return Optional.of(receipt.result());
    }

    SiteContracts.LoginSaveCommit commit(
            H2ManagedExtensionStore documents,
            WorkspaceId workspaceId,
            SiteContracts.Site frozen,
            CredentialMetadata credential,
            SiteContracts.LoginSession session,
            SiteContracts.LoginSaveTask task,
            boolean accountBacked)
            throws Exception {
        return documents.inTransaction(SITE_ID, transaction -> {
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
            SiteContracts.Site updated = current;
            if (!accountBacked) {
                updated = browserCredentialSite(current, credential);
                transaction.put(DOCUMENTS + workspaceId, updated.id(), current.revision(), json.encode(updated));
            }
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

    private record StoredSaveReceipt(String requestDigest, SiteContracts.LoginSaveCommit result) {
        private StoredSaveReceipt {
            requestDigest = Objects.requireNonNull(requestDigest, "requestDigest");
            Objects.requireNonNull(result, "result");
        }
    }
}
