package com.javaclaw.server.transport;

import java.nio.file.Path;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.core.api.TurnInput;
import com.javaclaw.sandbox.api.SandboxMode;
import com.javaclaw.sandbox.api.SandboxPaths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequestParametersTest {
    @TempDir
    Path temporary;

    @Test
    void clientsCannotSupplyWorkingDirectoryOrRawSandboxPolicy() {
        Path threadDirectory = temporary.resolve("workspace").toAbsolutePath().normalize();
        ObjectNode params = JsonNodeFactory.instance.objectNode();
        ObjectNode config = params.putObject("config");
        config.put("workingDirectory", "module-a");
        ObjectNode sandbox = config.putObject("sandboxPolicy");
        sandbox.put("mode", SandboxMode.WORKSPACE_WRITE.name());
        sandbox.putArray("readableRoots").add("src");
        sandbox.putArray("writableRoots").add("generated");
        sandbox.putArray("protectedRoots").add("secrets");

        assertThrows(IllegalArgumentException.class, () -> RequestParameters.turnConfig(params, threadDirectory));
    }

    @Test
    void defaultsRemainBoundToTheResolvedTurnDirectory() {
        Path threadDirectory = temporary.resolve("workspace").toAbsolutePath().normalize();
        ObjectNode params = JsonNodeFactory.instance.objectNode();
        params.putObject("config");

        var result = RequestParameters.turnConfig(params, threadDirectory);
        Path cwd = SandboxPaths.canonicalize(threadDirectory);

        assertEquals(java.util.Set.of(cwd), result.sandboxPolicy().readableRoots());
        assertTrue(result.sandboxPolicy().writableRoots().isEmpty());
        assertEquals(SandboxMode.READ_ONLY, result.sandboxPolicy().mode());
    }

    @Test
    void integerParametersRejectFractionalNumbers() {
        ObjectNode params = JsonNodeFactory.instance.objectNode();
        params.put("limit", 1.5d);

        assertThrows(IllegalArgumentException.class, () -> RequestParameters.optionalLong(params, "limit", 10));
    }

    @Test
    void workspaceWriteIsAlwaysBoundToTheThreadDirectory() {
        Path threadDirectory = temporary.resolve("workspace");
        ObjectNode params = JsonNodeFactory.instance.objectNode();
        params.putObject("config").put("accessMode", SandboxMode.WORKSPACE_WRITE.name());

        var result = RequestParameters.turnConfig(params, threadDirectory);
        assertEquals(
                java.util.Set.of(SandboxPaths.canonicalize(threadDirectory)),
                result.sandboxPolicy().writableRoots());
    }

    @Test
    void turnAttachmentsUseContentAddressesInsteadOfClientPaths() {
        Path cwd = temporary.resolve("workspace");
        ObjectNode params = JsonNodeFactory.instance.objectNode();
        ObjectNode input = params.putArray("input").addObject();
        input.put("type", "attachment");
        input.put("sha256", "a".repeat(64));
        input.put("mediaType", "text/markdown");
        input.put("displayName", "spec.md");

        TurnInput.AttachmentRef attachment =
                (TurnInput.AttachmentRef) RequestParameters.inputs(params, cwd).getFirst();

        assertEquals("a".repeat(64), attachment.sha256());
        assertEquals("spec.md", attachment.displayName());
    }

    @Test
    void threadPathsMustBeAbsoluteAtTheProtocolBoundary() {
        ObjectNode params = JsonNodeFactory.instance.objectNode();
        params.put("cwd", "relative/workspace");

        assertThrows(IllegalArgumentException.class, () -> RequestParameters.requiredAbsolutePath(params, "cwd"));
    }
}
