package com.javaclaw.framework.spi;

/** Trusted system extension entry point. It cannot replace the Agent run state machine. */
public interface AgentFrameworkExtension {
    ExtensionDescriptor descriptor();

    void register(ExtensionRegistrar registrar);

    default void start(ExtensionContext context) {}

    default void stop() {}
}
