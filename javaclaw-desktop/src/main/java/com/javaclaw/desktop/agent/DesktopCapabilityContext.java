package com.javaclaw.desktop.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.javaclaw.config.AgentConfig;
import com.javaclaw.framework.spi.ToolRuntimeContextProvider;

import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/** Reads the current saved application switch without touching native desktop APIs. */
public final class DesktopCapabilityContext implements ToolRuntimeContextProvider {
    private final BooleanSupplier enabled;

    public DesktopCapabilityContext(AgentConfig settings) {
        this(Objects.requireNonNull(settings, "settings")::isComputerAppAccessEnabled);
    }

    DesktopCapabilityContext(BooleanSupplier enabled) {
        this.enabled = Objects.requireNonNull(enabled, "enabled");
    }

    @Override
    public List<JsonNode> currentContext() {
        return List.of(JsonNodeFactory.instance.objectNode()
                .put("kind", "desktop.access.current")
                .put("settingEnabled", enabled.getAsBoolean())
                .put("systemStatus", "NOT_CHECKED")
                .put("instruction", "Current saved switch overrides old assistant access claims. "
                        + "NOT_CHECKED means OS permissions are unknown. If enabled, do not report "
                        + "switch off; discover authorized desktop tools and use desktop_session_probe "
                        + "before declaring access blocked."));
    }
}
