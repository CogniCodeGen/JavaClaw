package com.javaclaw.service.runner;

import com.javaclaw.service.api.PluginLogger;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.util.function.BiConsumer;

final class RunnerLogger implements PluginLogger {
    private final String pluginId;
    private final BiConsumer<Kind, String> typedLogs;

    RunnerLogger(String pluginId) {
        this(pluginId, null);
    }

    RunnerLogger(String pluginId, BiConsumer<Kind, String> typedLogs) {
        this.pluginId = pluginId;
        this.typedLogs = typedLogs;
    }

    @Override public void debug(String message) { write("DEBUG", message, null); }
    @Override public void info(String message) { write("INFO", message, null); }
    @Override public void info(Kind kind, String message) {
        if (kind == null || typedLogs == null) {
            info(message);
            return;
        }
        typedLogs.accept(kind, safe(message));
    }
    @Override public void warn(String message) { write("WARN", message, null); }
    @Override public void error(String message, Throwable failure) {
        write("ERROR", message, failure);
    }

    private void write(String level, String message, Throwable failure) {
        StringBuilder line = new StringBuilder()
                .append(Instant.now()).append(' ')
                .append(level).append(" service-plugin.").append(pluginId).append(" - ")
                .append(safe(message));
        if (failure != null) {
            StringWriter trace = new StringWriter();
            failure.printStackTrace(new PrintWriter(trace));
            line.append(System.lineSeparator()).append(safe(trace.toString()));
        }
        System.err.println(line);
    }

    private static String safe(String message) {
        if (message == null) return "";
        return message.replaceAll("(?i)(authorization|api[-_ ]?key|bearer|token)\\s*[:=]\\s*\\S+",
                "$1=<redacted>");
    }
}
