package com.javaclaw.infrastructure.inference.serviceplugin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.application.serviceplugin.ServicePluginInvocationPort.ServicePluginException;
import com.javaclaw.application.serviceplugin.ServicePluginManagementApplicationService.ResourceConfiguration;
import com.javaclaw.application.serviceplugin.ServicePluginManagementApplicationService.StartupPolicy;
import com.javaclaw.fixture.serviceplugin.IsolatedFixtureServicePlugin;
import com.javaclaw.infrastructure.serviceplugin.ServicePluginDefinition;
import com.javaclaw.infrastructure.serviceplugin.ServicePluginProcessManager;
import com.javaclaw.inference.api.InferenceChatResponse;
import com.javaclaw.platform.data.DataRoot;
import com.javaclaw.platform.execution.ManagedTaskExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeliveranceServicePluginGatewayTest {
    @TempDir Path temporary;

    @Test
    void stopIsIdempotentOnlyWhenTheProcessOwnsNoModelMemory() throws Exception {
        Path pluginJar = fixtureJar();
        try (ManagedTaskExecutor tasks = new ManagedTaskExecutor();
             ServicePluginProcessManager manager = new ServicePluginProcessManager(
                     new DataRoot(temporary.resolve("data")), tasks,
                     new ObjectMapper().findAndRegisterModules())) {
            manager.init();
            manager.register(definition(pluginJar));
            DeliveranceServicePluginGateway gateway = new DeliveranceServicePluginGateway(
                    null, null, manager, new ObjectMapper(), null);

            assertDoesNotThrow(() -> gateway.stop(UUID.randomUUID()),
                    "尚未启动的子进程不可能持有模型内存");

            manager.start(DeliveranceServicePluginGateway.PLUGIN_ID);
            ServicePluginException failure = assertThrows(
                    ServicePluginException.class, () -> gateway.stop(UUID.randomUUID()));
            assertEquals("service_failure", failure.code());
        }
    }

    @Test
    void normalizesLegacyQwenToolCallMarkupAtTheHostBoundary() {
        DeliveranceServicePluginGateway gateway = new DeliveranceServicePluginGateway(
                null, null, null, new ObjectMapper(), null);
        InferencePluginProtocol.ChatResponse wire = new InferencePluginProtocol.ChatResponse(
                "request-1", "qwen", "before<tool_call>\n"
                + "{\"name\":\"weather\",\"arguments\":{\"city\":\"北京\"}}\n"
                + "</tool_call>after", "", List.of(), "STOP",
                new InferencePluginProtocol.Usage(10, 5), 1, 2);

        InferenceChatResponse response = gateway.map(wire);

        assertEquals("beforeafter", response.content());
        assertEquals(InferenceChatResponse.FinishReason.TOOL_CALLS, response.finishReason());
        assertEquals(1, response.toolCalls().size());
        assertEquals("weather", response.toolCalls().getFirst().name());
        assertTrue(response.toolCalls().getFirst().id().startsWith("call_"));
        assertEquals("{\"city\":\"北京\"}", response.toolCalls().getFirst().argumentsJson());

        InferenceChatResponse malformed = gateway.map(new InferencePluginProtocol.ChatResponse(
                "request-2", "qwen", "<tool_call>not-json</tool_call>", "", List.of(),
                "STOP", new InferencePluginProtocol.Usage(1, 1), 0, 0));
        assertTrue(malformed.toolCalls().isEmpty());
        assertEquals("<tool_call>not-json</tool_call>", malformed.content());
    }

    private ServicePluginDefinition definition(Path pluginJar) throws Exception {
        return new ServicePluginDefinition(DeliveranceServicePluginGateway.PLUGIN_ID,
                "Deliverance fixture", "1.0.0", "1.0",
                IsolatedFixtureServicePlugin.class.getName(), "Test Publisher", true,
                sha256(pluginJar), pluginJar, temporary.resolve("plugin-data"),
                StartupPolicy.MANUAL, new ResourceConfiguration(256, 0, 1, 4, 64),
                List.of(), true, Set.of(), Map.of(), false);
    }

    private Path fixtureJar() throws Exception {
        Path jar = temporary.resolve("fixture-plugin.jar");
        String resource = "/" + IsolatedFixtureServicePlugin.class.getName()
                .replace('.', '/') + ".class";
        try (InputStream input = IsolatedFixtureServicePlugin.class.getResourceAsStream(resource);
             JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            if (input == null) throw new IllegalStateException("fixture class missing");
            output.putNextEntry(new JarEntry(resource.substring(1)));
            input.transferTo(output);
            output.closeEntry();
        }
        return jar;
    }

    private static String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
