package com.javaclaw.infrastructure.serviceplugin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.application.serviceplugin.ServicePluginHostServiceRouter;
import com.javaclaw.application.serviceplugin.ServicePluginManagementApplicationService.ResourceConfiguration;
import com.javaclaw.platform.execution.ManagedTaskExecutor;
import com.javaclaw.platform.execution.TaskSpec;
import com.javaclaw.util.ProcessTerminator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/** Starts and authenticates JavaClaw's built-in child-process host. */
final class ServicePluginProcessLauncher {
    private static final Logger log = LoggerFactory.getLogger(ServicePluginProcessLauncher.class);
    private static final Duration START_TIMEOUT = Duration.ofSeconds(60);

    interface LaunchObserver {
        boolean stopping();
        void startingListener(ServerSocket listener);
        void startingProcess(Process process);
        void disconnected(ServicePluginSession session);
    }

    private final ManagedTaskExecutor tasks;
    private final ObjectMapper json;
    private final ServicePluginHostServiceRouter hostServices;
    private final long desktopGeneration;
    private final ServicePluginHostRuntime hostRuntime;
    private final SecureRandom random = new SecureRandom();

    ServicePluginProcessLauncher(
            ManagedTaskExecutor tasks,
            ObjectMapper json,
            ServicePluginHostServiceRouter hostServices,
            long desktopGeneration,
            ServicePluginHostRuntime hostRuntime) {
        this.tasks = tasks;
        this.json = json;
        this.hostServices = hostServices;
        this.desktopGeneration = desktopGeneration;
        this.hostRuntime = hostRuntime;
    }

    ServicePluginSession launch(
            ServicePluginDefinition definition,
            ServicePluginResourceBudget.Lease lease,
            LaunchObserver observer) throws Exception {
        String token = randomToken();
        Process process = null;
        Socket accepted = null;
        ServerSocket listener = new ServerSocket();
        observer.startingListener(listener);
        try (listener) {
            if (observer.stopping()) throw new IllegalStateException("服务插件启动已取消");
            hostRuntime.verifyUnchanged();
            listener.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 1);
            listener.setSoTimeout((int) START_TIMEOUT.toMillis());
            ProcessBuilder builder = new ProcessBuilder(command(definition));
            builder.directory(definition.dataDirectory().toFile());
            Map<String, String> environment = builder.environment();
            retainSafeEnvironment(environment);
            environment.put("JAVACLAW_SERVICE_HOST", "127.0.0.1");
            environment.put("JAVACLAW_SERVICE_PORT", Integer.toString(listener.getLocalPort()));
            environment.put("JAVACLAW_SERVICE_TOKEN", token);
            environment.put("JAVACLAW_SERVICE_PLUGIN_ID", definition.id());
            environment.put("JAVACLAW_SERVICE_PLUGIN_VERSION", definition.version());
            environment.put("JAVACLAW_SERVICE_ARTIFACT_SHA256", definition.artifactSha256());
            environment.put("JAVACLAW_DESKTOP_GENERATION", Long.toString(desktopGeneration));
            environment.put("JAVACLAW_SERVICE_API_VERSION", definition.apiVersion());
            builder.redirectOutput(ProcessBuilder.Redirect.PIPE);
            builder.redirectError(ProcessBuilder.Redirect.PIPE);
            process = builder.start();
            observer.startingProcess(process);
            if (observer.stopping()) {
                ProcessTerminator.destroyTreeForcibly(process);
                throw new IllegalStateException("服务插件启动已取消");
            }
            Deque<String> processLogs = new ArrayDeque<>();
            drainOutput(definition.id(), process.getInputStream(), "stdout", processLogs);
            drainOutput(definition.id(), process.getErrorStream(), "stderr", processLogs);
            accepted = listener.accept();
            if (!accepted.getInetAddress().isLoopbackAddress()) {
                throw new SecurityException("服务插件控制连接不是回环地址");
            }
            accepted.setKeepAlive(true);
            accepted.setTcpNoDelay(true);
            accepted.setSoTimeout((int) START_TIMEOUT.toMillis());
            ServicePluginWire.Codec codec = new ServicePluginWire.Codec(json,
                    new DataInputStream(accepted.getInputStream()),
                    new DataOutputStream(accepted.getOutputStream()));
            ServicePluginWire.Frame helloFrame = codec.read();
            validateHello(definition, process, token, helloFrame);
            codec.write(ServicePluginWire.Frame.control(ServicePluginWire.Type.HELLO_ACK,
                    desktopGeneration, definition.id(), definition.version(),
                    json.valueToTree(Map.of("accepted", true))));
            codec.write(ServicePluginWire.Frame.control(ServicePluginWire.Type.CONFIGURE,
                    desktopGeneration, definition.id(), definition.version(),
                    json.valueToTree(configure(definition))));
            ServicePluginWire.Frame catalog = codec.read();
            accepted.setSoTimeout(0);
            AtomicReference<ServicePluginSession> reference = new AtomicReference<>();
            ServicePluginSession session = new ServicePluginSession(definition, desktopGeneration,
                    process, accepted, codec, tasks, json, lease, hostServices, processLogs,
                    () -> observer.disconnected(reference.get()));
            reference.set(session);
            session.acceptCatalog(catalog);
            accepted = null;
            process = null;
            return session;
        } catch (Throwable failure) {
            closeAccepted(accepted);
            ProcessTerminator.destroyTreeForcibly(process);
            throw failure;
        } finally {
            observer.startingListener(null);
            observer.startingProcess(null);
        }
    }

    private void drainOutput(String pluginId, InputStream stream, String channel,
                             Deque<String> destination) {
        tasks.submit(TaskSpec.io("读取服务插件日志 " + pluginId + " " + channel), context -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String safe = line.replaceAll(
                            "(?i)(authorization|api[-_ ]?key|bearer|token)\\s*[:=]\\s*\\S+",
                            "$1=<redacted>");
                    synchronized (destination) {
                        if (destination.size() >= 2_000) destination.removeFirst();
                        destination.addLast(channel + ": " + safe);
                    }
                }
            } catch (IOException failure) {
                log.debug("服务插件日志流已关闭: id={}, channel={}", pluginId, channel, failure);
            }
            return null;
        });
    }

    List<String> command(ServicePluginDefinition definition) {
        ResourceConfiguration resources = definition.resources();
        List<String> command = new ArrayList<>();
        command.add(javaExecutable().toString());
        command.add("--add-modules");
        command.add("jdk.httpserver,jdk.incubator.vector");
        command.add("--enable-native-access=ALL-UNNAMED");
        // Deliverance's tensor allocator uses the JDK direct-buffer address through reflection.
        // Keep this fixed in the host command; plugins cannot inject arbitrary JVM arguments.
        command.add("--add-opens=java.base/java.nio=ALL-UNNAMED");
        command.add("-Xmx" + resources.heapMiB() + "m");
        if (resources.nativeMemoryMiB() > 0) {
            command.add("-XX:MaxDirectMemorySize=" + resources.nativeMemoryMiB() + "m");
        }
        command.add("-XX:ActiveProcessorCount=" + resources.computeThreads());
        command.add("-cp");
        command.add(hostRuntime.classpath());
        command.add(hostRuntime.mainClass());
        command.add(definition.pluginJar().toString());
        return List.copyOf(command);
    }

    private static void retainSafeEnvironment(Map<String, String> environment) {
        Set<String> allowed = Set.of("LANG", "LC_ALL", "LC_CTYPE", "TZ",
                "TMPDIR", "TMP", "TEMP", "SYSTEMROOT", "WINDIR", "SYSTEMDRIVE");
        environment.keySet().removeIf(name -> !allowed.contains(
                name.toUpperCase(java.util.Locale.ROOT)));
    }

    private ServicePluginWire.Configure configure(ServicePluginDefinition definition) {
        ResourceConfiguration value = definition.resources();
        List<ServicePluginWire.Endpoint> endpoints = definition.endpoints().stream()
                .map(endpoint -> new ServicePluginWire.Endpoint(endpoint.id(), endpoint.protocol().name(),
                        endpoint.bindAddress(), endpoint.port(), endpoint.tlsEnabled(),
                        endpoint.allowInsecureLan(), endpoint.keyStorePath() == null ? ""
                                : endpoint.keyStorePath().toAbsolutePath().normalize().toString(),
                        endpoint.keyStorePassword(), endpoint.apiKey(), endpoint.requestsPerMinute(),
                        endpoint.tokensPerMinute(), endpoint.maxConcurrent(), endpoint.maxConnections(),
                        endpoint.maxRequestBytes(), endpoint.requestTimeoutSeconds()))
                .toList();
        return new ServicePluginWire.Configure(definition.mainClass(), definition.permissions(),
                definition.config(), new ServicePluginWire.Resources(value.heapMiB(),
                value.nativeMemoryMiB(), value.computeThreads(), value.ioConcurrency()),
                endpoints, definition.dataDirectory().toString());
    }

    private void validateHello(ServicePluginDefinition definition, Process process, String token,
                               ServicePluginWire.Frame frame) throws Exception {
        if (frame == null || frame.type() != ServicePluginWire.Type.HELLO
                || frame.desktopGeneration() != desktopGeneration
                || !definition.id().equals(frame.pluginId())
                || !definition.version().equals(frame.pluginVersion())) {
            throw new SecurityException("服务插件 HELLO 身份无效");
        }
        ServicePluginWire.Hello hello = json.treeToValue(frame.payload(), ServicePluginWire.Hello.class);
        if (!MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),
                hello.token().getBytes(StandardCharsets.UTF_8))
                || hello.pid() != process.pid()
                || !definition.artifactSha256().equalsIgnoreCase(hello.artifactSha256())
                || !sameMajor(definition.apiVersion(), hello.apiVersion())) {
            throw new SecurityException("服务插件启动鉴权失败");
        }
        Instant actual = processStart(process).orElseThrow(
                () -> new SecurityException("无法验证服务插件进程启动时间"));
        if (Math.abs(actual.toEpochMilli() - hello.processStartEpochMilli()) > 60_000) {
            throw new SecurityException("服务插件进程启动时间不匹配");
        }
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static void closeAccepted(Socket socket) {
        if (socket == null) return;
        try {
            socket.close();
        } catch (IOException failure) {
            log.debug("关闭失败的服务插件控制连接时出错", failure);
        }
    }

    static Optional<Instant> processStart(Process process) {
        try {
            return process.info().startInstant();
        } catch (RuntimeException failure) {
            return Optional.empty();
        }
    }

    private static Path javaExecutable() {
        String name = System.getProperty("os.name", "").toLowerCase().contains("win")
                ? "java.exe" : "java";
        Path path = Path.of(System.getProperty("java.home"), "bin", name).toAbsolutePath().normalize();
        if (!Files.isRegularFile(path)) throw new IllegalStateException("找不到当前 JVM 的 Java 可执行文件");
        return path;
    }

    private static boolean sameMajor(String left, String right) {
        return major(left).equals(major(right));
    }

    private static String major(String value) {
        if (value == null) return "";
        int dot = value.indexOf('.');
        return (dot < 0 ? value : value.substring(0, dot)).strip();
    }
}
