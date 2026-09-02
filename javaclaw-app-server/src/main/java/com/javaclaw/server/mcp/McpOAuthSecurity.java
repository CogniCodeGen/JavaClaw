package com.javaclaw.server.mcp;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Locale;
import java.util.Objects;

import com.javaclaw.api.McpHashes;
import com.javaclaw.server.persistence.CommandIdentity;
import com.javaclaw.server.persistence.PersistenceException;

/**
 * MCP OAuth 的 PKCE、回调 URI 与内部命令身份规则。
 *
 * <p>此类型不保存长期状态。PKCE 字符串只在一次调用栈中存在，编码后的字节由调用方立即写入 Vault 并清零。
 */
final class McpOAuthSecurity {
    private static final int RANDOM_BYTES = 32;

    private final SecureRandom random = new SecureRandom();

    /** @return 新生成的 S256 PKCE verifier、state 与 challenge */
    PkceMaterial generate() {
        byte[] verifierBytes = randomBytes();
        byte[] stateBytes = randomBytes();
        try {
            return new PkceMaterial(base64(verifierBytes), base64(stateBytes));
        } finally {
            Arrays.fill(verifierBytes, (byte) 0);
            Arrays.fill(stateBytes, (byte) 0);
        }
    }

    /** @param encoded Vault 解密出的临时字节 @return PKCE 材料 */
    PkceMaterial decode(byte[] encoded) {
        String[] parts = new String(encoded, StandardCharsets.US_ASCII).split("\\n", -1);
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            throw PersistenceException.invalidRequest("OAuth PKCE Vault 内容无效");
        }
        return new PkceMaterial(parts[0], parts[1]);
    }

    /** @param value 配置值 @return 规范化且精确的 loopback 拦截 URI */
    URI requireLoopback(URI value) {
        URI uri = Objects.requireNonNull(value, "redirectUri").normalize();
        boolean valid = uri.isAbsolute()
                && "http".equalsIgnoreCase(uri.getScheme())
                && ("127.0.0.1".equals(uri.getHost()) || "::1".equals(uri.getHost()))
                && uri.getPort() > 0
                && uri.getUserInfo() == null
                && uri.getQuery() == null
                && uri.getFragment() == null;
        if (!valid) {
            throw new IllegalArgumentException("OAuth redirectUri must be an exact loopback HTTP URI");
        }
        return uri;
    }

    /** @param value Worker 截获值 @param redirect 固定目标 @return 经精确校验的 callback */
    URI requireCallback(URI value, URI redirect) {
        URI uri = Objects.requireNonNull(value, "callbackUri").normalize();
        boolean targetMatches = Objects.equals(uri.getScheme(), redirect.getScheme())
                && Objects.equals(uri.getHost(), redirect.getHost())
                && uri.getPort() == redirect.getPort()
                && Objects.equals(uri.getPath(), redirect.getPath())
                && uri.getUserInfo() == null
                && uri.getFragment() == null;
        if (!targetMatches || uri.getQuery() == null || uri.getQuery().isBlank()) {
            throw new IllegalArgumentException("OAuth callbackUri does not match the private loopback intercept");
        }
        return uri;
    }

    /** @param uri HTTPS 授权 URI @return 不含路径和查询的精确 Origin */
    URI origin(URI uri) {
        try {
            return new URI(
                    uri.getScheme().toLowerCase(Locale.ROOT), null, uri.getHost(), uri.getPort(), null, null, null);
        } catch (URISyntaxException impossible) {
            throw new IllegalArgumentException("OAuth authorization origin is invalid", impossible);
        }
    }

    /** @param value 外部标识 @return 经语法校验的稳定标识 */
    String identifier(String value) {
        String id = Objects.requireNonNull(value, "id").strip();
        if (!id.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,239}")) {
            throw new IllegalArgumentException("OAuth id contains unsupported characters");
        }
        return id;
    }

    /** @param identity 外部写命令 @return 创建 PKCE 临时 Secret 的内部身份 */
    CommandIdentity vaultCreateIdentity(CommandIdentity identity) {
        return internal(identity, "credential/create", "oauth-create:", 0);
    }

    /** @param identity 完成命令 @param session 冻结会话 @return token 轮换身份 */
    CommandIdentity vaultRotateIdentity(CommandIdentity identity, McpOAuthSession session) {
        long expectedRevision = session.state() == com.javaclaw.api.McpOAuthState.PENDING ? 1 : 2;
        return internal(identity, "credential/rotate", "oauth-rotate:", expectedRevision);
    }

    /** @param identity 完成命令 @param revision Endpoint revision @return 凭据绑定身份 */
    CommandIdentity oauthAttachIdentity(CommandIdentity identity, long revision) {
        return internal(identity, "mcp/endpoint/oauth-attach", "oauth-attach:", revision);
    }

    /** @param authorizationId 会话标识 @param callbackUri 私有 callback @return 不含明文 callback 的身份 */
    CommandIdentity browserCompleteIdentity(String authorizationId, URI callbackUri) {
        String callbackDigest = McpHashes.sha256(callbackUri.toASCIIString());
        return new CommandIdentity(
                "mcp/oauth/browser-complete",
                "oauth-browser-complete:" + authorizationId,
                0,
                McpHashes.sha256(authorizationId + ':' + callbackDigest));
    }

    /** @param session 终态会话 @param revision 临时 Secret revision @return 清理身份 */
    CommandIdentity cleanupIdentity(McpOAuthSession session, long revision) {
        return new CommandIdentity(
                "credential/clear",
                "oauth-terminal-clear:" + session.id(),
                revision,
                McpHashes.sha256(session.id() + ':' + session.state() + ':' + revision));
    }

    private byte[] randomBytes() {
        byte[] value = new byte[RANDOM_BYTES];
        random.nextBytes(value);
        return value;
    }

    private static CommandIdentity internal(
            CommandIdentity source, String method, String prefix, long expectedRevision) {
        return new CommandIdentity(
                method,
                prefix + source.idempotencyKey(),
                expectedRevision,
                McpHashes.sha256(method + ':' + source.requestDigest()));
    }

    private static String base64(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    /**
     * 单次 PKCE 材料。
     *
     * @param verifier 高熵 verifier
     * @param state 高熵回调绑定值
     */
    record PkceMaterial(String verifier, String state) {
        /** @return S256 challenge */
        String challenge() {
            try {
                byte[] digest =
                        MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
                return base64(digest);
            } catch (NoSuchAlgorithmException impossible) {
                throw new IllegalStateException("SHA-256 is unavailable", impossible);
            }
        }

        /** @return 仅用于 Vault 写入的临时编码 */
        byte[] encoded() {
            return (verifier + '\n' + state).getBytes(StandardCharsets.US_ASCII);
        }
    }
}
