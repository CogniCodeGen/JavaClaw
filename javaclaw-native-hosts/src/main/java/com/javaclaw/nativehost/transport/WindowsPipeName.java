package com.javaclaw.nativehost.transport;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

import com.javaclaw.nativehost.LocalRuntimeDirectories;

/** Windows Named Pipe 的受限逻辑名称；不接受路径、远程主机或命名空间前缀。 */
public final class WindowsPipeName {
    private static final Pattern VALID_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");
    private static final String NAMESPACE = "\\\\.\\pipe\\";

    private final String value;

    private WindowsPipeName(String value) {
        this.value = value;
    }

    /**
     * 校验逻辑名称。
     *
     * @param value 不含 {@code \\.\pipe\} 前缀的名称
     * @return 已验证名称
     * @throws IllegalArgumentException 名称为空、过长或包含路径字符
     */
    public static WindowsPipeName parse(String value) {
        String normalized = Objects.requireNonNull(value, "value").strip();
        if (!VALID_NAME.matcher(normalized).matches()) {
            throw new IllegalArgumentException("Named Pipe name must contain only letters, digits, '.', '_' or '-'");
        }
        return new WindowsPipeName(normalized);
    }

    /**
     * 为当前用户的独立数据根生成稳定且不泄露原路径的默认名称。
     *
     * <p>名称哈希只用于减少多用户会话冲突；访问控制仍由服务端的登录 SID DACL 保证。
     *
     * @return 当前用户默认名称
     */
    public static WindowsPipeName currentUserDefault() {
        String identity = System.getProperty("user.name") + "\n"
                + LocalRuntimeDirectories.dataDirectory().toString().toLowerCase(Locale.ROOT);
        return parse("javaclaw-app-server-v6-" + digest(identity).substring(0, 24));
    }

    /** @return 不含 Windows 命名空间前缀的逻辑名称 */
    public String value() {
        return value;
    }

    /** @return 传给 Win32 API 的完整本机路径 */
    public String nativePath() {
        return NAMESPACE + value;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof WindowsPipeName name && value.equals(name.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return value;
    }

    private static String digest(String value) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
