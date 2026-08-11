package com.javaclaw.chat;

import java.util.function.Supplier;

/** Produces the compact model label shown in message headers. */
final class ChatModelLabel {

    private ChatModelLabel() { }

    static String resolve(Supplier<String> configuredName) {
        try {
            String name = configuredName.get();
            if (name == null || name.isBlank()) return "模型";
            String[] parts = name.split("[-/]");
            return parts.length >= 2
                    ? parts[parts.length - 2] + " " + parts[parts.length - 1]
                    : name;
        } catch (RuntimeException unavailable) {
            return "模型";
        }
    }
}
