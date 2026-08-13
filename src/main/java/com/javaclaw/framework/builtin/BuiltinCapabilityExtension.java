package com.javaclaw.framework.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.javaclaw.framework.api.CapabilityId;
import com.javaclaw.framework.spi.*;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** Reusable shell for built-in capabilities; behavior is still contributed through normal SPI. */
public class BuiltinCapabilityExtension implements AgentFrameworkExtension {
    private static final SemanticVersion VERSION = SemanticVersion.parse("2.0.0");

    private final ExtensionDescriptor descriptor;
    private final CapabilityDescriptor capability;
    private final Consumer<ExtensionRegistrar> contributions;

    public BuiltinCapabilityExtension(
            String id,
            String displayName,
            String description,
            JsonNode configurationSchema,
            JsonNode uiSchema,
            List<ExtensionDependency> dependencies,
            Consumer<ExtensionRegistrar> contributions) {
        this.descriptor = new ExtensionDescriptor(id, VERSION,
                ">=2.0.0 <3.0.0", ">=2.0.0 <3.0.0", dependencies, Set.of(),
                ExtensionScope.PLAN_SCOPED, HotUpdateCompatibility.PLAN_ISOLATED,
                1, Map.of("configuration", configurationSchema));
        this.capability = new CapabilityDescriptor(new CapabilityId(id), displayName,
                description, configurationSchema, uiSchema);
        this.contributions = contributions == null ? ignored -> {} : contributions;
    }

    @Override
    public ExtensionDescriptor descriptor() { return descriptor; }

    @Override
    public void register(ExtensionRegistrar registrar) {
        registrar.capability(capability, (id, configuration, context) -> configuration.deepCopy());
        contributions.accept(registrar);
    }
}
