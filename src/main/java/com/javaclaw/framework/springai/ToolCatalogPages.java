package com.javaclaw.framework.springai;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Objects;

/** 按最终 JSON 字符长度稳定地划分工具目录页面。 */
final class ToolCatalogPages {
    private static final int DESCRIPTION_LIMIT = 80;

    private ToolCatalogPages() {
    }

    static ObjectNode list(List<Entry> matches, int page, int maxCharacters) {
        Objects.requireNonNull(matches, "matches");
        if (page < 1 || maxCharacters < 1) {
            throw new IllegalArgumentException("page and maxCharacters must be positive");
        }
        if (matches.isEmpty()) return result(page, 0);

        int cursor = 0;
        int currentPage = 1;
        while (cursor < matches.size()) {
            ObjectNode result = result(currentPage, matches.size());
            ArrayNode tools = (ArrayNode) result.get("tools");
            int first = cursor;
            while (cursor < matches.size()) {
                Entry entry = matches.get(cursor);
                tools.addObject().put("name", entry.name()).put("group", entry.group())
                        .put("description", truncate(entry.description(), DESCRIPTION_LIMIT));
                cursor++;
                result.put("pageSize", cursor - first);
                result.put("hasNext", cursor < matches.size());
                if (result.toString().length() > maxCharacters) {
                    tools.remove(tools.size() - 1);
                    cursor--;
                    result.put("pageSize", cursor - first);
                    result.put("hasNext", true);
                    if (cursor == first) return oversized(entry.name(), maxCharacters);
                    break;
                }
            }
            if (currentPage == page) return result;
            currentPage++;
        }
        return result(page, matches.size());
    }

    private static ObjectNode result(int page, int total) {
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        result.put("action", "list");
        result.put("page", page);
        result.put("pageSize", 0);
        result.put("total", total);
        result.put("hasNext", false);
        result.putArray("tools");
        return result;
    }

    private static ObjectNode oversized(String name, int maxCharacters) {
        String prefix = "tool catalog entry exceeds the tool result character budget: ";
        String label = truncate(name, 120);
        ObjectNode error = JsonNodeFactory.instance.objectNode().put("success", false);
        error.put("error", prefix + label);
        while (error.toString().length() > maxCharacters && !label.isEmpty()) {
            label = label.substring(0, label.length() - 1);
            error.put("error", prefix + label);
        }
        if (error.toString().length() <= maxCharacters) return error;
        error.put("error", "tool catalog entry exceeds result budget");
        if (error.toString().length() <= maxCharacters) return error;
        error.remove("error");
        if (error.toString().length() <= maxCharacters) return error;
        throw new IllegalArgumentException("maxCharacters is too small for a catalog error");
    }

    private static String truncate(String value, int limit) {
        Objects.requireNonNull(value, "value");
        return value.substring(0, Math.min(value.length(), limit));
    }

    record Entry(String name, String group, String description) {
        Entry {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(group, "group");
            Objects.requireNonNull(description, "description");
        }
    }
}
