package com.javaclaw.extensionfixture;

import com.javaclaw.framework.spi.AgentFrameworkExtension;
import com.javaclaw.framework.spi.ExtensionDescriptor;
import com.javaclaw.framework.spi.ExtensionRegistrar;
import com.javaclaw.framework.spi.ExtensionScope;
import com.javaclaw.framework.spi.HotUpdateCompatibility;
import com.javaclaw.framework.spi.SemanticVersion;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Test-only service provider that is packaged into a real external extension JAR. */
public final class FixtureAgentExtension implements AgentFrameworkExtension {
    @Override
    public ExtensionDescriptor descriptor() {
        return new ExtensionDescriptor(
                "fixture.agent", SemanticVersion.parse("1.0.0"),
                ">=2.0.0 <3.0.0", ">=2.0.0 <3.0.0", List.of(), Set.of(),
                ExtensionScope.PLAN_SCOPED, HotUpdateCompatibility.PLAN_ISOLATED,
                0, Map.of());
    }

    @Override
    public void register(ExtensionRegistrar registrar) {
        // The install fixture only needs a valid descriptor and service entry.
    }
}
