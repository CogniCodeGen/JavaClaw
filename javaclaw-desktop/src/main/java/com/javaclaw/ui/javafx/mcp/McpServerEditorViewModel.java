package com.javaclaw.ui.javafx.mcp;

import com.javaclaw.application.mcp.McpManagementApplicationService.Transport;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

/** MCP 服务器编辑弹窗状态；不持有运行时或仓储。 */
final class McpServerEditorViewModel {
    private final StringProperty originalName = new SimpleStringProperty(this, "originalName", "");
    private final StringProperty name = new SimpleStringProperty(this, "name", "");
    private final StringProperty command = new SimpleStringProperty(this, "command", "");
    private final StringProperty arguments = new SimpleStringProperty(this, "arguments", "");
    private final StringProperty url = new SimpleStringProperty(this, "url", "");
    private final BooleanProperty http = new SimpleBooleanProperty(this, "http");
    private final BooleanProperty enabled = new SimpleBooleanProperty(this, "enabled", true);
    private final StringProperty error = new SimpleStringProperty(this, "error", "");
    private final StringProperty testResult = new SimpleStringProperty(this, "testResult", "");
    private final BooleanProperty testing = new SimpleBooleanProperty(this, "testing");

    StringProperty originalNameProperty() { return originalName; }
    StringProperty nameProperty() { return name; }
    StringProperty commandProperty() { return command; }
    StringProperty argumentsProperty() { return arguments; }
    StringProperty urlProperty() { return url; }
    BooleanProperty httpProperty() { return http; }
    BooleanProperty enabledProperty() { return enabled; }
    StringProperty errorProperty() { return error; }
    StringProperty testResultProperty() { return testResult; }
    BooleanProperty testingProperty() { return testing; }

    Transport transport() { return http.get() ? Transport.HTTP : Transport.STDIO; }
}
