package com.javaclaw.desktop;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.Test;

import com.javaclaw.sdk.AppServerProcess;
import com.javaclaw.sdk.JavaClawClient;
import com.javaclaw.sdk.model.ProfileInfo;
import com.javaclaw.sdk.model.ThreadInfo;
import com.javaclaw.sdk.model.WorkspaceInfo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesktopArchitectureTest {
    @Test
    void launchDefaultsAreImmutableAndExplicitCommandsKeepTheirDataDirectory() throws Exception {
        String java = Path.of(
                        System.getProperty("java.home"),
                        "bin",
                        System.getProperty("os.name", "")
                                        .toLowerCase(Locale.ROOT)
                                        .contains("windows")
                                ? "java.exe"
                                : "java")
                .toString();
        List<String> command = new ArrayList<>(List.of(java, "-cp", "automatic", "Server"));
        Map<String, String> defaults = new HashMap<>(Map.of(
                "JAVACLAW_PROGRAM_DIR", "program-root",
                "JAVACLAW_SANDBOX_MODULE_PATH", "automatic-modules"));
        DesktopLaunchConfiguration configuration = new DesktopLaunchConfiguration(command, defaults, List.of());
        command.clear();
        defaults.clear();
        assertEquals(List.of(java, "-cp", "automatic", "Server"), configuration.appServerCommand());
        assertEquals(
                "automatic-modules", configuration.infrastructureEnvironment().get("JAVACLAW_SANDBOX_MODULE_PATH"));
        assertEquals("program-root", configuration.infrastructureEnvironment().get("JAVACLAW_PROGRAM_DIR"));

        List<String> explicit =
                List.of(Path.of(java).toRealPath().toString(), "CustomServer", "--data-dir", "owned-data");
        List<String> resolved = DesktopComponentGraph.appServerCommand(
                configuration,
                Map.of(
                        "JAVACLAW_APP_SERVER_COMMAND_JSON", AppServerProcess.encodeInfrastructureCommand(explicit),
                        "JAVACLAW_APP_SERVER_CLASSPATH", "ignored-classpath",
                        "JAVACLAW_DATA_DIR", "ignored-data"));
        assertEquals(explicit, resolved);
    }

    @Test
    void explicitClasspathAndInfrastructureOverrideAutomaticallyLocatedValues() throws Exception {
        DesktopLaunchConfiguration configuration = new DesktopLaunchConfiguration(
                List.of("automatic-java", "-cp", "automatic-classpath", "Server"),
                Map.of(
                        "JAVACLAW_SANDBOX_MODULE_PATH",
                        "automatic-modules",
                        "JAVACLAW_BROWSER_SERVICE_COMMAND_JSON",
                        "automatic-browser"),
                List.of());
        List<String> resolved = DesktopComponentGraph.appServerCommand(
                configuration,
                Map.of(
                        "JAVACLAW_APP_SERVER_CLASSPATH", "显式 classpath",
                        "JAVACLAW_CONFIG_DIR", "配置 目录"));
        assertEquals("显式 classpath", resolved.get(resolved.indexOf("-cp") + 1));
        assertEquals(Path.of("配置 目录").toAbsolutePath().normalize().toString(), resolved.getLast());
        assertEquals(
                Map.of(
                        "JAVACLAW_SANDBOX_MODULE_PATH", "explicit-modules",
                        "JAVACLAW_BROWSER_SERVICE_LIB", "explicit-library"),
                DesktopComponentGraph.infrastructureEnvironment(
                        configuration,
                        Map.of(
                                "JAVACLAW_SANDBOX_MODULE_PATH", "explicit-modules",
                                "JAVACLAW_BROWSER_SERVICE_LIB", "explicit-library")));
    }

    @Test
    void initializedStartupConnectionDoesNotReceiveASecondHandshake() throws Exception {
        FakeServer transport = new FakeServer();
        try (transport;
                JavaClawClient client = JavaClawClient.connect(transport.clientInput(), transport.clientOutput());
                DesktopViewModel model = new DesktopViewModel(client, Runnable::run, true)) {
            client.initialize("startup", "4").get(2, TimeUnit.SECONDS);
            model.initialize();
            assertTrue(transport.loaded.await(2, TimeUnit.SECONDS));
            assertEquals(1, transport.initializations.get());
        }
    }

    @Test
    void fxmlUsesTheExplicitSdkController() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        try (InputStream input = getClass().getResourceAsStream("/fxml/main.fxml")) {
            assertNotNull(input);
            var document = factory.newDocumentBuilder().parse(input);
            assertEquals(
                    "com.javaclaw.desktop.MainController",
                    document.getDocumentElement().getAttributeNS("http://javafx.com/fxml/1", "controller"));
        }
    }

    @Test
    void viewModelLoadsItsStateOnlyThroughTheSdkFacade() throws Exception {
        FakeServer transport = new FakeServer();
        try (transport;
                JavaClawClient client = JavaClawClient.connect(transport.clientInput(), transport.clientOutput());
                DesktopViewModel model = new DesktopViewModel(client, Runnable::run)) {
            model.initialize();
            assertTrue(transport.loaded.await(2, TimeUnit.SECONDS));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (model.busyProperty().get() && System.nanoTime() < deadline) {
                Thread.sleep(5);
            }

            assertEquals(
                    List.of("workspace"),
                    model.workspaces().stream().map(WorkspaceInfo::name).toList());
            assertEquals(
                    List.of("Chat"),
                    model.profiles().stream().map(ProfileInfo::name).toList());
            assertEquals(
                    List.of("Task"),
                    model.threads().stream().map(ThreadInfo::title).toList());
            assertTrue(model.errorProperty().get().isBlank());
        }
    }

    private static final class FakeServer implements AutoCloseable {
        private final AtomicInteger initializations = new AtomicInteger();
        private static final Pattern ID = Pattern.compile("\"id\":(\\d+)");
        private final CountDownLatch loaded = new CountDownLatch(1);
        private final PipedInputStream clientInput = new PipedInputStream();
        private final PipedOutputStream serverOutput;
        private final PipedInputStream serverInput = new PipedInputStream();
        private final PipedOutputStream clientOutput;
        private final Thread worker;

        private FakeServer() throws Exception {
            serverOutput = new PipedOutputStream(clientInput);
            clientOutput = new PipedOutputStream(serverInput);
            worker = Thread.startVirtualThread(this::serve);
        }

        private InputStream clientInput() {
            return clientInput;
        }

        private PipedOutputStream clientOutput() {
            return clientOutput;
        }

        private void serve() {
            try (var input = new BufferedReader(new InputStreamReader(serverInput, StandardCharsets.UTF_8));
                    var output = new BufferedWriter(new OutputStreamWriter(serverOutput, StandardCharsets.UTF_8))) {
                String line;
                while ((line = input.readLine()) != null) {
                    var matcher = ID.matcher(line);
                    if (!matcher.find()) {
                        continue;
                    }
                    String result;
                    if (line.contains("\"method\":\"initialize\"")) {
                        initializations.incrementAndGet();
                        result = "{\"protocolVersion\":1,\"serverName\":\"test\","
                                + "\"serverVersion\":\"4\",\"capabilities\":{},"
                                + "\"connectionId\":\"connection\"}";
                    } else if (line.contains("\"method\":\"workspace/list\"")) {
                        result = "[{\"id\":\"wsp\",\"name\":\"workspace\","
                                + "\"root\":\"/tmp\",\"revision\":1,\"locked\":false,"
                                + "\"lockReason\":\"\",\"createdAt\":\"2026-01-01T00:00:00Z\","
                                + "\"updatedAt\":\"2026-01-01T00:00:00Z\"}]";
                    } else if (line.contains("\"method\":\"thread/list\"")) {
                        result = "[{\"id\":\"thr\",\"workspaceId\":\"wsp\","
                                + "\"title\":\"Task\",\"cwd\":\"/tmp\",\"status\":\"ACTIVE\","
                                + "\"baseSequence\":0,\"lastSequence\":0,\"revision\":1,"
                                + "\"createdAt\":\"2026-01-01T00:00:00Z\","
                                + "\"updatedAt\":\"2026-01-01T00:00:00Z\"}]";
                    } else if (line.contains("\"method\":\"profile/list\"")) {
                        result = "[{\"id\":\"profile_chat\",\"name\":\"Chat\","
                                + "\"kind\":\"CHAT\",\"provider\":\"openai\",\"model\":\"gpt-5\","
                                + "\"systemPrompt\":\"\",\"enabledTools\":[],"
                                + "\"requestedSandboxMode\":\"READ_ONLY\",\"maxIterations\":16,"
                                + "\"maxModelCalls\":16,\"attributes\":{},\"revision\":1,"
                                + "\"updatedAt\":\"2026-01-01T00:00:00Z\"}]";
                        loaded.countDown();
                    } else {
                        throw new AssertionError("unexpected SDK call: " + line);
                    }
                    output.write("{\"jsonrpc\":\"2.0\",\"id\":" + matcher.group(1) + ",\"result\":" + result + "}\n");
                    output.flush();
                }
            } catch (Exception ignored) {
            }
        }

        @Override
        public void close() throws Exception {
            clientOutput.close();
            clientInput.close();
            worker.join(1_000);
        }
    }
}
