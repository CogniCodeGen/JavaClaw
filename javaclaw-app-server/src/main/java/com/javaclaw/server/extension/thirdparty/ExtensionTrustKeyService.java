package com.javaclaw.server.extension.thirdparty;

import java.util.List;
import java.util.Objects;

import com.javaclaw.api.AttachmentContent;
import com.javaclaw.api.AttachmentScope;
import com.javaclaw.protocol.BundleRpcContracts;
import com.javaclaw.server.persistence.AttachmentService;
import com.javaclaw.server.persistence.ExtensionTrustKeyRecord;
import com.javaclaw.server.persistence.ExtensionTrustKeyRepository;
import com.javaclaw.server.persistence.PersistenceException;

/** Attachment 导入、展示、撤销与实时 Bundle 禁用的 Trust Key 用例。 */
final class ExtensionTrustKeyService {
    private final AttachmentService attachments;
    private final ExtensionTrustKeyRepository repository;
    private final ExtensionTrustedKeys trustedKeys;
    private final ThirdPartyBundleRegistry registry;

    ExtensionTrustKeyService(
            AttachmentService attachments,
            ExtensionTrustKeyRepository repository,
            ExtensionTrustedKeys trustedKeys,
            ThirdPartyBundleRegistry registry) {
        this.attachments = Objects.requireNonNull(attachments, "attachments");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.trustedKeys = Objects.requireNonNull(trustedKeys, "trustedKeys");
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    List<BundleRpcContracts.TrustKey> list() {
        return repository.list().stream().map(ExtensionTrustKeyRecord::metadata).toList();
    }

    BundleRpcContracts.TrustKey read(String keyId) {
        return repository
                .find(keyId)
                .orElseThrow(() -> PersistenceException.invalidRequest("Trust Key 不存在"))
                .metadata();
    }

    synchronized BundleRpcContracts.TrustKey importKey(
            BundleRpcContracts.TrustKeyImportPayload request, long expectedRevision) {
        if (expectedRevision != 0) {
            throw PersistenceException.revisionConflict("Trust Key 导入 expected revision 必须为 0");
        }
        AttachmentContent attachment =
                attachments.read(AttachmentScope.global(), request.attachment().attachmentId());
        requireAttachment(attachment, request.attachment());
        ExtensionTrustedKeys.DecodedKey decoded = ExtensionTrustedKeys.decodeAttachment(attachment.content());
        ExtensionTrustKeyRecord record = ExtensionTrustKeyRecord.active(
                request.keyId(),
                decoded.fingerprint(),
                attachment.metadata().digest(),
                decoded.encoded(),
                attachment.metadata().createdAt());
        ExtensionTrustKeyRecord stored = repository.importKey(record);
        trustedKeys.trust(stored);
        return stored.metadata();
    }

    synchronized BundleRpcContracts.TrustKey revoke(String keyId, long expectedRevision) {
        ExtensionTrustKeyRepository.RevocationResult result = repository.revoke(keyId, expectedRevision);
        trustedKeys.revoke(keyId);
        result.disabledExtensions().forEach(registry::remove);
        return result.key();
    }

    private static void requireAttachment(AttachmentContent attachment, BundleRpcContracts.AttachmentPointer expected) {
        if (!attachment.metadata().digest().equals(expected.digest())) {
            throw new SecurityException("Trust Key Attachment digest changed");
        }
        if (!BundleRpcContracts.PUBLIC_KEY_MEDIA_TYPE.equals(
                attachment.metadata().mediaType())) {
            throw PersistenceException.invalidRequest("Trust Key Attachment media type 不受支持");
        }
    }
}
