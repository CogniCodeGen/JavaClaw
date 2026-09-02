package com.javaclaw.server.config;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.CredentialRef;
import com.javaclaw.model.CredentialMaterial;
import com.javaclaw.model.ProviderCredentialResolver;
import com.javaclaw.server.persistence.PersistenceException;
import com.javaclaw.server.security.vault.SecretVaultService;
import com.javaclaw.server.security.vault.VaultException;

/** 将 Vault 的短生命周期 UTF-8 字节映射为模型 Adapter 可清零的字符材料。 */
public final class VaultProviderCredentialResolver implements ProviderCredentialResolver {
    private final SecretVaultService vault;

    /**
     * 创建 fail-closed 解析器。
     *
     * @param vault Secret Vault
     */
    public VaultProviderCredentialResolver(SecretVaultService vault) {
        this.vault = Objects.requireNonNull(vault, "vault");
    }

    /**
     * 解析引用；Vault 锁定、引用失效或内容不是合法 UTF-8 时返回空。
     *
     * @param reference opaque Vault 引用
     * @return 可清零凭据材料
     */
    @Override
    public Optional<CredentialMaterial> resolve(CredentialRef reference) {
        try {
            return Optional.of(vault.use(reference, bytes -> material(decode(bytes))));
        } catch (VaultException | PersistenceException failure) {
            return Optional.empty();
        }
    }

    private static CredentialMaterial material(char[] value) {
        try {
            return new CredentialMaterial(value);
        } finally {
            Arrays.fill(value, '\u0000');
        }
    }

    private static char[] decode(byte[] value) throws java.nio.charset.CharacterCodingException {
        var decoder = StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        CharBuffer buffer = decoder.decode(ByteBuffer.wrap(value));
        char[] result = new char[buffer.remaining()];
        buffer.get(result);
        if (buffer.hasArray()) {
            Arrays.fill(buffer.array(), '\u0000');
        }
        return result;
    }
}
