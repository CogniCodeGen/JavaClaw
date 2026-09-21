package com.javaclaw.browser.worker;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import com.javaclaw.browser.protocol.BrowserWorkerProtocol;
import com.javaclaw.protocol.CanonicalJson;

/** 完成登记只导出最终网站可使用的 Cookie 和该精确 Origin 的存储，不携带其他登录提供方的状态。 */
final class RegistrationStorage {
    private RegistrationStorage() {}

    static byte[] filter(String input, URI origin) {
        if (input.length() > BrowserWorkerProtocol.MAXIMUM_STATE_BYTES) {
            throw new IllegalArgumentException("Browser registration state exceeds limit");
        }
        CanonicalJson json = new CanonicalJson();
        Map<?, ?> state = json.decode(json.parse(input), Map.class);
        List<?> cookies = array(state, "cookies").stream()
                .filter(value -> value instanceof Map<?, ?> cookie && cookieMatches(cookie, origin.getHost()))
                .toList();
        List<?> origins = array(state, "origins").stream()
                .filter(value ->
                        value instanceof Map<?, ?> stored && origin.toString().equals(stored.get("origin")))
                .toList();
        byte[] bytes = json.encode(Map.of("cookies", cookies, "origins", origins))
                .json()
                .getBytes(StandardCharsets.UTF_8);
        if (bytes.length > BrowserWorkerProtocol.MAXIMUM_STATE_BYTES) {
            java.util.Arrays.fill(bytes, (byte) 0);
            throw new IllegalArgumentException("Browser registration state exceeds limit");
        }
        return bytes;
    }

    private static List<?> array(Map<?, ?> source, String key) {
        Object value = source.get(key);
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException("Browser registration storage is invalid");
        }
        return list;
    }

    private static boolean cookieMatches(Map<?, ?> cookie, String host) {
        if (!(cookie.get("domain") instanceof String domain) || domain.isBlank()) {
            return false;
        }
        String normalized = host.toLowerCase(java.util.Locale.ROOT);
        String checked = domain.toLowerCase(java.util.Locale.ROOT);
        return checked.startsWith(".")
                ? normalized.equals(checked.substring(1)) || normalized.endsWith(checked)
                : normalized.equals(checked);
    }
}
