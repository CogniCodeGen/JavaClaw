package com.javaclaw.application.workspace;

import com.javaclaw.application.error.ValidationException;
import com.javaclaw.application.workspace.WorkspaceApplicationService.WorkspaceSummary;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceUseCaseTest {

    @Test
    void normalizesNamesAndReturnsDurablePortResult() {
        FakePort port = new FakePort();
        WorkspaceUseCase useCase = new WorkspaceUseCase(port);

        WorkspaceSummary created = useCase.create("  研发  ");

        assertEquals("研发", created.name());
        assertEquals(List.of(created), useCase.list());
    }

    @Test
    void rejectsBlankCommandsBeforeCallingThePort() {
        WorkspaceUseCase useCase = new WorkspaceUseCase(new FakePort());

        assertThrows(ValidationException.class, () -> useCase.create("  "));
        assertThrows(ValidationException.class, () -> useCase.delete(null));
    }

    @Test
    void delegatesDeletionAndCurrentWorkspaceQueries() {
        FakePort port = new FakePort();
        WorkspaceUseCase useCase = new WorkspaceUseCase(port);
        WorkspaceSummary created = useCase.create("测试");

        assertEquals("current", useCase.currentWorkspaceId());
        assertTrue(useCase.delete(created.id()));
        assertTrue(useCase.list().isEmpty());
    }

    private static final class FakePort implements WorkspaceManagementPort {
        private final List<WorkspaceSummary> values = new ArrayList<>();

        @Override
        public List<WorkspaceSummary> list() {
            return values;
        }

        @Override
        public String currentWorkspaceId() {
            return "current";
        }

        @Override
        public WorkspaceSummary create(String name) {
            WorkspaceSummary created = new WorkspaceSummary("id-" + values.size(), name, "now");
            values.add(created);
            return created;
        }

        @Override
        public boolean delete(String workspaceId) {
            return values.removeIf(value -> value.id().equals(workspaceId));
        }
    }
}
