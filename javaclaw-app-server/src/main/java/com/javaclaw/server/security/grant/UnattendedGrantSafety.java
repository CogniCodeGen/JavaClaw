package com.javaclaw.server.security.grant;

import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

import com.javaclaw.api.UnattendedToolGrantDraft;
import com.javaclaw.protocol.CanonicalJson;

/** 无人值守授权创建时的静态参数安全规则。 */
final class UnattendedGrantSafety {
    private static final Set<String> INLINE_SECRET_FIELDS = Set.of(
            "authorization",
            "cookie",
            "password",
            "secret",
            "client_secret",
            "token",
            "access_token",
            "refresh_token",
            "apikey",
            "api_key",
            "api-key",
            "privatekey",
            "private_key",
            "browserstate",
            "browser_state");
    private static final Set<String> SENSITIVE_VARIABLE_TOKENS = Set.of(
            "recipient",
            "email",
            "address",
            "destination",
            "target",
            "to",
            "cc",
            "bcc",
            "origin",
            "url",
            "uri",
            "host",
            "hostname",
            "port",
            "network",
            "proxy",
            "ip",
            "dns",
            "endpoint",
            "scheme",
            "protocol",
            "socket",
            "secret",
            "credential",
            "token",
            "password",
            "cookie",
            "authorization",
            "command",
            "cmd",
            "argument",
            "args",
            "executable",
            "shell",
            "browser",
            "pty",
            "worktree",
            "workspace");

    private UnattendedGrantSafety() {}

    static UnattendedToolGrantDraft requireSafe(UnattendedToolGrantDraft draft, CanonicalJson json) {
        UnattendedToolGrantDraft checked = Objects.requireNonNull(draft, "draft");
        CanonicalJson checkedJson = Objects.requireNonNull(json, "json");
        Duration validity = checked.validity();
        if (validity.isZero()
                || validity.isNegative()
                || validity.compareTo(UnattendedToolGrantService.MAXIMUM_VALIDITY) > 0) {
            throw new IllegalArgumentException("unattended grant validity must be within 30 days");
        }
        if (checkedJson.containsAnyField(checked.fixedArguments(), INLINE_SECRET_FIELDS)) {
            throw new IllegalArgumentException("fixed arguments must use CredentialRef instead of inline Secret");
        }
        checked.variableStringFields().forEach(UnattendedGrantSafety::requireSafeVariableField);
        checkedJson.requireTopLevelStringFields(checked.fixedArguments(), checked.variableStringFields());
        return checked;
    }

    private static void requireSafeVariableField(String field) {
        String normalized = field.toLowerCase(Locale.ROOT).replaceAll("[._-]", "");
        if (SENSITIVE_VARIABLE_TOKENS.stream().anyMatch(normalized::contains)) {
            throw new IllegalArgumentException("sensitive field cannot be an unattended variable slot: " + field);
        }
    }
}
