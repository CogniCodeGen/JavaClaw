package com.javaclaw.application.site;

import com.javaclaw.application.error.NotFoundException;
import com.javaclaw.application.error.ValidationException;

import java.net.IDN;
import java.net.URI;
import java.util.Locale;
import java.util.Objects;

/** 站点凭据列表与变更用例；不持有页面状态。 */
public final class SiteCredentialUseCase implements SiteCredentialApplicationService {

    private final SiteCredentialPort credentials;

    public SiteCredentialUseCase(SiteCredentialPort credentials) {
        this.credentials = Objects.requireNonNull(credentials, "credentials");
    }

    @Override
    public Snapshot snapshot() {
        return new Snapshot(credentials.list().stream().map(SiteCredentialUseCase::toView).toList(),
                credentials.storageDescription());
    }

    @Override
    public Snapshot save(SaveCommand command) {
        Objects.requireNonNull(command, "command");
        String id = command.id().strip();
        String name = required(command.name(), "展示名");
        String host = normalizeHost(command.hostPattern());
        String loginUrl = normalizeOptionalUrl(command.loginUrl());
        SiteCredentialPort.Entry previous = id.isEmpty() ? null : credentials.list().stream()
                .filter(entry -> id.equals(entry.id()))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("未找到站点凭据：" + id));
        long createdAt = previous == null ? 0 : previous.createdAt();
        long lastUsedAt = previous == null ? 0 : previous.lastUsedAt();
        boolean hasSession = previous != null && previous.hasSession();
        credentials.save(new SiteCredentialPort.Entry(
                id, name, host, loginUrl, command.username().strip(), command.password(),
                emptyToNull(command.notes()), createdAt, lastUsedAt, hasSession));
        return snapshot();
    }

    @Override
    public Snapshot delete(String credentialId) {
        String id = required(credentialId, "站点凭据 ID");
        if (!credentials.delete(id)) {
            throw new NotFoundException("未找到站点凭据：" + id);
        }
        return snapshot();
    }

    @Override
    public Snapshot clearSession(String credentialId) {
        String id = required(credentialId, "站点凭据 ID");
        if (!credentials.clearSession(id)) {
            throw new NotFoundException("未找到站点凭据：" + id);
        }
        return snapshot();
    }

    private static Credential toView(SiteCredentialPort.Entry entry) {
        return new Credential(entry.id(), entry.name(), entry.hostPattern(), entry.loginUrl(),
                entry.username(), entry.password(), entry.notes(), entry.createdAt(),
                entry.lastUsedAt(), entry.hasSession());
    }

    private static String normalizeHost(String raw) {
        String value = required(raw, "主机匹配").toLowerCase(Locale.ROOT);
        boolean wildcard = value.startsWith("*.");
        String candidate = wildcard ? value.substring(2) : value;
        if (!candidate.contains("://")) candidate = "https://" + candidate;
        try {
            String host = URI.create(candidate).getHost();
            if (host == null || host.isBlank()) throw new IllegalArgumentException("invalid host");
            String ascii = IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES)
                    .toLowerCase(Locale.ROOT);
            if (ascii.startsWith(".") || ascii.endsWith(".")) {
                throw new IllegalArgumentException("invalid host");
            }
            return wildcard ? "*." + ascii : ascii;
        } catch (IllegalArgumentException failure) {
            throw new ValidationException("主机匹配格式无效：" + value);
        }
    }

    private static String normalizeOptionalUrl(String raw) {
        String value = emptyToNull(raw);
        if (value == null) return null;
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme();
            if (uri.getHost() == null
                    || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
                throw new IllegalArgumentException("unsupported URL");
            }
            return uri.toString();
        } catch (IllegalArgumentException failure) {
            throw new ValidationException("登录页 URL 必须是有效的 http/https 地址");
        }
    }

    private static String required(String value, String label) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isEmpty()) throw new ValidationException(label + "不能为空");
        return normalized;
    }

    private static String emptyToNull(String value) {
        if (value == null) return null;
        String normalized = value.strip();
        return normalized.isEmpty() ? null : normalized;
    }
}
