package com.javaclaw.framework.spi;

import com.javaclaw.framework.api.AgentDefinitionRef;
import com.javaclaw.framework.api.RunProfileRef;

/** Repairs workspace-owned built-in definitions before a latest-version request is compiled. */
public interface DefinitionProvisioner {
    DefinitionProvisioner NONE = new DefinitionProvisioner() {
        @Override
        public boolean ensureAgent(String workspaceId, AgentDefinitionRef reference) {
            return false;
        }

        @Override
        public boolean ensureProfile(String workspaceId, RunProfileRef reference) {
            return false;
        }
    };

    boolean ensureAgent(String workspaceId, AgentDefinitionRef reference);

    boolean ensureProfile(String workspaceId, RunProfileRef reference);
}
