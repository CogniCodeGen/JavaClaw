package com.javaclaw.testkit;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import javax.xml.parsers.DocumentBuilderFactory;
import com.sun.source.util.JavacTask;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Source-level release gates for the nine domain modules and their process boundaries. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
final class ArchitectureBoundaryTest {
    // 本测试类执行期间源码只读；跨场景复用解析结果，不为每条边界断言重复解析整座 Reactor。
    private final Map<Path, String> parsedSources = new ConcurrentHashMap<>();

    private static final List<String> MODULES = List.of(
            "javaclaw-api",
            "javaclaw-protocol",
            "javaclaw-agent-runtime",
            "javaclaw-app-server",
            "javaclaw-native-hosts",
            "javaclaw-browser-service",
            "javaclaw-client",
            "javaclaw-desktop",
            "javaclaw-packaging");

    private static final Set<String> REMOVED_MODULES = Set.of(
            "javaclaw-core-api",
            "javaclaw-sandbox-api",
            "javaclaw-core",
            "javaclaw-runtime",
            "javaclaw-tool-runtime",
            "javaclaw-feature-tools",
            "javaclaw-feature-conversation",
            "javaclaw-feature-automation",
            "javaclaw-feature-knowledge",
            "javaclaw-store-h2",
            "javaclaw-model-cloud",
            "javaclaw-extension-runtime",
            "javaclaw-native-ffm",
            "javaclaw-sandbox-launcher",
            "javaclaw-windows-transport-host",
            "javaclaw-sdk-java",
            "javaclaw-cli",
            "javaclaw-testkit");

    @Test
    void reactorContainsExactlyTheNineDomainModules() throws Exception {
        assertEquals(MODULES, reactorModules());
        for (String module : REMOVED_MODULES) {
            assertFalse(Files.exists(root().resolve(module)), () -> "removed Maven module still exists: " + module);
        }
    }

    @Test
    void productionDependenciesMatchTheFrozenDomainGraph() throws Exception {
        Map<String, Set<String>> expected = Map.ofEntries(
                Map.entry("javaclaw-api", Set.of()),
                Map.entry("javaclaw-protocol", Set.of()),
                Map.entry("javaclaw-agent-runtime", Set.of("javaclaw-api")),
                Map.entry("javaclaw-app-server", Set.of("javaclaw-api", "javaclaw-protocol", "javaclaw-agent-runtime")),
                Map.entry("javaclaw-native-hosts", Set.of("javaclaw-api", "javaclaw-protocol")),
                Map.entry("javaclaw-browser-service", Set.of("javaclaw-protocol")),
                Map.entry("javaclaw-client", Set.of("javaclaw-protocol")),
                Map.entry("javaclaw-desktop", Set.of("javaclaw-client")),
                Map.entry(
                        "javaclaw-packaging",
                        Set.of(
                                "javaclaw-app-server",
                                "javaclaw-native-hosts",
                                "javaclaw-browser-service",
                                "javaclaw-client",
                                "javaclaw-desktop")));

        Map<String, Set<String>> actual = new HashMap<>();
        for (String module : MODULES) {
            actual.put(module, productionModuleDependencies(module));
            assertEquals(
                    expected.get(module), actual.get(module), () -> "unexpected production dependencies for " + module);
        }
        assertAcyclic(actual);
    }

    @Test
    void kernelAndSchedulerRemainFrameworkFree() throws Exception {
        assertNoMatch(
                filesUnder("javaclaw-agent-runtime", "src/main/java/com/javaclaw/agent/kernel"),
                Pattern.compile("(?:import|requires)\\s+(?:org\\.springframework|javafx|"
                        + "java\\.sql|javax\\.sql|org\\.h2|com\\.fasterxml\\.jackson|"
                        + "com\\.javaclaw\\.(?:protocol|server))"),
                "agent.kernel must remain framework, persistence and transport free");
        assertNoMatch(
                filesUnder("javaclaw-agent-runtime", "src/main/java/com/javaclaw/agent/runtime"),
                Pattern.compile("(?:import|requires)\\s+(?:org\\.springframework|javafx|"
                        + "java\\.sql|javax\\.sql|org\\.h2|com\\.fasterxml\\.jackson|"
                        + "com\\.javaclaw\\.(?:protocol|server))"),
                "agent.runtime must remain framework, persistence and transport free");
    }

    @Test
    void agentLibrariesStayInsideTheirFunctionalPackages() throws Exception {
        assertImportsOnlyIn(
                "javaclaw-agent-runtime",
                "com.fasterxml.jackson",
                Set.of("com/javaclaw/agent/tool/", "com/javaclaw/agent/tools/"));
        assertImportsOnlyIn("javaclaw-agent-runtime", "org.quartz", Set.of("com/javaclaw/agent/automation/"));
        assertImportsOnlyIn("javaclaw-agent-runtime", "org.apache.pdfbox", Set.of("com/javaclaw/agent/knowledge/"));
        assertImportsOnlyIn("javaclaw-agent-runtime", "org.apache.poi", Set.of("com/javaclaw/agent/knowledge/"));
    }

    @Test
    void infrastructureLibrariesStayInsideTheirOwningProcesses() throws Exception {
        assertImportsOnlyInAllModules("org.h2", Set.of("javaclaw-app-server"));
        assertImportsOnlyInAllModules("org.springframework.ai", Set.of("javaclaw-app-server"));
        assertImportsOnlyInAllModules("com.microsoft.playwright", Set.of("javaclaw-browser-service"));
        assertImportsOnlyInAllModules("javafx", Set.of("javaclaw-desktop"));
        assertImportsOnlyInAllModules("java.lang.foreign", Set.of("javaclaw-native-hosts"));
    }

    @Test
    void clientsCannotReachServerOrAgentInternals() throws Exception {
        assertNoMatch(
                javaSources("javaclaw-client"),
                Pattern.compile("(?:import|requires)\\s+com\\.javaclaw\\.(?:agent|server|core\\.api|sandbox)"),
                "Client must communicate through the protocol only");
        assertNoMatch(
                javaSources("javaclaw-desktop"),
                Pattern.compile("(?:import|requires)\\s+com\\.javaclaw\\.(?:agent|server|core\\.api|"
                        + "sandbox|cli|protocol)"),
                "Desktop may use only SDK types, never protocol, CLI, runtime, store or server code");
        assertNoMatch(
                filesUnder("javaclaw-client", "src/main/java/com/javaclaw/cli"),
                Pattern.compile("(?:import|requires)\\s+(?:com\\.javaclaw\\.protocol|" + "com\\.fasterxml\\.jackson)"),
                "CLI may use only SDK and JDK types");
        assertNoMatch(
                javaSources("javaclaw-desktop"),
                Pattern.compile("(?:import|requires)\\s+com\\.fasterxml\\.jackson"),
                "Desktop must not parse protocol JSON");

        for (String client : List.of(
                "WorkspaceClient.java",
                "ThreadClient.java",
                "ModelClient.java",
                "AutomationClient.java",
                "KnowledgeClient.java",
                "ExtensionClient.java",
                "AttachmentClient.java",
                "AdministrationClient.java")) {
            Path source = root().resolve("javaclaw-client/src/main/java/com/javaclaw/sdk")
                    .resolve(client);
            String text = javaSource(source);
            assertFalse(
                    Pattern.compile("com\\.javaclaw\\.protocol|"
                                    + "com\\.fasterxml\\.jackson|\\bJsonNode\\b|\\bWire[A-Z]")
                            .matcher(text)
                            .find(),
                    () -> "SDK domain client crossed its internal protocol mapper: " + client);
        }
    }

    @Test
    void cleanUseCaseAndPersistenceCallChainCannotRegress() throws Exception {
        for (String movedPort : List.of(
                "ModelGateway.java",
                "EmbeddingGateway.java",
                "ContextContributor.java",
                "WorkspaceUseCases.java",
                "ThreadUseCases.java",
                "TurnUseCases.java",
                "InteractionUseCases.java",
                "RuntimeStreams.java",
                "ThreadJournal.java",
                "EventOutbox.java",
                "AttachmentRepository.java",
                "AgentKernel.java",
                "ItemSink.java",
                "LiveItemSource.java",
                "BrowserGateway.java",
                "CollaborationGateway.java")) {
            assertFalse(
                    Files.exists(root().resolve("javaclaw-api/src/main/java/com/javaclaw/core/api/" + movedPort)),
                    () -> "runtime-only port leaked back into javaclaw-api: " + movedPort);
        }

        String session = javaSource(root().resolve(
                        "javaclaw-app-server/src/main/java/com/javaclaw/server/transport/" + "AppServerSession.java"));
        for (String forbidden : List.of(
                "ProfileService",
                "KnowledgeService",
                "AutomationRuntime",
                "ProviderService",
                "PluginService",
                "McpService",
                "H2Persistence",
                "ModelGateway")) {
            assertFalse(
                    session.contains(forbidden),
                    () -> "AppServerSession directly depends on feature implementation " + forbidden);
        }
        assertFalse(
                session.contains("threads.readThread("),
                "live notifications must use narrow Turn/Item lookups, not reload snapshots");
        assertTrue(
                session.contains("threads.readTurn(") && session.contains("threads.readItem("),
                "live notification enrichment must use narrow query ports");

        for (Path service : List.of(
                root().resolve("javaclaw-app-server/src/main/java/com/javaclaw/server/" + "model/ProviderService.java"),
                root().resolve("javaclaw-app-server/src/main/java/com/javaclaw/server/"
                        + "extension/PluginService.java"),
                root().resolve("javaclaw-app-server/src/main/java/com/javaclaw/server/"
                        + "diagnostics/DiagnosticsService.java"),
                root().resolve("javaclaw-app-server/src/main/java/com/javaclaw/server/"
                        + "extension/mcp/McpService.java"))) {
            String text = javaSource(service);
            assertFalse(
                    text.contains("import com.javaclaw.protocol"),
                    () -> "server use case returned a protocol type: " + relative(service));
            assertFalse(
                    Pattern.compile("public\\s+[^;{]*\\bJsonNode\\b")
                            .matcher(text)
                            .find(),
                    () -> "server use case exposed JsonNode: " + relative(service));
        }

        String engine = javaSource(root().resolve("javaclaw-app-server/src/main/java/com/javaclaw/server/persistence/"
                + "H2PersistenceEngine.java"));
        assertFalse(
                engine.contains("implements WorkspaceRepository")
                        || engine.contains("implements ThreadJournal")
                        || engine.contains("implements EventOutbox")
                        || engine.contains("implements InteractionRepository")
                        || engine.contains("implements AttachmentRepository"),
                "the internal H2 coordinator must not be exposed as a fat repository");
        for (String adapter : List.of(
                "H2WorkspaceRepository.java",
                "H2ThreadJournal.java",
                "H2EventOutbox.java",
                "H2InteractionRepository.java",
                "H2AttachmentRepository.java",
                "H2ServerConfigurationRepository.java")) {
            assertTrue(
                    Files.isRegularFile(root().resolve(
                                    "javaclaw-app-server/src/main/java/com/javaclaw/server/persistence/" + adapter)),
                    () -> "missing narrow H2 adapter: " + adapter);
        }

        Path persistenceRoot = root().resolve("javaclaw-app-server/src/main/java/com/javaclaw/server/persistence");
        for (String independent : List.of(
                "H2WorkspaceRepository.java",
                "H2EventOutbox.java",
                "H2InteractionRepository.java",
                "H2ServerConfigurationRepository.java")) {
            String text = javaSource(persistenceRoot.resolve(independent));
            assertFalse(text.contains("H2PersistenceEngine"), () -> independent + " regressed to a fat-store delegate");
        }
        Set<String> engineOwners = new LinkedHashSet<>();
        for (Path source : filesUnder("javaclaw-app-server", "src/main/java/com/javaclaw/server/persistence")) {
            if (javaSource(source).contains("H2PersistenceEngine")) {
                engineOwners.add(source.getFileName().toString());
            }
        }
        assertEquals(
                Set.of(
                        "H2PersistenceEngine.java",
                        "H2Persistence.java",
                        "H2ThreadJournal.java",
                        "H2AttachmentRepository.java"),
                engineOwners,
                "only the transaction coordinator and the two remaining aggregate adapters "
                        + "may reference the internal persistence engine");
    }

    @Test
    void transportContainsOnlyProtocolAndConnectionResponsibilities() throws Exception {
        List<Path> transport = filesUnder("javaclaw-app-server", "src/main/java/com/javaclaw/server/transport");
        assertNoMatch(
                transport,
                Pattern.compile("(?:import|requires)\\s+com\\.javaclaw\\.server\\.persistence"),
                "transport must not depend on H2 adapters");
        assertNoMatch(
                transport,
                Pattern.compile("\\b(?:ProfileService|KnowledgeService|AutomationRuntime|"
                        + "ProviderService|PluginService|DiagnosticsService|"
                        + "McpService|H2[A-Z][A-Za-z0-9_$]*)\\b"),
                "transport must depend on narrow use-case interfaces, not implementations");

        Set<String> handlers = new LinkedHashSet<>();
        for (Path source : transport) {
            String name = source.getFileName().toString();
            if (name.endsWith("RpcHandler.java") && !"RpcHandler.java".equals(name)) {
                handlers.add(name);
            }
        }
        assertEquals(
                Set.of(
                        "WorkspaceRpcHandler.java",
                        "ThreadRpcHandler.java",
                        "ModelRpcHandler.java",
                        "AutomationRpcHandler.java",
                        "KnowledgeRpcHandler.java",
                        "ExtensionRpcHandler.java",
                        "AttachmentRpcHandler.java",
                        "AdministrationRpcHandler.java"),
                handlers);

        Path bootstrap = root().resolve("javaclaw-app-server/src/main/java/com/javaclaw/"
                + "server/bootstrap/ServerComponentGraph.java");
        assertTrue(Files.isRegularFile(bootstrap));
        assertFalse(Files.exists(root().resolve("javaclaw-app-server/src/main/java/"
                + "com/javaclaw/server/transport/ServerComponentGraph.java")));
        assertFalse(Files.exists(root().resolve(
                        "javaclaw-app-server/src/main/java/" + "com/javaclaw/server/transport/AppServerMain.java")));

        String main = javaSource(root().resolve(
                        "javaclaw-app-server/src/main/java/" + "com/javaclaw/server/bootstrap/AppServerMain.java"));
        assertTrue(main.contains("ServerComponentGraph.create(args)"));
        assertFalse(
                Pattern.compile("H2|ObjectMapper|AgentLoopKernel|PluginService")
                        .matcher(main)
                        .find(),
                "AppServerMain must only own the component graph");
    }

    @Test
    void discoveryAndBusinessServicesCannotSerializeProtocolDirectly() throws Exception {
        List<Path> discovery = filesUnder("javaclaw-app-server", "src/main/java/com/javaclaw/server/discovery");
        assertNoMatch(
                discovery,
                Pattern.compile("com\\.fasterxml\\.jackson|\\bJsonNode\\b|valueToTree"),
                "discovery must return typed domain snapshots");
        assertTrue(Files.isRegularFile(root().resolve(
                        "javaclaw-app-server/src/main/java/" + "com/javaclaw/server/transport/ProtocolMapper.java")));
    }

    @Test
    void nativeInteropIsFfmOnlyAndTheFfmPackageIsNotExported() throws Exception {
        assertNoMatch(
                allJavaSources(),
                Pattern.compile("(?:System\\.loadLibrary\\s*\\(|System\\.load\\s*\\(|com\\.sun\\.jna|"
                        + "native\\s+[A-Za-z_$][A-Za-z0-9_$]*\\s*\\()"),
                "JavaClaw-owned native integration must use JDK FFM, never JNI/JNA");

        String descriptor = javaSource(root().resolve("javaclaw-native-hosts/src/main/java/module-info.java"));
        assertTrue(descriptor.contains("module com.javaclaw.nativehosts"));
        assertFalse(
                descriptor.contains("exports com.javaclaw.nativehost.ffm"),
                "raw FFM bindings must not be exported from the native helper module");
        assertTrue(descriptor.contains("requires com.javaclaw.api"));
        assertTrue(descriptor.contains("requires com.javaclaw.protocol"));

        Path nativeRoot = root().resolve("javaclaw-native-hosts/src/main/java/com/javaclaw/nativehost");
        assertTrue(Files.isRegularFile(nativeRoot.resolve("sandbox/SandboxLauncherMain.java")));
        assertTrue(Files.isRegularFile(nativeRoot.resolve("transport/windows/WindowsTransportHostMain.java")));
        String launcher = javaSource(root().resolve("javaclaw-agent-runtime/src/main/java/com/javaclaw/agent/tool/"
                + "LauncherProcessSandboxExecutor.java"));
        assertTrue(launcher.contains("--enable-native-access=com.javaclaw.nativehosts"));
        assertTrue(
                launcher.contains("com.javaclaw.nativehosts/" + "com.javaclaw.nativehost.sandbox.SandboxLauncherMain"));
    }

    @Test
    void processCreationHasAnExplicitBoundary() throws Exception {
        Map<String, Set<String>> allowed = Map.of(
                "javaclaw-agent-runtime", Set.of("LauncherProcessSandboxExecutor.java"),
                "javaclaw-client", Set.of("AppServerProcess.java", "WindowsTransportBridge.java"),
                "javaclaw-native-hosts", Set.of("SandboxProcessRunner.java", "WindowsTransportHostMain.java"));
        List<String> violations = new ArrayList<>();
        for (String module : MODULES) {
            for (Path source : javaSources(module)) {
                String text = javaSource(source);
                boolean startsProcess = Pattern.compile("new\\s+(?:java\\.lang\\.)?ProcessBuilder\\s*\\(")
                                .matcher(text)
                                .find()
                        || text.matches("(?s).*Runtime\\.getRuntime\\s*\\(\\)\\.exec.*");
                if (startsProcess
                        && !allowed.getOrDefault(module, Set.of())
                                .contains(source.getFileName().toString())) {
                    violations.add(relative(source));
                }
            }
        }
        assertTrue(violations.isEmpty(), () -> "unexpected process creation sites: " + violations);
    }

    @Test
    void legacyRuntimeAndPluginThreeTreesCannotReturn() throws Exception {
        assertFalse(Files.exists(root().resolve("src")), "the root 3.x production/test tree must remain deleted");
        assertFalse(
                Files.exists(root().resolve("sample-plugins")),
                "Plugin 3 and local-inference samples must not ship in 4.0");
        Set<String> forbiddenFiles = Set.of(
                "TrustedExtensionInstaller.java",
                "WorkspaceRuntime.java",
                "TurnPipeline.java",
                "ChatHistoryStore.java",
                "AgentConversationRunner.java",
                "ConversationEvent.java",
                "ScriptedModelGateway.java",
                "DefaultThreadService.java",
                "ThreadService.java",
                "ThreadStore.java",
                "H2ThreadStore.java",
                "ToolRuntime.java",
                "AppServerFeatures.java");
        List<String> violations = allJavaSources().stream()
                .filter(path -> forbiddenFiles.contains(path.getFileName().toString()))
                .map(this::relative)
                .toList();
        assertTrue(violations.isEmpty(), () -> "legacy runtime types returned: " + violations);
    }

    @Test
    void nativeSecurityAndDistributionGatesUseTheMergedArtifacts() throws Exception {
        String windows = javaSource(root().resolve(
                        "javaclaw-native-hosts/src/main/java/com/javaclaw/nativehost/ffm/" + "WindowsSandbox.java"));
        assertTrue(
                windows.contains("CreatePseudoConsole")
                        && windows.contains("ResizePseudoConsole")
                        && windows.contains("JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE"),
                "Windows sessions require ConPTY inside the Job boundary");
        String linux = javaSource(root().resolve(
                        "javaclaw-native-hosts/src/main/java/com/javaclaw/nativehost/ffm/" + "LinuxSecurity.java"));
        assertTrue(
                linux.contains("SECCOMP_SET_MODE_FILTER") && linux.contains("PR_SET_NO_NEW_PRIVS"),
                "Linux execution requires no-new-privileges plus seccomp");

        String packaging = Files.readString(root().resolve("javaclaw-packaging/pom.xml"));
        for (String artifact : List.of(
                "javaclaw-app-server",
                "javaclaw-native-hosts",
                "javaclaw-browser-service",
                "javaclaw-client",
                "javaclaw-desktop")) {
            assertTrue(packaging.contains("<artifactId>" + artifact + "</artifactId>"));
        }
        for (String removed : REMOVED_MODULES) {
            assertFalse(
                    packaging.contains("<artifactId>" + removed + "</artifactId>"),
                    () -> "packaging still depends on removed artifact " + removed);
        }
        assertTrue(packaging.contains("cyclonedx-maven-plugin"));
        assertTrue(packaging.contains("THIRD-PARTY.txt"));
        assertTrue(packaging.contains("<id>stage-native-module-path</id>"));
        assertTrue(packaging.contains("<id>validate-native-module-path</id>"));
        assertTrue(packaging.contains("${javaclaw.distribution.root}/lib/native-module-path"));
        assertTrue(packaging.contains("--validate-modules"));
        assertTrue(packaging.contains("<id>release-macos-signing-gate</id>"));
        assertTrue(packaging.contains("<id>release-linux-signing-gate</id>"));
        assertTrue(packaging.contains("<id>release-windows-signing-gate</id>"));

        String workflow = Files.readString(root().resolve(".github/workflows/javaclaw-v4.yml"));
        assertTrue(workflow.contains("-Djavaclaw.require.native.sandbox=true"));
        assertTrue(workflow.contains("-Djavaclaw.performance.gate=true"));
        assertTrue(workflow.contains("javaclaw-client/target/performance-gate.json"));
        assertFalse(workflow.contains("javaclaw-sdk-java/target/performance-gate.json"));

        String posixLauncher =
                Files.readString(root().resolve("javaclaw-packaging/src/main/distribution/bin/javaclaw-cli"));
        String windowsLauncher =
                Files.readString(root().resolve("javaclaw-packaging/src/main/distribution/bin/javaclaw-cli.cmd"));
        assertTrue(posixLauncher.contains("JAVACLAW_SANDBOX_MODULE_PATH=\"$APP_ROOT/" + "native-module-path\""));
        assertTrue(windowsLauncher.contains("JAVACLAW_SANDBOX_MODULE_PATH=%APP_ROOT%\\native-module-path"));

        String desktopGraph = javaSource(
                root().resolve("javaclaw-desktop/src/main/java/com/javaclaw/desktop/" + "DesktopComponentGraph.java"));
        assertTrue(desktopGraph.contains("library.getParent().resolve(\"native-module-path\")"));
        assertTrue(desktopGraph.contains("library.resolve(\"native-module-path\")"));
    }

    private void assertImportsOnlyIn(String module, String importedPrefix, Set<String> allowedPathFragments)
            throws Exception {
        List<String> violations = new ArrayList<>();
        for (Path source : javaSources(module)) {
            String text = javaSource(source);
            if (!imports(text, importedPrefix)) {
                continue;
            }
            String normalized = source.toString().replace('\\', '/');
            if (allowedPathFragments.stream().noneMatch(normalized::contains)) {
                violations.add(relative(source));
            }
        }
        assertTrue(violations.isEmpty(), () -> importedPrefix + " escaped its functional package: " + violations);
    }

    private void assertImportsOnlyInAllModules(String importedPrefix, Set<String> allowedModules) throws Exception {
        List<String> violations = new ArrayList<>();
        for (String module : MODULES) {
            for (Path source : javaSources(module)) {
                if (imports(javaSource(source), importedPrefix) && !allowedModules.contains(module)) {
                    violations.add(relative(source));
                }
            }
        }
        assertTrue(violations.isEmpty(), () -> importedPrefix + " escaped owning module: " + violations);
    }

    private void assertNoMatch(List<Path> sources, Pattern pattern, String message) throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path source : sources) {
            if (pattern.matcher(javaSource(source)).find()) {
                violations.add(relative(source));
            }
        }
        assertTrue(violations.isEmpty(), () -> message + ": " + violations);
    }

    private List<Path> allJavaSources() throws IOException {
        List<Path> result = new ArrayList<>();
        for (String module : MODULES) {
            result.addAll(javaSources(module));
        }
        return result;
    }

    @Test
    void architectureChecksIgnoreFormattingWithoutIgnoringExecutableCode() throws Exception {
        String compact = """
                import java.util.List;
                class Example { void execute() {
                    new ProcessBuilder(List.of("echo", "a b"));
                    Runtime.getRuntime().exec("echo");
                } }
                """;
        String expanded = """
                import
                    java.util.List;
                /** 注释中的 ServerService 不应被误识别为真实依赖。 */
                class Example {
                    void execute() {
                        new ProcessBuilder (
                            List.of("echo", "a b")
                        );
                        Runtime
                            .getRuntime()
                            .exec("echo");
                    }
                }
                """;
        String canonical = parseJava(compact);
        assertEquals(canonical, parseJava(expanded));
        assertTrue(canonical.contains("new ProcessBuilder("));
        assertTrue(canonical.contains("Runtime.getRuntime().exec("));
        assertTrue(canonical.contains("\"a b\""), "不能靠删除所有空白来归一化 Java 字符串");
        assertFalse(canonical.contains("ServerService"));
    }

    private static boolean imports(String source, String prefix) {
        return Pattern.compile("\\bimport\\s+(?:static\\s+)?" + Pattern.quote(prefix))
                .matcher(source)
                .find();
    }

    private String javaSource(Path path) throws IOException {
        String cached = parsedSources.get(path);
        if (cached == null) {
            cached = parseJava(Files.readString(path));
            parsedSources.put(path, cached);
        }
        return cached;
    }

    /** 使用 JDK 的语法树规范化排版；不删字符串空格，不把注释当依赖，也不忽略解析失败。 */
    private static String parseJava(String source) throws IOException {
        var compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IOException("源码架构门禁需要完整 JDK");
        }
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        JavaFileObject input =
                new SimpleJavaFileObject(URI.create("string:///Source.java"), JavaFileObject.Kind.SOURCE) {
                    @Override
                    public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                        return source;
                    }
                };
        try (var files = compiler.getStandardFileManager(diagnostics, null, java.nio.charset.StandardCharsets.UTF_8)) {
            JavacTask task = (JavacTask) compiler.getTask(
                    null, files, diagnostics, List.of("-proc:none", "--release", "25"), null, List.of(input));
            var unit = task.parse().iterator().next();
            StringBuilder normalized = new StringBuilder();
            if (unit.getPackage() != null) {
                normalized.append(unit.getPackage()).append('\n');
            }
            unit.getImports().forEach(value -> normalized.append(value).append('\n'));
            if (unit.getModule() != null) {
                normalized.append(unit.getModule());
            }
            // 分别渲染声明，不渲染整个 compilation unit；后者会把 Javadoc 表一并打印出来。
            unit.getTypeDecls().forEach(value -> normalized.append(value).append('\n'));
            if (diagnostics.getDiagnostics().stream().anyMatch(value -> value.getKind() == Diagnostic.Kind.ERROR)) {
                throw new IOException("无法解析待检查源码：" + diagnostics.getDiagnostics());
            }
            return normalized.toString();
        }
    }

    private List<Path> javaSources(String module) throws IOException {
        return filesUnder(module, "src/main/java");
    }

    private List<Path> filesUnder(String module, String path) throws IOException {
        Path sourceRoot = root().resolve(module).resolve(path);
        if (!Files.isDirectory(sourceRoot)) {
            return List.of();
        }
        try (var stream = Files.walk(sourceRoot)) {
            return stream.filter(value -> Files.isRegularFile(value)
                            && value.getFileName().toString().endsWith(".java"))
                    .toList();
        }
    }

    private List<String> reactorModules() throws Exception {
        Element project = DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .parse(root().resolve("pom.xml").toFile())
                .getDocumentElement();
        Element modules = directChild(project, "modules");
        List<String> result = new ArrayList<>();
        for (Node node = modules.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && "module".equals(element.getTagName())) {
                result.add(element.getTextContent().strip());
            }
        }
        return List.copyOf(result);
    }

    private Set<String> productionModuleDependencies(String module) throws Exception {
        Element project = DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .parse(root().resolve(module).resolve("pom.xml").toFile())
                .getDocumentElement();
        Element dependencies = directChildOrNull(project, "dependencies");
        if (dependencies == null) {
            return Set.of();
        }
        Set<String> result = new LinkedHashSet<>();
        for (Node node = dependencies.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (!(node instanceof Element dependency) || !"dependency".equals(dependency.getTagName())) {
                continue;
            }
            if (!"com.javaclaw".equals(childText(dependency, "groupId"))) {
                continue;
            }
            if ("test".equals(childText(dependency, "scope"))) {
                continue;
            }
            result.add(childText(dependency, "artifactId"));
        }
        return Set.copyOf(result);
    }

    private static void assertAcyclic(Map<String, Set<String>> graph) {
        Set<String> visited = new HashSet<>();
        Set<String> active = new HashSet<>();
        for (String module : graph.keySet()) {
            visit(module, graph, visited, active);
        }
    }

    private static void visit(String module, Map<String, Set<String>> graph, Set<String> visited, Set<String> active) {
        if (visited.contains(module)) {
            return;
        }
        assertTrue(active.add(module), () -> "cyclic Maven dependency at " + module);
        for (String dependency : graph.getOrDefault(module, Set.of())) {
            visit(dependency, graph, visited, active);
        }
        active.remove(module);
        visited.add(module);
    }

    private static Element directChild(Element parent, String name) {
        Element result = directChildOrNull(parent, name);
        if (result == null) {
            throw new IllegalStateException("missing <" + name + ">");
        }
        return result;
    }

    private static Element directChildOrNull(Element parent, String name) {
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && name.equals(element.getTagName())) {
                return element;
            }
        }
        return null;
    }

    private static String childText(Element parent, String name) {
        Element child = directChildOrNull(parent, name);
        return child == null ? "" : child.getTextContent().strip();
    }

    private String relative(Path path) {
        return root().relativize(path.toAbsolutePath().normalize()).toString();
    }

    private Path root() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("pom.xml")) && Files.isDirectory(current.resolve("javaclaw-api"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("cannot locate JavaClaw reactor root");
    }
}
