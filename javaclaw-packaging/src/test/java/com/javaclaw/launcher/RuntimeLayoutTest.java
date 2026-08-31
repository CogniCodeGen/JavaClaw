package com.javaclaw.launcher;

import java.io.File;
import java.io.IOException;
import java.lang.module.ModuleFinder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.desktop.DesktopComponentGraph;
import com.javaclaw.desktop.DesktopLaunchConfiguration;
import com.javaclaw.desktop.JavaClawDesktop;
import com.javaclaw.sdk.AppServerProcess;
import com.javaclaw.sdk.JavaClawClient;
import com.javaclaw.sdk.WindowsTransportBridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeLayoutTest {
    @TempDir
    Path temporary;

    @Test
    void exposesOneDirectDesktopProductEntryAndKeepsTheIdeConfigurationOnIt() throws Exception {
        assertNotNull(JavaClawLauncher.class.getDeclaredMethod("main", String[].class));
        assertThrows(
                NoSuchMethodException.class, () -> JavaClawDesktop.class.getDeclaredMethod("main", String[].class));
        String configuration = Files.readString(Path.of("..", ".run", "JavaClaw.run.xml"));
        assertTrue(configuration.contains("com.javaclaw.launcher.JavaClawLauncher"));
        assertTrue(configuration.contains("module name=\"javaclaw-packaging\""));
    }

    @Test
    void resolvesCompiledClassesAndOnlyTheNativeHostsRequiredModules() throws Exception {
        DesktopLaunchConfiguration configuration = ideConfiguration();
        List<Path> nativePaths = modulePaths(configuration);
        var names = ModuleFinder.of(nativePaths.toArray(Path[]::new)).findAll().stream()
                .map(module -> module.descriptor().name())
                .collect(Collectors.toSet());

        assertTrue(configuration.appServerCommand().get(2).contains("target" + File.separator + "classes"));
        assertEquals(
                Path.of(System.getProperty("user.dir"))
                        .toAbsolutePath()
                        .normalize()
                        .toString(),
                configuration.infrastructureEnvironment().get("JAVACLAW_PROGRAM_DIR"));
        assertTrue(nativePaths.stream().anyMatch(Files::isDirectory));
        assertTrue(names.contains("com.javaclaw.nativehosts"));
        assertFalse(names.stream().anyMatch(name -> name.startsWith("javafx") || name.startsWith("spring")));
        assertFalse(names.contains("com.javaclaw.server"));
        ModuleLayer.boot()
                .configuration()
                .resolve(
                        ModuleFinder.of(nativePaths.toArray(Path[]::new)),
                        ModuleFinder.of(),
                        List.of("com.javaclaw.nativehosts"));
    }

    @Test
    void preservesManifestClasspathEntriesWithEncodedSpacesAndUnicode() throws Exception {
        Path dependency = temporary.resolve("依赖 空格.jar");
        writeJar(dependency, Map.of("resource.txt", "resource".getBytes()), null);
        Path wrapper = temporary.resolve("idea-classpath.jar");
        writeJar(wrapper, Map.of(), dependency.toUri().toASCIIString() + " missing-optional.jar");

        assertEquals(
                List.of(wrapper.toRealPath(), dependency.toRealPath()),
                RuntimeLayout.classPathEntries(wrapper.toString()));
    }

    @Test
    void rejectsNetworkClasspathWithoutAttemptingToFetchIt() throws Exception {
        Path wrapper = temporary.resolve("remote-classpath.jar");
        writeJar(wrapper, Map.of(), "https://example.invalid/dependency.jar");
        assertThrows(IOException.class, () -> RuntimeLayout.classPathEntries(wrapper.toString()));
    }

    @Test
    void resolvesBothDistributionAndJpackageLayoutsUnderUnicodePaths() throws Exception {
        List<Path> nativePaths = modulePaths(ideConfiguration());
        for (boolean jpackage : List.of(false, true)) {
            Path root = temporary.resolve(jpackage ? "安装 包" : "发行 目录");
            Path library = Files.createDirectories(root.resolve("lib"));
            Files.writeString(
                    Files.createDirectories(library.resolve("ms-playwright")).resolve("bundle.json"), "{\"format\":1}");
            Path launcher = library.resolve("com.javaclaw.javaclaw-packaging.jar");
            writeJar(launcher, Map.of(), null);
            writeJar(
                    library.resolve("com.javaclaw.javaclaw-app-server.jar"),
                    Map.of("com/javaclaw/server/bootstrap/AppServerMain.class", new byte[0]),
                    null);
            writeJar(
                    library.resolve("com.javaclaw.javaclaw-browser-service.jar"),
                    Map.of("com/javaclaw/browser/BrowserServiceMain.class", new byte[0]),
                    null);
            writeJar(
                    library.resolve("javafx-fixture.jar"),
                    Map.of(
                            "javafx/application/Application.class", new byte[0],
                            "javafx/fxml/FXMLLoader.class", new byte[0]),
                    null);
            Path nativeDirectory = Files.createDirectories((jpackage ? library : root).resolve("native-module-path"));
            int index = 0;
            for (Path module : nativePaths) {
                copyAsJar(module, nativeDirectory.resolve("module-" + index++ + ".jar"));
            }

            DesktopLaunchConfiguration configuration =
                    RuntimeLayout.resolve(launcher, "unused-classpath", Map.of(), javaHome(), osName());

            assertEquals(
                    library.toRealPath().resolve("*").toString(),
                    configuration.appServerCommand().get(2));
            assertEquals(
                    library.toRealPath().resolve("ms-playwright").toString(),
                    configuration.infrastructureEnvironment().get("JAVACLAW_BROWSER_ASSET_DIR"));
            assertEquals(
                    root.toRealPath().toString(),
                    configuration.infrastructureEnvironment().get("JAVACLAW_PROGRAM_DIR"));
            Path realNativeDirectory = nativeDirectory.toRealPath();
            assertTrue(modulePaths(configuration).stream()
                    .allMatch(path -> path.getParent().equals(realNativeDirectory)));
        }
    }

    @Test
    void reportsMissingAndAmbiguousNativeModules() throws Exception {
        IOException missing =
                assertThrows(IOException.class, () -> RuntimeLayout.nativeModulePaths(List.of(temporary)));
        assertTrue(missing.getMessage().contains("com.javaclaw.nativehosts"));
        List<Path> modules = new ArrayList<>(modulePaths(ideConfiguration()));
        Path duplicate = temporary.resolve("duplicate-native.jar");
        copyAsJar(modules.getFirst(), duplicate);
        modules.add(duplicate);
        IOException ambiguous = assertThrows(IOException.class, () -> RuntimeLayout.nativeModulePaths(modules));
        assertTrue(ambiguous.getMessage().contains("多个版本"));
    }

    @Test
    void explicitExternalEndpointsDoNotRequireLocalServerArtifacts() throws Exception {
        for (String os : List.of("Linux", "Windows")) {
            String option =
                    os.equals("Windows") ? "JAVACLAW_WINDOWS_TRANSPORT_COMMAND_JSON" : "JAVACLAW_APP_SERVER_SOCKET";
            DesktopLaunchConfiguration configuration = RuntimeLayout.resolve(
                    temporary.resolve("absent.jar"), "absent-classpath", Map.of(option, "explicit"), javaHome(), os);
            assertTrue(configuration.appServerCommand().isEmpty());
            assertTrue(configuration.windowsTransportCommand().isEmpty());
        }
    }

    @Test
    void explicitProgramDirectoryControlsAllUnspecifiedLocalStorage() throws Exception {
        Path program = temporary.resolve("portable program");
        DesktopLaunchConfiguration configuration = RuntimeLayout.resolve(
                launcherLocation(),
                LaunchTestSupport.ideClasspath(),
                Map.of("JAVACLAW_PROGRAM_DIR", program.toString()),
                javaHome(),
                osName());

        assertEquals(
                program.toAbsolutePath().normalize().toString(),
                configuration.infrastructureEnvironment().get("JAVACLAW_PROGRAM_DIR"));
        assertFalse(configuration.infrastructureEnvironment().containsKey("JAVACLAW_DATA_DIR"));
    }

    @Test
    void programLocalDefaultsPersistProviderConfigurationAcrossServerRestart() throws Exception {
        DesktopLaunchConfiguration defaults = ideConfiguration();
        Path program = temporary.resolve("可携带程序");
        List<String> command = new ArrayList<>(defaults.appServerCommand());
        command.addAll(List.of("--program-dir", program.toString()));
        char[] credential = "test-program-local-secret".toCharArray();
        try {
            try (AppServerProcess process = new AppServerProcess(command, defaults.infrastructureEnvironment())) {
                JavaClawClient client = process.client();
                checkHandshake(client);
                var provider = openAiProvider(client);
                client.models()
                        .configureProvider(
                                "openai",
                                Map.of(
                                        "model", "program-local-model",
                                        "embeddingModel", "program-local-embedding",
                                        "baseUrl", "https://program-local.invalid/v1"),
                                provider.configRevision(),
                                "program-local-provider-config")
                        .get(5, TimeUnit.SECONDS);
                client.models()
                        .setCredential("openai", credential, "program-local-provider-secret")
                        .get(5, TimeUnit.SECONDS);
            }
        } finally {
            Arrays.fill(credential, '\0');
        }

        Path localRoot = program.resolve(".javaclaw");
        assertTrue(Files.isDirectory(localRoot.resolve("data-v4")));
        assertTrue(Files.isDirectory(localRoot.resolve("config-v4")));
        assertTrue(Files.isDirectory(localRoot.resolve("cache-v4")));

        try (AppServerProcess process = new AppServerProcess(command, defaults.infrastructureEnvironment())) {
            JavaClawClient client = process.client();
            checkHandshake(client);
            var provider = openAiProvider(client);
            assertTrue(provider.configured());
            assertEquals("program-local-model", provider.model());
            assertEquals("program-local-embedding", provider.embeddingModel());
            assertEquals("https://program-local.invalid/v1", provider.baseUrl());
            assertTrue(provider.configRevision() > 0);
            assertTrue(provider.credentialRevision() > 0);
        }
    }

    @Test
    void explicitNativeModulePathIsUsedByTheWindowsHostCommand() throws Exception {
        String modules = ideConfiguration().infrastructureEnvironment().get("JAVACLAW_SANDBOX_MODULE_PATH");
        Path fakeHome = temporary.resolve("Windows JDK");
        Files.createDirectories(fakeHome.resolve("bin"));
        Path java = Files.writeString(fakeHome.resolve("bin/java.exe"), "fixture");
        assertTrue(java.toFile().setExecutable(true));
        DesktopLaunchConfiguration configuration = RuntimeLayout.resolve(
                launcherLocation(),
                System.getProperty("java.class.path"),
                Map.of("JAVACLAW_SANDBOX_MODULE_PATH", modules),
                fakeHome,
                "Windows");
        List<String> command = configuration.windowsTransportCommand();
        assertEquals(java.toRealPath().toString(), command.getFirst());
        assertEquals(modules, command.get(command.indexOf("--module-path") + 1));
        assertTrue(command.contains("--enable-native-access=com.javaclaw.nativehosts"));
    }

    @Test
    void startsARealServerFromIdeOutputsAndClosesItsOwnedProcesses() throws Exception {
        DesktopLaunchConfiguration configuration = ideConfiguration();
        List<String> command = new ArrayList<>(configuration.appServerCommand());
        command.addAll(List.of(
                "--data-dir",
                temporary.resolve("数据 data").toString(),
                "--config-dir",
                temporary.resolve("配置 config").toString(),
                "--cache-dir",
                temporary.resolve("缓存 cache").toString()));
        List<ProcessHandle> children;
        if (osName().toLowerCase(Locale.ROOT).contains("windows")) {
            try (var transport = WindowsTransportBridge.startLocalAppServer(
                    configuration.windowsTransportCommand(),
                    "javaclaw-v4-launcher-" + UUID.randomUUID(),
                    command,
                    configuration.infrastructureEnvironment())) {
                checkHandshake(transport.client());
                children = ProcessHandle.current().children().toList();
            }
        } else {
            try (AppServerProcess process = new AppServerProcess(command, configuration.infrastructureEnvironment())) {
                checkHandshake(process.client());
                children = ProcessHandle.current().children().toList();
            }
        }
        assertFalse(children.isEmpty());
        for (ProcessHandle child : children) {
            child.onExit().get(5, TimeUnit.SECONDS);
            assertFalse(child.isAlive());
        }
    }

    @Test
    void invalidDataDirectoryFailsBeforeCreatingUiAndKeepsExistingData() throws Exception {
        DesktopLaunchConfiguration defaults = ideConfiguration();
        Path data = Files.createDirectories(temporary.resolve("旧数据"));
        Path existing = Files.writeString(data.resolve("existing.txt"), "keep-existing-data");
        List<String> command = new ArrayList<>(defaults.appServerCommand());
        command.addAll(List.of(
                "--data-dir",
                data.toString(),
                "--config-dir",
                temporary.resolve("config").toString(),
                "--cache-dir",
                temporary.resolve("cache").toString()));
        DesktopLaunchConfiguration configuration = new DesktopLaunchConfiguration(
                command, defaults.infrastructureEnvironment(), defaults.windowsTransportCommand());
        var previous =
                ProcessHandle.current().children().map(ProcessHandle::pid).collect(Collectors.toSet());

        IOException failure = assertThrows(IOException.class, () -> DesktopComponentGraph.create(configuration));

        assertTrue(failure.getMessage().contains("握手失败"));
        assertEquals("keep-existing-data", Files.readString(existing));
        assertTrue(ProcessHandle.current()
                .children()
                .noneMatch(child -> !previous.contains(child.pid()) && child.isAlive()));
    }

    private static void checkHandshake(JavaClawClient client) throws Exception {
        assertEquals(
                1,
                client.initialize("launcher-test", "4")
                        .get(15, TimeUnit.SECONDS)
                        .protocolVersion());
        assertFalse(client.models().listProfiles().get(5, TimeUnit.SECONDS).isEmpty());
    }

    private static com.javaclaw.sdk.model.ProviderInfo openAiProvider(JavaClawClient client) throws Exception {
        return client.models().listProviders().get(5, TimeUnit.SECONDS).stream()
                .filter(provider -> "openai".equals(provider.id()))
                .findFirst()
                .orElseThrow();
    }

    private static DesktopLaunchConfiguration ideConfiguration() throws Exception {
        return RuntimeLayout.resolve(
                launcherLocation(), LaunchTestSupport.ideClasspath(), Map.of(), javaHome(), osName());
    }

    private static Path launcherLocation() throws Exception {
        return Path.of(JavaClawLauncher.class
                .getProtectionDomain()
                .getCodeSource()
                .getLocation()
                .toURI());
    }

    private static List<Path> modulePaths(DesktopLaunchConfiguration configuration) {
        return Pattern.compile(Pattern.quote(File.pathSeparator))
                .splitAsStream(configuration.infrastructureEnvironment().get("JAVACLAW_SANDBOX_MODULE_PATH"))
                .map(Path::of)
                .toList();
    }

    private static Path javaHome() {
        return Path.of(System.getProperty("java.home"));
    }

    private static String osName() {
        return System.getProperty("os.name", "");
    }

    private static void writeJar(Path path, Map<String, byte[]> resources, String classpath) throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (classpath != null) {
            manifest.getMainAttributes().put(Attributes.Name.CLASS_PATH, classpath);
        }
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(path), manifest)) {
            for (var resource : resources.entrySet()) {
                output.putNextEntry(new JarEntry(resource.getKey()));
                output.write(resource.getValue());
                output.closeEntry();
            }
        }
    }

    private static void copyAsJar(Path source, Path target) throws IOException {
        if (Files.isRegularFile(source)) {
            Files.copy(source, target);
            return;
        }
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(target));
                var files = Files.walk(source)) {
            for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                output.putNextEntry(
                        new JarEntry(source.relativize(file).toString().replace(File.separatorChar, '/')));
                Files.copy(file, output);
                output.closeEntry();
            }
        }
    }
}
