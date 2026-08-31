package com.javaclaw.server.extension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.sandbox.api.NetworkPolicy;
import com.javaclaw.sandbox.api.SandboxCommand;
import com.javaclaw.sandbox.api.SandboxExecutor;
import com.javaclaw.sandbox.api.SandboxMode;
import com.javaclaw.sandbox.api.SandboxPolicy;
import com.javaclaw.sandbox.api.SandboxResult;
import com.javaclaw.sandbox.api.SandboxSession;
import com.javaclaw.sandbox.api.SandboxSessionFrame;
import com.javaclaw.sandbox.api.SandboxSessionOptions;
import com.javaclaw.sandbox.api.SandboxSignal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginRuntimeTest {
    @TempDir
    Path temporary;

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void loadsStrictManifestAndInvokesOnlyThroughSandboxBoundary() throws Exception {
        Path bundle = validBundle("sample.plugin");
        PluginCatalog catalog = new PluginCatalog(new PluginBundleLoader(PluginSignatureVerifier.REJECT_UNTRUSTED));
        LoadedPlugin loaded = catalog.register(bundle, true);
        assertFalse(loaded.signatureVerified());
        assertEquals(1, loaded.processes().size());
        assertEquals(1, loaded.skills().size());

        CapturingSandbox sandbox = new CapturingSandbox(json);
        PluginProcessRuntime runtime = new PluginProcessRuntime(catalog, sandbox, json);
        JsonNode request = json.readTree("""
                {"jsonrpc":"2.0","id":"request-1","method":"health","params":{}}
                """);
        JsonNode response = runtime.invoke(
                "sample.plugin",
                "service",
                request,
                new PluginInvocationContext(
                        temporary,
                        Set.of(temporary.resolve(".git")),
                        hostCeiling(),
                        Map.of("PATH", "/usr/bin", "TOKEN", "secret")));

        assertTrue(response.path("result").path("ok").asBoolean());
        assertEquals(List.of("initialize", "health"), sandbox.methods);
        assertEquals(1, runtime.activeSessionCount());
        assertEquals("", sandbox.command.standardInput());
        assertEquals(Set.of("PATH"), sandbox.command.policy().inheritedEnvironment());
        assertEquals(SandboxMode.READ_ONLY, sandbox.command.policy().mode());
        assertTrue(
                sandbox.command.argv().getFirst().startsWith(bundle.toRealPath().toString()));
    }

    @Test
    void rejectsTraversalUnknownFieldsAndDuplicateRegistration() throws Exception {
        Path valid = validBundle("valid.plugin");
        PluginCatalog catalog = new PluginCatalog(new PluginBundleLoader(PluginSignatureVerifier.REJECT_UNTRUSTED));
        catalog.register(valid, true);
        assertThrows(IllegalStateException.class, () -> catalog.register(valid, true));

        Path traversal = temporary.resolve("traversal");
        Files.createDirectories(traversal.resolve(".javaclaw-plugin"));
        Files.writeString(traversal.resolve(".javaclaw-plugin/plugin.json"), """
                {
                  "apiVersion":4,
                  "id":"bad.plugin",
                  "version":"1.0.0",
                  "name":"bad",
                  "processes":[{
                    "id":"service","kind":"SERVICE","entrypoint":"../escape",
                    "arguments":[],"workspaceRead":false,"workspaceWrite":false,
                    "networkAllowlist":[],"timeoutMillis":1000,"outputLimitBytes":1024
                  }],
                  "skills":[]
                }
                """);
        assertThrows(
                IOException.class,
                () -> new PluginBundleLoader(PluginSignatureVerifier.REJECT_UNTRUSTED).load(traversal));

        Path unknown = temporary.resolve("unknown");
        Files.createDirectories(unknown.resolve(".javaclaw-plugin"));
        Files.writeString(unknown.resolve(".javaclaw-plugin/plugin.json"), """
                {"apiVersion":4,"id":"unknown.plugin","version":"1.0.0",
                 "name":"unknown","processes":[],"skills":[],"javaClass":"Exploit"}
                """);
        assertThrows(
                IOException.class,
                () -> new PluginBundleLoader(PluginSignatureVerifier.REJECT_UNTRUSTED).load(unknown));
    }

    @Test
    void rejectsSpoofedSignatureWithoutGrantingAnyPermission() throws Exception {
        Path bundle = temporary.resolve("signed");
        Files.createDirectories(bundle.resolve(".javaclaw-plugin"));
        Files.writeString(bundle.resolve(".javaclaw-plugin/plugin.json"), """
                {"apiVersion":4,"id":"signed.plugin","version":"1.0.0",
                 "name":"signed","processes":[],"skills":[],
                 "signature":{"algorithm":"Ed25519","keyId":"unknown","value":"fake"}}
                """);
        assertThrows(
                IOException.class, () -> new PluginBundleLoader(PluginSignatureVerifier.REJECT_UNTRUSTED).load(bundle));
    }

    private Path validBundle(String id) throws IOException {
        Path bundle = temporary.resolve(id);
        Files.createDirectories(bundle.resolve(".javaclaw-plugin"));
        Files.createDirectories(bundle.resolve("bin"));
        Files.createDirectories(bundle.resolve("skills/demo"));
        Files.writeString(bundle.resolve("bin/worker"), "worker");
        Files.writeString(bundle.resolve("skills/demo/SKILL.md"), "# Demo");
        Files.writeString(bundle.resolve(".javaclaw-plugin/plugin.json"), """
                {
                  "apiVersion":4,
                  "id":"%s",
                  "version":"1.2.3",
                  "name":"Sample",
                  "processes":[{
                    "id":"service","kind":"SERVICE","entrypoint":"bin/worker",
                    "arguments":["--jsonrpc"],"workspaceRead":false,"workspaceWrite":false,
                    "networkAllowlist":[],"timeoutMillis":1000,"outputLimitBytes":4096
                  }],
                  "skills":[{"id":"demo","path":"skills/demo/SKILL.md"}]
                }
                """.formatted(id));
        return bundle;
    }

    private static SandboxPolicy hostCeiling() {
        return new SandboxPolicy(
                SandboxMode.HOST_FULL_ACCESS,
                Set.of(),
                Set.of(),
                Set.of(),
                new NetworkPolicy(NetworkPolicy.Mode.FULL, Set.of()),
                Set.of("PATH"),
                Duration.ofMinutes(1),
                8192);
    }

    private static final class CapturingSandbox implements SandboxExecutor {
        private final ObjectMapper json;
        private SandboxCommand command;
        private final java.util.List<String> methods = new java.util.ArrayList<>();

        private CapturingSandbox(ObjectMapper json) {
            this.json = json;
        }

        @Override
        public SandboxResult execute(SandboxCommand command) throws Exception {
            throw new AssertionError("persistent plugins must not use one-shot execution");
        }

        @Override
        public SandboxSession openSession(SandboxCommand command, SandboxSessionOptions options) {
            this.command = command;
            return new SandboxSession() {
                private final LinkedBlockingQueue<SandboxSessionFrame> output = new LinkedBlockingQueue<>();
                private boolean alive = true;

                @Override
                public String id() {
                    return command.id();
                }

                @Override
                public SandboxSessionFrame read(Duration timeout) throws InterruptedException {
                    return output.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
                }

                @Override
                public void write(byte[] input) throws Exception {
                    JsonNode request = json.readTree(input);
                    methods.add(request.path("method").asText());
                    var response = json.createObjectNode().put("jsonrpc", "2.0");
                    response.set("id", request.get("id"));
                    var result = response.putObject("result").put("ok", true);
                    if ("initialize".equals(request.path("method").asText())) {
                        result.put("protocolVersion", 1);
                    }
                    output.add(SandboxSessionFrame.stream(
                            "test",
                            SandboxSessionFrame.Kind.STDOUT,
                            (response + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                }

                @Override
                public void closeInput() {}

                @Override
                public void resize(int columns, int rows) {}

                @Override
                public void signal(SandboxSignal signal) {}

                @Override
                public boolean isAlive() {
                    return alive;
                }

                @Override
                public void terminate() {
                    alive = false;
                }
            };
        }
    }
}
