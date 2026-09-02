package com.javaclaw.builtin.extensions;

import java.util.Collection;
import java.util.Locale;
import java.util.Set;

/** 内置扩展共享的确定性文本过滤规则。 */
final class ExtensionSearch {
    private ExtensionSearch() {}

    static boolean contains(String query, String... values) {
        String expected = normalize(query);
        for (String value : values) {
            if (normalize(value).contains(expected)) {
                return true;
            }
        }
        return false;
    }

    static boolean contains(String query, Collection<String> values) {
        String expected = normalize(query);
        return values.stream().map(ExtensionSearch::normalize).anyMatch(value -> value.contains(expected));
    }

    static boolean matchesValue(Set<String> filters, String value) {
        if (filters.isEmpty()) {
            return true;
        }
        String actual = normalize(value);
        return filters.stream().map(ExtensionSearch::normalize).anyMatch(actual::equals);
    }

    static boolean containsAll(Set<String> values, Set<String> required) {
        Set<String> normalized = values.stream()
                .map(ExtensionSearch::normalize)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        return required.stream().map(ExtensionSearch::normalize).allMatch(normalized::contains);
    }

    private static String normalize(String value) {
        return value.strip().toLowerCase(Locale.ROOT);
    }
}
