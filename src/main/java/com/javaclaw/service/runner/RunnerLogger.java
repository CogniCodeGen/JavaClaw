package com.javaclaw.service.runner;

import com.javaclaw.service.api.PluginLogger;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;

final class RunnerLogger implements PluginLogger {
    private final String pluginId;

    RunnerLogger(String pluginId) {
        this.pluginId = pluginId;
    }

    @Override public void debug(String message) { write("DEBUG", message, null); }
    @Override public void info(String message) { write("INFO", message, null); }
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
