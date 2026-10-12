package com.javaclaw.ui.javafx.mcp;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

/** MCP 环境变量/Header 编辑行状态。 */
final class McpKeyValueViewModel {
    private final StringProperty key = new SimpleStringProperty(this, "key", "");
    private final StringProperty value = new SimpleStringProperty(this, "value", "");
    private final BooleanProperty secret = new SimpleBooleanProperty(this, "secret");

    StringProperty keyProperty() { return key; }
    StringProperty valueProperty() { return value; }
    BooleanProperty secretProperty() { return secret; }
}
