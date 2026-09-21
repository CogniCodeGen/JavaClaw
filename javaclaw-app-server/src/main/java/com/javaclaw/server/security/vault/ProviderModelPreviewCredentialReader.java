package com.javaclaw.server.security.vault;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.CredentialMetadata;
import com.javaclaw.api.CredentialRef;
import com.javaclaw.model.CredentialMaterial;
import com.javaclaw.server.persistence.PersistenceException;
import com.javaclaw.server.persistence.ProviderModelPreviewCredentialPort;

/**
 * Vault 对模型目录预览提供的只读适配器。
 *
 * <p>调用方已经持有 Provider 锁，本类只取得 Vault 锁；版本核对和实际材料解密处于同一区间。返回的材料不再依赖锁，网络读取在调用方释放锁后执行。
 */
public final class ProviderModelPreviewCredentialReader implements ProviderModelPreviewCredentialPort {
    private final SecretVaultService vault;

    /**
     * 绑定当前服务器的 Vault，不接管其生命周期。
     *
     * @param vault 已装配的凭据服务
     */
    public ProviderModelPreviewCredentialReader(SecretVaultService vault) {
        this.vault = Objects.requireNonNull(vault, "vault");
    }

    @Override
    public Optional<CredentialMaterial> read(
            Optional<CredentialRef> reference, long expectedRevision, boolean materialRequired) {
        Optional<CredentialRef> checked = Objects.requireNonNull(reference, "reference");
        checked.ifPresent(ProviderModelPreviewCredentialReader::requireProviderReference);
        synchronized (vault) {
            long actual = checked.flatMap(vault::metadata)
                    .map(CredentialMetadata::revision)
                    .orElse(0L);
            if (expectedRevision < 0 || actual != expectedRevision) {
                throw PersistenceException.revisionConflict("预览来源的凭据版本已变化，请重新打开配置");
            }
            if (!materialRequired) {
                return Optional.empty();
            }
            CredentialRef existing =
                    checked.orElseThrow(() -> PersistenceException.invalidRequest("来源 Provider 尚未配置凭据"));
            return Optional.of(vault.use(existing, ProviderModelPreviewCredentialReader::decode));
        }
    }

    private static void requireProviderReference(CredentialRef reference) {
        if (!"provider".equals(reference.namespace())) {
            throw PersistenceException.invalidRequest("模型目录预览只能使用 Provider 凭据");
        }
    }

    private static CredentialMaterial decode(byte[] value) {
        CharBuffer buffer = null;
        char[] chars = null;
        try {
            buffer = StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(value));
            chars = new char[buffer.remaining()];
            buffer.get(chars);
            return new CredentialMaterial(chars);
        } catch (CharacterCodingException failure) {
            throw PersistenceException.invalidRequest("API Key 必须是有效的 UTF-8 文本");
        } finally {
            if (chars != null) {
                Arrays.fill(chars, '\0');
            }
            if (buffer != null && buffer.hasArray()) {
                Arrays.fill(buffer.array(), '\0');
            }
        }
    }
}
