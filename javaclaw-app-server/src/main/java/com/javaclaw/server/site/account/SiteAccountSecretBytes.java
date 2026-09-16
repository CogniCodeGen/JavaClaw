package com.javaclaw.server.site.account;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** 只验证私有秘密帧，不把密码或登录态转换为可日志化 DTO。 */
final class SiteAccountSecretBytes {
    private SiteAccountSecretBytes() {}

    static void validate(byte[] secret) {
        Objects.requireNonNull(secret, "secret");
        if (secret.length < 3 || secret.length > 65_536) {
            throw new IllegalArgumentException("用户名密码包超出长度限制");
        }
        int separator = -1;
        for (int i = 0; i < secret.length; i++) {
            if (secret[i] == 0) {
                if (separator >= 0) {
                    throw new IllegalArgumentException("用户名密码包分隔符无效");
                }
                separator = i;
            }
        }
        if (separator < 1 || separator == secret.length - 1) {
            throw new IllegalArgumentException("用户名和密码不能为空");
        }
        try {
            java.nio.CharBuffer decoded = StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(secret));
            while (decoded.hasRemaining()) {
                decoded.put('\0');
            }
        } catch (CharacterCodingException invalid) {
            throw new IllegalArgumentException("用户名密码包不是有效 UTF-8", invalid);
        }
    }

    static void validateStorage(byte[] state) {
        Objects.requireNonNull(state, "state");
        if (state.length < 2 || state.length > 2 * 1024 * 1024 || state[0] != '{') {
            throw new IllegalArgumentException("登录态超出私有帧限制或格式无效");
        }
    }
}
