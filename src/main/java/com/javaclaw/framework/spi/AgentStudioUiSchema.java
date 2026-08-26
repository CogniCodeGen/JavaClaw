package com.javaclaw.framework.spi;

/** Stable UI-Schema vocabulary understood by the schema-driven Agent Studio. */
public final class AgentStudioUiSchema {
    public static final String WIDGET = "ui:widget";
    public static final String OPTIONS = "ui:options";
    /**
     * Optional stricter bound used when authoring a new definition. The standard JSON Schema
     * maximum may remain wider so already-published configurations stay loadable after an
     * effective runtime limit is reduced.
     */
    public static final String AUTHORING_MAXIMUM = "x-javaclaw-authoringMaximum";
    public static final String MODEL_REF = "model-ref";
    public static final String TOOL_REF = "tool-ref";
    public static final String AGENT_REF = "agent-ref";
    public static final String WORKFLOW_REF = "workflow-ref";
    public static final String SECRET_REF = "secret-ref";

    private AgentStudioUiSchema() { }
}
