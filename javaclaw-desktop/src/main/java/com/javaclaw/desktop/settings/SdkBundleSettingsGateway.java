package com.javaclaw.desktop.settings;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

import com.javaclaw.api.AttachmentMetadata;
import com.javaclaw.api.AttachmentScope;
import com.javaclaw.api.CancellationSource;
import com.javaclaw.client.CommandOptions;
import com.javaclaw.client.facade.AttachmentUploadOptions;
import com.javaclaw.desktop.DesktopPresenter;
import com.javaclaw.protocol.BundleRpcContracts;

/** 通过 Desktop 后台执行器组合 Attachment 与 Bundle SDK facade。 */
class SdkBundleSettingsGateway implements BundleSettingsGateway {
    private static final int MAX_BUNDLE_BYTES = 64 * 1024 * 1024;
    private static final int MAX_KEY_BYTES = 4096;

    private final DesktopPresenter desktop;

    SdkBundleSettingsGateway(DesktopPresenter desktop) {
        this.desktop = Objects.requireNonNull(desktop, "desktop");
    }

    @Override
    public CompletionStage<List<BundleRpcContracts.Bundle>> bundles() {
        return desktop.submitSettingsRequest(client -> client.extensionBundles().list());
    }

    @Override
    public CompletionStage<BundleRpcContracts.StageResult> stageBundle(Path archive) {
        Path candidate = Objects.requireNonNull(archive, "archive");
        return desktop.submitSettingsRequest(client -> {
            Path selected = checkedPath(candidate);
            AttachmentMetadata attachment = client.attachments()
                    .uploadMetadata(
                            selected,
                            new AttachmentUploadOptions(
                                    AttachmentScope.global(),
                                    BundleRpcContracts.BUNDLE_MEDIA_TYPE,
                                    MAX_BUNDLE_BYTES,
                                    new CancellationSource(),
                                    CommandOptions.create(0)));
            return client.extensionBundles().stage(attachment, CommandOptions.create(0));
        });
    }

    @Override
    public CompletionStage<BundleRpcContracts.Bundle> installBundle(BundleRpcContracts.StageResult staging) {
        return desktop.submitSettingsRequest(
                client -> client.extensionBundles().install(staging, CommandOptions.create(0)));
    }

    @Override
    public CompletionStage<BundleRpcContracts.Bundle> upgradeBundle(
            BundleRpcContracts.StageResult staging, BundleRpcContracts.Bundle current) {
        requireSameExtension(staging, current);
        return desktop.submitSettingsRequest(
                client -> client.extensionBundles().upgrade(staging, CommandOptions.create(current.revision())));
    }

    @Override
    public CompletionStage<BundleRpcContracts.Bundle> probeBundle(BundleRpcContracts.Bundle bundle) {
        return desktop.submitSettingsRequest(
                client -> client.extensionBundles().probe(bundle.id(), CommandOptions.create(bundle.revision())));
    }

    @Override
    public CompletionStage<BundleRpcContracts.Bundle> setBundleEnabled(
            BundleRpcContracts.Bundle bundle, boolean enabled) {
        return desktop.submitSettingsRequest(client -> enabled
                ? client.extensionBundles().enable(bundle.id(), CommandOptions.create(bundle.revision()))
                : client.extensionBundles().disable(bundle.id(), CommandOptions.create(bundle.revision())));
    }

    @Override
    public CompletionStage<BundleRpcContracts.TrashEntry> uninstallBundle(BundleRpcContracts.Bundle bundle) {
        return desktop.submitSettingsRequest(
                client -> client.extensionBundles().uninstall(bundle.id(), CommandOptions.create(bundle.revision())));
    }

    @Override
    public CompletionStage<List<BundleRpcContracts.TrustKey>> trustKeys() {
        return desktop.submitSettingsRequest(client -> client.extensionBundles().listTrustKeys());
    }

    @Override
    public CompletionStage<TrustKeyImportDraft> prepareTrustKey(Path publicKey) {
        Path candidate = Objects.requireNonNull(publicKey, "publicKey");
        return desktop.submitSettingsRequest(client -> {
            Path selected = checkedPath(candidate);
            byte[] content = readBounded(selected, MAX_KEY_BYTES);
            PublicKey key = decodeKey(content);
            String fingerprint = digest(key.getEncoded());
            AttachmentMetadata attachment = client.attachments()
                    .uploadMetadata(
                            selected,
                            new AttachmentUploadOptions(
                                    AttachmentScope.global(),
                                    BundleRpcContracts.PUBLIC_KEY_MEDIA_TYPE,
                                    MAX_KEY_BYTES,
                                    new CancellationSource(),
                                    CommandOptions.create(0)));
            return new TrustKeyImportDraft(selected.getFileName().toString(), attachment, fingerprint);
        });
    }

    @Override
    public CompletionStage<BundleRpcContracts.TrustKey> importTrustKey(String keyId, TrustKeyImportDraft draft) {
        TrustKeyImportDraft checked = Objects.requireNonNull(draft, "draft");
        return desktop.submitSettingsRequest(client -> {
            BundleRpcContracts.TrustKey imported =
                    client.extensionBundles().importTrustKey(keyId, checked.attachment(), CommandOptions.create(0));
            if (!imported.fingerprint().equals(checked.fingerprint())) {
                throw new SecurityException("服务端 Trust Key 指纹与确认值不一致");
            }
            return imported;
        });
    }

    @Override
    public CompletionStage<BundleRpcContracts.TrustKey> revokeTrustKey(BundleRpcContracts.TrustKey key) {
        return desktop.submitSettingsRequest(
                client -> client.extensionBundles().revokeTrustKey(key.id(), CommandOptions.create(key.revision())));
    }

    @Override
    public CompletionStage<List<BundleRpcContracts.TrashEntry>> bundleTrash() {
        return desktop.submitSettingsRequest(client -> client.extensionBundles().listTrash());
    }

    @Override
    public CompletionStage<BundleRpcContracts.Bundle> restoreBundle(BundleRpcContracts.TrashEntry entry) {
        return desktop.submitSettingsRequest(client ->
                client.extensionBundles().restoreTrash(entry.trashId(), CommandOptions.create(entry.revision())));
    }

    @Override
    public CompletionStage<BundleRpcContracts.TrashEntry> purgeBundle(
            BundleRpcContracts.TrashEntry entry, String confirmation) {
        return desktop.submitSettingsRequest(client -> client.extensionBundles()
                .purgeTrash(entry.trashId(), confirmation, CommandOptions.create(entry.revision())));
    }

    private static Path checkedPath(Path path) {
        Path selected = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        if (Files.isSymbolicLink(selected) || !Files.isRegularFile(selected, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("请选择普通文件，不能使用目录或符号链接");
        }
        return selected;
    }

    private static byte[] readBounded(Path path, int maximum) {
        try {
            long size = Files.size(path);
            if (size < 1 || size > maximum) {
                throw new IllegalArgumentException("所选文件大小必须在 1 到 " + maximum + " bytes 之间");
            }
            try (var input = Files.newInputStream(path)) {
                byte[] content = input.readNBytes(maximum + 1);
                if (content.length < 1 || content.length > maximum || input.read() != -1) {
                    throw new IllegalArgumentException("所选文件在读取期间超过大小限制");
                }
                return content;
            }
        } catch (IOException failure) {
            throw new UncheckedIOException("无法读取所选文件", failure);
        }
    }

    private static PublicKey decodeKey(byte[] content) {
        try {
            return key(content);
        } catch (IllegalArgumentException directFailure) {
            try {
                return key(Base64.getDecoder().decode(new String(content, StandardCharsets.US_ASCII).strip()));
            } catch (IllegalArgumentException encodedFailure) {
                encodedFailure.addSuppressed(directFailure);
                throw new IllegalArgumentException("所选文件不是 Ed25519 DER 或 Base64 DER 公钥", encodedFailure);
            }
        }
    }

    private static PublicKey key(byte[] encoded) {
        try {
            PublicKey key = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(encoded));
            if (!"EdDSA".equalsIgnoreCase(key.getAlgorithm()) && !"Ed25519".equalsIgnoreCase(key.getAlgorithm())) {
                throw new IllegalArgumentException("公钥算法不是 Ed25519");
            }
            return key;
        } catch (GeneralSecurityException failure) {
            throw new IllegalArgumentException("Ed25519 公钥编码无效", failure);
        }
    }

    private static String digest(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("SHA-256 不可用", failure);
        }
    }

    private static void requireSameExtension(
            BundleRpcContracts.StageResult staging, BundleRpcContracts.Bundle current) {
        if (!Objects.requireNonNull(staging, "staging")
                .extensionId()
                .equals(Objects.requireNonNull(current, "current").id())) {
            throw new IllegalArgumentException("升级 staging 与当前 Bundle 标识不一致");
        }
    }
}
