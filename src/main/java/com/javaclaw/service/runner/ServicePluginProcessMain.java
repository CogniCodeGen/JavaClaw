package com.javaclaw.service.runner;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;

/** Generic child-process entry point. It never invokes a plugin-defined main method. */
public final class ServicePluginProcessMain {
    public static final String ENV_HOST = "JAVACLAW_SERVICE_HOST";
    public static final String ENV_PORT = "JAVACLAW_SERVICE_PORT";
    public static final String ENV_TOKEN = "JAVACLAW_SERVICE_TOKEN";
    public static final String ENV_PLUGIN_ID = "JAVACLAW_SERVICE_PLUGIN_ID";
    public static final String ENV_PLUGIN_VERSION = "JAVACLAW_SERVICE_PLUGIN_VERSION";
    public static final String ENV_ARTIFACT_SHA256 = "JAVACLAW_SERVICE_ARTIFACT_SHA256";
    public static final String ENV_DESKTOP_GENERATION = "JAVACLAW_DESKTOP_GENERATION";
    public static final String ENV_API_VERSION = "JAVACLAW_SERVICE_API_VERSION";

    private ServicePluginProcessMain() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("expected one plugin JAR path");
        Path pluginJar = Path.of(args[0]).toAbsolutePath().normalize();
        if (Files.isSymbolicLink(pluginJar)
                || !Files.isRegularFile(pluginJar, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("plugin JAR is not a regular file");
        }
        String host = required(ENV_HOST);
        if (!InetAddress.getByName(host).isLoopbackAddress()) {
            throw new SecurityException("Desktop control socket must be loopback-only");
        }
        int port = Integer.parseInt(required(ENV_PORT));
        long generation = Long.parseLong(required(ENV_DESKTOP_GENERATION));
        String pluginId = required(ENV_PLUGIN_ID);
        String pluginVersion = required(ENV_PLUGIN_VERSION);
        ObjectMapper json = new ObjectMapper()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

        try (Socket socket = new Socket()) {
            socket.setKeepAlive(true);
            socket.setTcpNoDelay(true);
            socket.connect(new InetSocketAddress(host, port), 10_000);
            var codec = new ServicePluginWire.Codec(json,
                    new DataInputStream(socket.getInputStream()),
                    new DataOutputStream(socket.getOutputStream()));
            var hello = new ServicePluginWire.Hello(required(ENV_TOKEN),
                    ProcessHandle.current().pid(), Instant.now().toEpochMilli(),
                    required(ENV_ARTIFACT_SHA256), required(ENV_API_VERSION));
            codec.write(ServicePluginWire.Frame.control(ServicePluginWire.Type.HELLO,
                    generation, pluginId, pluginVersion, json.valueToTree(hello)));
            ServicePluginWire.Frame ack = require(codec.read(), ServicePluginWire.Type.HELLO_ACK);
            if (ack.desktopGeneration() != generation) {
                throw new SecurityException("Desktop generation changed during handshake");
            }
            ServicePluginWire.Frame configureFrame = require(codec.read(), ServicePluginWire.Type.CONFIGURE);
            ServicePluginWire.Configure configure = json.treeToValue(
                    configureFrame.payload(), ServicePluginWire.Configure.class);
            try (ServicePluginRunner runner = new ServicePluginRunner(
                    pluginJar, pluginId, pluginVersion, generation, configure, codec, json)) {
                runner.start();
                runner.runControlLoop();
            }
        }
    }

    private static ServicePluginWire.Frame require(
            ServicePluginWire.Frame frame, ServicePluginWire.Type type) {
        if (frame == null || frame.type() != type) {
            throw new SecurityException("expected " + type + " during service-plugin handshake");
        }
        return frame;
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("missing " + name);
        return value;
    }
}
