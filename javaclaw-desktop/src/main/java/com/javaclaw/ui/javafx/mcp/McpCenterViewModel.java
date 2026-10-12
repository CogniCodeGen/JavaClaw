package com.javaclaw.ui.javafx.mcp;

import com.javaclaw.application.mcp.McpManagementApplicationService.Server;
import com.javaclaw.application.mcp.McpManagementApplicationService.Snapshot;
import com.javaclaw.application.mcp.McpManagementApplicationService.State;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

import java.util.List;
import java.util.Locale;

/** MCP 中心页面状态；不持有应用服务、仓储或窗口。 */
public final class McpCenterViewModel {

    enum Filter { ALL, RUNNING, FAILED, STOPPED }

    private final ObjectProperty<Snapshot> snapshot = new SimpleObjectProperty<>(
            this, "snapshot", new Snapshot(List.of(), ""));
    private final ObjectProperty<Filter> filter = new SimpleObjectProperty<>(
            this, "filter", Filter.ALL);
    private final StringProperty query = new SimpleStringProperty(this, "query", "");
    private final StringProperty status = new SimpleStringProperty(this, "status", "");
    private final BooleanProperty statusError = new SimpleBooleanProperty(this, "statusError");
    private final BooleanProperty loading = new SimpleBooleanProperty(this, "loading");
    private final BooleanProperty mutating = new SimpleBooleanProperty(this, "mutating");

    ObjectProperty<Snapshot> snapshotProperty() { return snapshot; }
    ObjectProperty<Filter> filterProperty() { return filter; }
    StringProperty queryProperty() { return query; }
    StringProperty statusProperty() { return status; }
    BooleanProperty statusErrorProperty() { return statusError; }
    BooleanProperty loadingProperty() { return loading; }
    BooleanProperty mutatingProperty() { return mutating; }

    void apply(Snapshot value) {
        snapshot.set(java.util.Objects.requireNonNull(value, "value"));
    }

    void showStatus(String message, boolean error) {
        status.set(message == null ? "" : message);
        statusError.set(error);
    }

    List<Server> visibleServers() {
        String normalized = query.get() == null ? "" : query.get().strip().toLowerCase(Locale.ROOT);
        return snapshot.get().servers().stream()
                .filter(this::matchesFilter)
                .filter(server -> normalized.isEmpty() || searchableText(server).contains(normalized))
                .toList();
    }

    Counts counts() {
        int running = 0;
        int failed = 0;
        int stopped = 0;
        int enabled = 0;
        int tools = 0;
        String firstFailure = "";
        for (Server server : snapshot.get().servers()) {
            if (server.enabled()) enabled++;
            if (server.state() == State.RUNNING || server.state() == State.STARTING) running++;
            else if (server.state() == State.FAILED) {
                failed++;
                if (firstFailure.isEmpty()) {
                    firstFailure = server.name() + ": " + (server.startupError().isBlank()
                            ? "启动失败" : server.startupError());
                }
            } else stopped++;
            if (server.state() == State.RUNNING) tools += server.tools().size();
        }
        return new Counts(snapshot.get().servers().size(), running, failed, stopped,
                enabled, tools, firstFailure);
    }

    String contentTitle(boolean standalone) {
        return switch (filter.get()) {
            case RUNNING -> "运行中的服务器";
            case FAILED -> "需要处理";
            case STOPPED -> "已停止的服务器";
            case ALL -> standalone ? "全部服务器" : "MCP 服务器";
        };
    }

    private boolean matchesFilter(Server server) {
        return switch (filter.get()) {
            case RUNNING -> server.state() == State.RUNNING || server.state() == State.STARTING;
            case FAILED -> server.state() == State.FAILED;
            case STOPPED -> server.state() == State.STOPPED;
            case ALL -> true;
        };
    }

    private static String searchableText(Server server) {
        return String.join(" ", server.name(), server.command(), server.url(),
                server.transport().name(), String.join(" ", server.arguments()))
                .toLowerCase(Locale.ROOT);
    }

    record Counts(
            int total,
            int running,
            int failed,
            int stopped,
            int enabled,
            int tools,
            String firstFailure) { }
}
