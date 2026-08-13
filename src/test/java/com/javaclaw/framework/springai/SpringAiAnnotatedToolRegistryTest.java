package com.javaclaw.framework.springai;

import com.javaclaw.framework.spi.ToolContract;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SpringAiAnnotatedToolRegistryTest {

    @Test
    void missingContractFailsBeforeAHostToolIsConstructed() {
        assertThrows(IllegalStateException.class,
                () -> SpringAiAnnotatedToolRegistry.validateContracts(
                        List.of(MissingContractTool.class)));
    }

    @Test
    void methodOrClassContractIsAccepted() {
        assertDoesNotThrow(() -> SpringAiAnnotatedToolRegistry.validateContracts(
                        List.of(ClassContractTool.class, MethodContractTool.class)));
    }

    @Test
    void desktopInspectIsAnExecuteContractBecauseItActivatesAndWrites() throws Exception {
        ToolContract contract = com.javaclaw.desktop.DesktopTools.class
                .getDeclaredMethod("inspect", String.class)
                .getAnnotation(ToolContract.class);

        assertArrayEquals(new String[]{"tool.execute"}, contract.permissions());
        assertFalse(contract.idempotent());
    }

    private static final class MissingContractTool {
        @Tool
        String read() { return "ok"; }
    }

    @ToolContract(group = "test", permissions = {"tool.read"}, idempotent = true)
    private static final class ClassContractTool {
        @Tool
        String read() { return "ok"; }
    }

    private static final class MethodContractTool {
        @ToolContract(group = "test", permissions = {"tool.execute"}, idempotent = false)
        @Tool
        String write() { return "ok"; }
    }
}
