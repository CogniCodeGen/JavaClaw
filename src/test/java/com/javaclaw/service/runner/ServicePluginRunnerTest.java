package com.javaclaw.service.runner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.BinaryNode;
import com.javaclaw.fixture.FixtureServicePlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServicePluginRunnerTest {
    private static final String PLUGIN_ID = "fixture.service";
    private static final String VERSION = "1.0.0";
    private static final long GENERATION = 42;

    @TempDir Path temporary;

    @Test
    void loadsPluginInChildClassLoaderAndReturnsExactlyOneTerminal() throws Exception {
        List<ServicePluginWire.Frame> frames = run("loader", new byte[0]);

        ServicePluginWire.Frame catalog = frames.getFirst();
        assertEquals(ServicePluginWire.Type.SERVICE_CATALOG, catalog.type());
        assertEquals("fixture", catalog.payload().path("services").path(0).path("id").asText());

        List<ServicePluginWire.Frame> terminal = frames.stream()
                .filter(frame -> frame.type() == ServicePluginWire.Type.RESPONSE
                        || frame.type() == ServicePluginWire.Type.ERROR)
                .toList();
        assertEquals(1, terminal.size());
        assertTrue(new String(terminal.getFirst().payload().binaryValue(), StandardCharsets.UTF_8)
                .contains("ServicePluginRunner$PluginClassLoader"));
    }

    @Test
    void exposesThePluginClassLoaderToRequestsAndManagedTasks() throws Exception {
        ServicePluginWire.Frame response = run("context-loader", new byte[0]).stream()
                .filter(frame -> frame.type() == ServicePluginWire.Type.RESPONSE)
                .findFirst().orElseThrow();

        assertEquals("true:true:true",
                new String(response.payload().binaryValue(), StandardCharsets.UTF_8));
    }

    @Test
    void chunksLargeResponsesBelowFrameLimitAndPreservesBytes() throws Exception {
        List<ServicePluginWire.Frame> frames = run("large", new byte[0]);
        List<ServicePluginWire.Frame> responses = frames.stream()
                .filter(frame -> frame.type() == ServicePluginWire.Type.RESPONSE)
                .toList();

        assertEquals(3, responses.size());
        assertEquals(List.of(0L, 1L, 2L), responses.stream()
                .map(ServicePluginWire.Frame::sequence).toList());
        assertTrue(responses.get(0).payload().binaryValue().length < ServicePluginWire.MAX_FRAME_BYTES);
        assertTrue(responses.get(1).payload().binaryValue().length < ServicePluginWire.MAX_FRAME_BYTES);
        assertTrue(responses.get(2).terminal());
        ByteArrayOutputStream combined = new ByteArrayOutputStream();
        for (ServicePluginWire.Frame response : responses) combined.write(response.payload().binaryValue());
        byte[] expected = new byte[9 * 1024 * 1024];
        java.util.Arrays.fill(expected, (byte) 0x5a);
        assertArrayEquals(expected, combined.toByteArray());
    }

    @Test
    void streamsEventsBeforeOneTerminalAndIgnoresDuplicateCompletion() throws Exception {
        List<ServicePluginWire.Frame> frames = run("stream", new byte[0]).stream()
                .filter(frame -> frame.type() == ServicePluginWire.Type.EVENT
                        || frame.type() == ServicePluginWire.Type.RESPONSE
                        || frame.type() == ServicePluginWire.Type.ERROR)
                .toList();

        assertEquals(2, frames.size());
        assertEquals(ServicePluginWire.Type.EVENT, frames.get(0).type());
        assertEquals(0, frames.get(0).sequence());
        assertEquals(ServicePluginWire.Type.RESPONSE, frames.get(1).type());
        assertEquals(1, frames.get(1).sequence());
        assertTrue(frames.get(1).terminal());
    }

    @Test
    void reportsTheDeepestServiceFailureInsteadOfAnInvocationWrapper() throws Exception {
        ServicePluginWire.Frame failure = run("failure", new byte[0]).stream()
                .filter(frame -> frame.type() == ServicePluginWire.Type.ERROR)
                .findFirst().orElseThrow();

        assertEquals("service_failure", failure.errorCode());
        assertEquals("specific model failure", failure.errorMessage());
    }

    @Test
    @Timeout(10)
    void reverseDesktopCallsUseTheSameBidirectionalSocket() throws Exception {
        ObjectMapper json = new ObjectMapper();
        PipedInputStream runnerInput = new PipedInputStream(64 * 1024);
        PipedOutputStream desktopOutput = new PipedOutputStream(runnerInput);
        PipedInputStream desktopInput = new PipedInputStream(64 * 1024);
        PipedOutputStream runnerOutput = new PipedOutputStream(desktopInput);
        ServicePluginWire.Codec runnerCodec = new ServicePluginWire.Codec(json,
                new DataInputStream(runnerInput), new DataOutputStream(runnerOutput));
        ServicePluginWire.Codec desktopCodec = new ServicePluginWire.Codec(json,
                new DataInputStream(desktopInput), new DataOutputStream(desktopOutput));
        ServicePluginWire.Configure configure = new ServicePluginWire.Configure(
                FixtureServicePlugin.class.getName(), Set.of("host.echo"), Map.of(),
                new ServicePluginWire.Resources(256, 0, 2, 2), List.of(),
                temporary.resolve("data").toString());
        try (var control = Executors.newVirtualThreadPerTaskExecutor();
             ServicePluginRunner runner = new ServicePluginRunner(fixtureJar(), PLUGIN_ID,
                     VERSION, GENERATION, configure, runnerCodec, json, false)) {
            runner.start();
            assertEquals(ServicePluginWire.Type.SERVICE_CATALOG, desktopCodec.read().type());
            var running = control.submit(() -> {
                runner.runControlLoop();
                return null;
            });
            byte[] requestBody = "through-desktop".getBytes(StandardCharsets.UTF_8);
            desktopCodec.write(new ServicePluginWire.Frame(ServicePluginWire.PROTOCOL_MAJOR,
                    ServicePluginWire.PROTOCOL_MINOR, ServicePluginWire.Type.REQUEST,
                    "desktop-request", GENERATION, PLUGIN_ID, VERSION, "fixture", "host",
                    0, System.currentTimeMillis() + 10_000, "text/plain",
                    BinaryNode.valueOf(requestBody), true, "", ""));

            ServicePluginWire.Frame reverse = control.submit(desktopCodec::read).get(3, TimeUnit.SECONDS);
            assertEquals(ServicePluginWire.Type.REQUEST, reverse.type());
            assertTrue(reverse.requestId().startsWith("plugin:"));
            assertEquals("host.fixture", reverse.serviceId());
            assertArrayEquals(requestBody, reverse.payload().binaryValue());
            desktopCodec.write(new ServicePluginWire.Frame(ServicePluginWire.PROTOCOL_MAJOR,
                    ServicePluginWire.PROTOCOL_MINOR, ServicePluginWire.Type.RESPONSE,
                    reverse.requestId(), GENERATION, PLUGIN_ID, VERSION,
                    reverse.serviceId(), reverse.operation(), 0, reverse.deadlineEpochMilli(),
                    "text/plain", BinaryNode.valueOf(requestBody), true, "", ""));

            ServicePluginWire.Frame response = control.submit(desktopCodec::read).get(3, TimeUnit.SECONDS);
            assertEquals(ServicePluginWire.Type.RESPONSE, response.type());
            assertEquals("desktop-request", response.requestId());
            assertArrayEquals(requestBody, response.payload().binaryValue());
            desktopCodec.write(ServicePluginWire.Frame.control(ServicePluginWire.Type.SHUTDOWN,
                    GENERATION, PLUGIN_ID, VERSION, json.nullNode()));
            running.get(5, TimeUnit.SECONDS);
        }
    }

    private List<ServicePluginWire.Frame> run(String operation, byte[] payload) throws Exception {
        ObjectMapper json = new ObjectMapper();
        Path pluginJar = fixtureJar();
        ByteArrayOutputStream desktopFrames = new ByteArrayOutputStream();
        ServicePluginWire.Codec writer = new ServicePluginWire.Codec(json,
                new DataInputStream(InputStream.nullInputStream()),
                new DataOutputStream(desktopFrames));
        writer.write(new ServicePluginWire.Frame(ServicePluginWire.PROTOCOL_MAJOR,
                ServicePluginWire.PROTOCOL_MINOR, ServicePluginWire.Type.REQUEST,
                "request-1", GENERATION, PLUGIN_ID, VERSION, "fixture", operation,
                0, System.currentTimeMillis() + 10_000, "application/octet-stream",
                BinaryNode.valueOf(payload), true, "", ""));
        writer.write(ServicePluginWire.Frame.control(ServicePluginWire.Type.SHUTDOWN,
                GENERATION, PLUGIN_ID, VERSION, json.nullNode()));

        ByteArrayOutputStream pluginFrames = new ByteArrayOutputStream();
        ServicePluginWire.Codec runnerCodec = new ServicePluginWire.Codec(json,
                new DataInputStream(new ByteArrayInputStream(desktopFrames.toByteArray())),
                new DataOutputStream(pluginFrames));
        ServicePluginWire.Configure configure = new ServicePluginWire.Configure(
                FixtureServicePlugin.class.getName(), Set.of(), Map.of(),
                new ServicePluginWire.Resources(256, 0, 2, 2), List.of(),
                temporary.resolve("data").toString());
        try (ServicePluginRunner runner = new ServicePluginRunner(pluginJar, PLUGIN_ID,
                VERSION, GENERATION, configure, runnerCodec, json, false)) {
            runner.start();
            runner.runControlLoop();
        }

        List<ServicePluginWire.Frame> result = new ArrayList<>();
        ServicePluginWire.Codec reader = new ServicePluginWire.Codec(json,
                new DataInputStream(new ByteArrayInputStream(pluginFrames.toByteArray())),
                new DataOutputStream(OutputStreamHolder.EMPTY));
        ServicePluginWire.Frame frame;
        while ((frame = reader.read()) != null) result.add(frame);
        return result;
    }

    private Path fixtureJar() throws Exception {
        Path jar = temporary.resolve("fixture-plugin.jar");
        String entryName = FixtureServicePlugin.class.getName().replace('.', '/') + ".class";
        try (InputStream input = FixtureServicePlugin.class.getClassLoader().getResourceAsStream(entryName);
             JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            if (input == null) throw new IllegalStateException("fixture class resource missing");
            output.putNextEntry(new JarEntry(entryName));
            input.transferTo(output);
            output.closeEntry();
        }
        return jar;
    }

    private static final class OutputStreamHolder {
        private static final ByteArrayOutputStream EMPTY = new ByteArrayOutputStream();
    }
}
