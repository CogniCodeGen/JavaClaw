package com.javaclaw.application.agent;

import com.javaclaw.application.agent.AgentManagementApplicationService.Agent;
import com.javaclaw.application.agent.AgentManagementApplicationService.OptimizePromptCommand;
import com.javaclaw.application.agent.AgentManagementApplicationService.SaveAgentCommand;
import com.javaclaw.application.error.ConflictException;
import com.javaclaw.application.error.RejectedException;
import com.javaclaw.application.error.ValidationException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentManagementUseCaseTest {

    @Test
    void createSaveAndDeleteReturnImmutableCatalogs() {
        FakeDefinitions definitions = new FakeDefinitions();
        AgentManagementUseCase useCase = new AgentManagementUseCase(
                definitions, (name, description, draft) -> "optimized");

        var created = useCase.create();
        String id = created.agent().id();
        var saved = useCase.save(new SaveAgentCommand(
                id, "Java 专家", "java_expert", "描述", "提示词", 5, true));

        assertEquals("Java 专家", saved.require(id).name());
        assertThrows(UnsupportedOperationException.class,
                () -> saved.agents().add(created.agent()));
        assertTrue(useCase.delete(id).agents().stream().noneMatch(a -> a.id().equals(id)));
    }

    @Test
    void validatesFormAndRejectsDuplicateToolNames() {
        AgentManagementUseCase useCase = new AgentManagementUseCase(
                new FakeDefinitions(), (name, description, draft) -> "optimized");
        String id = useCase.create().agent().id();

        assertThrows(ValidationException.class, () -> useCase.save(
                new SaveAgentCommand(id, "", "ok", "", "", 1, true)));
        assertThrows(ValidationException.class, () -> useCase.save(
                new SaveAgentCommand(id, "名称", "bad-name", "", "", 1, true)));
        assertThrows(ConflictException.class, () -> useCase.save(
                new SaveAgentCommand(id, "名称", "coding_expert", "", "", 1, true)));
    }

    @Test
    void builtInsAreReadOnly() {
        AgentManagementUseCase useCase = new AgentManagementUseCase(
                new FakeDefinitions(), (name, description, draft) -> "optimized");

        assertThrows(ConflictException.class, () -> useCase.save(
                new SaveAgentCommand("builtin", "改名", "builtin", "", "", 1, true)));
        assertThrows(ConflictException.class, () -> useCase.delete("builtin"));
    }

    @Test
    void optimizationHasExplicitValidationAndRejectionSemantics() {
        AgentManagementUseCase valid = new AgentManagementUseCase(
                new FakeDefinitions(), (name, description, draft) -> "优化结果");
        assertEquals("优化结果", valid.optimize(new OptimizePromptCommand("名称", "", "")));

        assertThrows(ValidationException.class,
                () -> valid.optimize(new OptimizePromptCommand(" ", "", "")));
        AgentManagementUseCase empty = new AgentManagementUseCase(
                new FakeDefinitions(), (name, description, draft) -> null);
        assertThrows(RejectedException.class,
                () -> empty.optimize(new OptimizePromptCommand("名称", "", "")));
    }

    private static final class FakeDefinitions implements AgentDefinitionPort {
        private final List<Agent> agents = new ArrayList<>(List.of(
                new Agent("builtin", "内置", "coding_expert", "", "", 1, true, true)));
        private int sequence;

        @Override public List<Agent> list() { return List.copyOf(agents); }

        @Override
        public Agent create(String name) {
            Agent created = new Agent("custom-" + (++sequence), name,
                    "custom_" + sequence, "", "", 1, true, false);
            agents.add(created);
            return created;
        }

        @Override
        public void update(Agent agent) {
            agents.removeIf(existing -> existing.id().equals(agent.id()));
            agents.add(agent);
        }

        @Override
        public boolean delete(String id) {
            return agents.removeIf(agent -> agent.id().equals(id));
        }
    }
}
