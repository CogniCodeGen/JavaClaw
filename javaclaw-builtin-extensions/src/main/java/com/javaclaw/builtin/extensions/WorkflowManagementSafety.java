package com.javaclaw.builtin.extensions;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Set;

import com.javaclaw.builtin.contracts.ContractDigests;

/** Workflow 管理值的敏感字段、有限数值和确定性行标识门禁。 */
final class WorkflowManagementSafety {
    private static final Set<String> SECRET_MARKERS = Set.of(
            "accesskey",
            "apikey",
            "authorization",
            "bearer",
            "cookie",
            "credential",
            "password",
            "passwd",
            "privatekey",
            "secret",
            "sessionid",
            "token");

    private WorkflowManagementSafety() {}

    static void requireNonSecretField(String name) {
        String normalized = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        if (SECRET_MARKERS.stream().anyMatch(normalized::contains)) {
            throw new IllegalArgumentException("Workflow fixed Tool arguments cannot contain Secret fields");
        }
    }

    static BigDecimal number(String value) {
        try {
            return finite(new BigDecimal(value).stripTrailingZeros());
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("Tool number argument is invalid", failure);
        }
    }

    static String numberText(Number value) {
        return finite(new BigDecimal(value.toString()).stripTrailingZeros()).toPlainString();
    }

    static String stableRowId(String kind, String... values) {
        StringBuilder framed = new StringBuilder();
        append(framed, kind);
        for (String value : values) {
            append(framed, value);
        }
        return ContractDigests.sha256(framed.toString()).substring(0, 24);
    }

    private static BigDecimal finite(BigDecimal value) {
        if (value.precision() > 100 || value.scale() < -100 || value.scale() > 100) {
            throw new IllegalArgumentException("Tool number argument exceeds precision or scale limits");
        }
        if (value.toPlainString().length() > 256) {
            throw new IllegalArgumentException("Tool number argument expands beyond 256 characters");
        }
        return value;
    }

    private static void append(StringBuilder target, String value) {
        target.append(value.length()).append(':').append(value);
    }
}
