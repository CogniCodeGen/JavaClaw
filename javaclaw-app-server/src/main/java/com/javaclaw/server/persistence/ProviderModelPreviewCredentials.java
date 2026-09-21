package com.javaclaw.server.persistence;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import com.javaclaw.api.ProviderAuthentication;
import com.javaclaw.api.ProviderConfigurationSource;
import com.javaclaw.api.ProviderCredentialChange;
import com.javaclaw.api.ProviderEndpoint;
import com.javaclaw.api.ProviderLifecycle;
import com.javaclaw.api.ProviderModelPreviewRequest;
import com.javaclaw.model.CredentialMaterial;

/**
 * 预览连接的只读凭据准备，不创建 Vault 记录或 Provider 版本。
 *
 * <p>沿用 Provider → Vault 的锁顺序，使源版本、当前凭据版本与实际材料保持一致；网络请求只在锁外发生。
 */
final class ProviderModelPreviewCredentials {
    private final ProviderService providers;
    private final ProviderModelPreviewCredentialPort credentialReader;

    ProviderModelPreviewCredentials(ProviderService providers, ProviderModelPreviewCredentialPort credentialReader) {
        this.providers = Objects.requireNonNull(providers, "providers");
        this.credentialReader = Objects.requireNonNull(credentialReader, "credentialReader");
    }

    Optional<CredentialMaterial> prepare(
            CommandIdentity identity, ProviderModelPreviewRequest request, Supplier<byte[]> plaintext) {
        requireCredentialIntent(request);
        return providers.coordinateSerialCommand(identity, () -> {
            Optional<ProviderEndpoint> source = request.source().map(this::requireSource);
            Optional<CredentialMaterial> material =
                    source.isPresent() ? readSource(request, source.orElseThrow()) : Optional.empty();
            return request.credentialChange() == ProviderCredentialChange.REPLACE
                    ? Optional.of(temporary(plaintext))
                    : material;
        });
    }

    private static void requireCredentialIntent(ProviderModelPreviewRequest request) {
        boolean noAuthentication = request.connection().authentication() == ProviderAuthentication.NONE;
        if (noAuthentication && request.credentialChange() == ProviderCredentialChange.REPLACE
                || !noAuthentication && request.credentialChange() == ProviderCredentialChange.CLEAR) {
            throw PersistenceException.invalidRequest("预览鉴权方式与凭据操作不一致");
        }
        if (!noAuthentication
                && request.credentialChange() == ProviderCredentialChange.KEEP
                && request.source().isEmpty()) {
            throw PersistenceException.invalidRequest("保留 API Key 必须指定已保存的来源 Provider");
        }
    }

    private ProviderEndpoint requireSource(ProviderConfigurationSource source) {
        ProviderEndpoint current = providers.requireLatestForMutation(source.providerId());
        if (current.revision() != source.providerRevision()) {
            throw PersistenceException.revisionConflict("预览来源的 Provider 版本已变化，请重新打开配置");
        }
        if (current.lifecycle() == ProviderLifecycle.ARCHIVED) {
            throw PersistenceException.invalidRequest("已归档的 Provider 不能预览模型目录");
        }
        return current;
    }

    private Optional<CredentialMaterial> readSource(ProviderModelPreviewRequest request, ProviderEndpoint source) {
        boolean materialRequired = request.credentialChange() == ProviderCredentialChange.KEEP
                && request.connection().authentication() == ProviderAuthentication.API_KEY;
        if (materialRequired
                && (source.spec().adapter() != request.connection().adapter()
                        || !source.spec().baseUri().equals(request.connection().baseUri()))) {
            throw PersistenceException.invalidRequest("变更地址或协议后必须重新输入 API Key");
        }
        return credentialReader.read(
                source.spec().credential(), request.source().orElseThrow().credentialRevision(), materialRequired);
    }

    private static CredentialMaterial temporary(Supplier<byte[]> plaintext) {
        byte[] value = Objects.requireNonNull(plaintext.get(), "plaintext");
        try {
            return decode(value);
        } finally {
            Arrays.fill(value, (byte) 0);
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
