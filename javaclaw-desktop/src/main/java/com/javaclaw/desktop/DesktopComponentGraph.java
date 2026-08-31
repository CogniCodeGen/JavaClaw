package com.javaclaw.desktop;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;

import com.javaclaw.sdk.AppServerProcess;
import com.javaclaw.sdk.JavaClawClient;
import com.javaclaw.sdk.LocalSocketClient;
import com.javaclaw.sdk.WindowsTransportBridge;

/** Explicit desktop object graph. No Spring context or workspace child context exists. */
public final class DesktopComponentGraph implements AutoCloseable {
    private final AutoCloseable transportOwner;
    private final JavaClawClient client;
    private final DesktopViewModel viewModel;
    private final JavaFxDesktopDialogGateway dialogs;
    private boolean closed;

    private DesktopComponentGraph(
            AutoCloseable transportOwner,
            JavaClawClient client,
            DesktopViewModel viewModel,
            JavaFxDesktopDialogGateway dialogs) {
        this.transportOwner = transportOwner;
        this.client = client;
        this.viewModel = viewModel;
        this.dialogs = dialogs;
    }

    /**
     * 使用已有环境或发行目录连接 App Server 并完成握手；必须在 JavaFX Application Thread 之外调用。 返回值负责关闭本次创建的传输及订阅，不会停止显式连接的外部服务。
     *
     * @throws Exception 连接、启动命令解析或组件装配失败
     */
    public static DesktopComponentGraph create() throws Exception {
        return create(DesktopLaunchConfiguration.automatic());
    }

    /**
     * 使用非空启动配置连接服务端，在最多 15 秒的首次握手后装配 UI 状态；失败时关闭本次持有的传输。 必须在 JavaFX Application Thread 之外调用；后续断线恢复仍由 SDK 管理。
     *
     * @param configuration 从 IDE 或发行布局解析的非空基础设施默认值
     * @return 已完成握手的组件图，由调用方在退出或 UI 装配失败时关闭
     * @throws Exception 启动、握手或组件装配失败，关闭异常附加到原始异常
     */
    public static DesktopComponentGraph create(DesktopLaunchConfiguration configuration) throws Exception {
        Objects.requireNonNull(configuration, "configuration");
        ClientTransport transport = connect(configuration, System.getenv());
        try {
            transport.client().initialize("javaclaw-desktop", "4.0.0-SNAPSHOT").get(15, TimeUnit.SECONDS);
            var dialogs = new JavaFxDesktopDialogGateway();
            DesktopViewModel viewModel =
                    new DesktopViewModel(transport.client(), FxDispatcher.platform(), true, dialogs);
            return new DesktopComponentGraph(transport.owner(), transport.client(), viewModel, dialogs);
        } catch (Exception | Error failure) {
            try {
                transport.owner().close();
            } catch (Exception cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            if (failure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            if (failure instanceof Error error) {
                throw error;
            }
            throw new IOException("App Server 启动或 initialize 握手失败，请检查数据目录与服务端日志", failure);
        }
    }

    /**
     * 在 JavaFX 线程加载主 FXML，并通过显式 controller factory 注入当前 ViewModel；不访问 Spring 或数据库。
     *
     * @throws IOException FXML 读取或控制器构建失败
     */
    public Parent loadMainView() throws IOException {
        FXMLLoader loader = new FXMLLoader(DesktopComponentGraph.class.getResource("/fxml/main.fxml"));
        loader.setControllerFactory(type -> {
            if (type == MainController.class) {
                return new MainController(viewModel);
            }
            throw new IllegalArgumentException("unsupported FXML controller: " + type.getName());
        });
        return loader.load();
    }

    /** 注入 JavaFX HostServices 打开已确认的 HTTPS OAuth 页面；不使用自建进程启动器。 */
    public void setExternalBrowserOpener(java.util.function.Consumer<java.net.URI> opener) {
        dialogs.setExternalBrowser(opener);
    }

    private static ClientTransport connect(DesktopLaunchConfiguration configuration, Map<String, String> environment)
            throws Exception {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("windows")) {
            List<String> bridge = commandFromJson(environment.get("JAVACLAW_WINDOWS_TRANSPORT_COMMAND_JSON"));
            String pipe = environment.getOrDefault("JAVACLAW_WINDOWS_PIPE", "javaclaw-v4-desktop");
            if (!bridge.isEmpty()) {
                JavaClawClient client = WindowsTransportBridge.connect(bridge, pipe);
                return new ClientTransport(client, client);
            }
            List<String> packagedHost = configuration.windowsTransportCommand().isEmpty()
                    ? packagedWindowsTransportCommand()
                    : configuration.windowsTransportCommand();
            if (packagedHost.isEmpty()) {
                throw new IllegalStateException("Windows requires the packaged Native Transport "
                        + "Host or JAVACLAW_WINDOWS_TRANSPORT_COMMAND_JSON; stdio fallback is disabled");
            }
            WindowsTransportBridge.ManagedTransport transport = WindowsTransportBridge.startLocalAppServer(
                    packagedHost,
                    pipe,
                    appServerCommand(configuration, environment),
                    infrastructureEnvironment(configuration, environment));
            return new ClientTransport(transport.client(), transport);
        }
        String socket = environment.get("JAVACLAW_APP_SERVER_SOCKET");
        if (socket != null && !socket.isBlank()) {
            JavaClawClient client = LocalSocketClient.connect(Path.of(socket));
            return new ClientTransport(client, client);
        }
        AppServerProcess process = new AppServerProcess(
                appServerCommand(configuration, environment), infrastructureEnvironment(configuration, environment));
        return new ClientTransport(process.client(), process);
    }

    static List<String> appServerCommand(DesktopLaunchConfiguration configuration, Map<String, String> environment)
            throws Exception {
        List<String> configured = commandFromJson(environment.get("JAVACLAW_APP_SERVER_COMMAND_JSON"));
        ArrayList<String> command = new ArrayList<>();
        if (!configured.isEmpty()) {
            command.addAll(configured);
        } else {
            String classpath = environment.get("JAVACLAW_APP_SERVER_CLASSPATH");
            if ((classpath == null || classpath.isBlank())
                    && !configuration.appServerCommand().isEmpty()) {
                command.addAll(configuration.appServerCommand());
            } else {
                if (classpath == null || classpath.isBlank()) {
                    Path packaged = packagedLibrary();
                    classpath = packaged == null
                            ? System.getProperty("java.class.path", "")
                            : packaged.resolve("*").toString();
                }
                if (classpath.isBlank()) {
                    throw new IllegalStateException("Packaged App Server classpath is missing. Set "
                            + "JAVACLAW_APP_SERVER_COMMAND_JSON or JAVACLAW_APP_SERVER_CLASSPATH.");
                }
                command.add(Path.of(
                                System.getProperty("java.home"),
                                "bin",
                                System.getProperty("os.name", "")
                                                .toLowerCase(Locale.ROOT)
                                                .contains("windows")
                                        ? "java.exe"
                                        : "java")
                        .toString());
                command.addAll(List.of("-cp", classpath, "com.javaclaw.server.bootstrap.AppServerMain"));
            }
        }
        addPathOption(command, "--data-dir", environment.get("JAVACLAW_DATA_DIR"));
        addPathOption(command, "--config-dir", environment.get("JAVACLAW_CONFIG_DIR"));
        addPathOption(command, "--cache-dir", environment.get("JAVACLAW_CACHE_DIR"));
        return List.copyOf(command);
    }

    private static List<String> commandFromJson(String encoded) throws Exception {
        if (encoded == null || encoded.isBlank()) {
            return List.of();
        }
        return AppServerProcess.parseInfrastructureCommand(encoded);
    }

    private static void addPathOption(List<String> command, String option, String value) {
        if (command.contains(option)) {
            return;
        }
        if (value == null || value.isBlank()) {
            return;
        }
        command.add(option);
        command.add(Path.of(value).toAbsolutePath().normalize().toString());
    }

    static Map<String, String> infrastructureEnvironment(
            DesktopLaunchConfiguration configuration, Map<String, String> environment) {
        Map<String, String> defaults = configuration.infrastructureEnvironment().isEmpty()
                ? packagedInfrastructureEnvironment()
                : configuration.infrastructureEnvironment();
        LinkedHashMap<String, String> result = new LinkedHashMap<>(defaults);
        for (String name : List.of(
                "JAVACLAW_PROGRAM_DIR",
                "JAVACLAW_DATA_DIR",
                "JAVACLAW_CONFIG_DIR",
                "JAVACLAW_CACHE_DIR",
                "JAVACLAW_SANDBOX_MODULE_PATH",
                "JAVACLAW_BROWSER_SERVICE_LIB",
                "JAVACLAW_BROWSER_SERVICE_COMMAND_JSON",
                "JAVACLAW_BROWSER_ASSET_DIR")) {
            String value = environment.get(name);
            if (value != null && !value.isBlank()) {
                result.put(name, value);
            }
        }
        if (environment.containsKey("JAVACLAW_BROWSER_SERVICE_LIB")
                && !environment.get("JAVACLAW_BROWSER_SERVICE_LIB").isBlank()
                && environment
                        .getOrDefault("JAVACLAW_BROWSER_SERVICE_COMMAND_JSON", "")
                        .isBlank()) {
            // 显式的库目录应由服务端解析，不能被自动生成的 Browser argv 遮盖。
            result.remove("JAVACLAW_BROWSER_SERVICE_COMMAND_JSON");
        }
        return Map.copyOf(result);
    }

    private static Map<String, String> packagedInfrastructureEnvironment() {
        try {
            Path library = packagedLibrary();
            if (library == null) {
                return Map.of();
            }
            Path java = Path.of(
                    System.getProperty("java.home"),
                    "bin",
                    System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows")
                            ? "java.exe"
                            : "java");
            Path nativeModulePath = packagedNativeModulePath(library);
            if (nativeModulePath == null) {
                return Map.of();
            }
            String browser = AppServerProcess.encodeInfrastructureCommand(List.of(
                    java.toString(),
                    "-cp",
                    library.resolve("*").toString(),
                    "com.javaclaw.browser.BrowserServiceMain"));
            return Map.of(
                    "JAVACLAW_SANDBOX_MODULE_PATH", nativeModulePath.toString(),
                    "JAVACLAW_BROWSER_SERVICE_LIB", library.toString(),
                    "JAVACLAW_BROWSER_SERVICE_COMMAND_JSON", browser,
                    "JAVACLAW_BROWSER_ASSET_DIR",
                            library.resolve("ms-playwright").toString());
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private static List<String> packagedWindowsTransportCommand() {
        try {
            Path library = packagedLibrary();
            if (library == null) {
                return List.of();
            }
            Path nativeModulePath = packagedNativeModulePath(library);
            if (nativeModulePath == null
                    || !Files.isRegularFile(nativeModulePath.resolve("com.javaclaw.javaclaw-native-hosts.jar"))) {
                return List.of();
            }
            Path java = Path.of(System.getProperty("java.home"), "bin", "java.exe");
            if (!Files.isExecutable(java)) {
                return List.of();
            }
            return List.of(
                    java.toString(),
                    "--module-path",
                    nativeModulePath.toString(),
                    "--add-modules",
                    "com.javaclaw.nativehosts",
                    "--enable-native-access=com.javaclaw.nativehosts",
                    "-m",
                    "com.javaclaw.nativehosts/" + "com.javaclaw.nativehost.transport.windows.WindowsTransportHostMain");
        } catch (RuntimeException failure) {
            return List.of();
        }
    }

    private static Path packagedNativeModulePath(Path library) {
        Path distributionLayout = library.getParent().resolve("native-module-path");
        if (Files.isDirectory(distributionLayout)) {
            return distributionLayout;
        }
        Path jpackageLayout = library.resolve("native-module-path");
        return Files.isDirectory(jpackageLayout) ? jpackageLayout : null;
    }

    private static Path packagedLibrary() {
        try {
            Path location = Path.of(DesktopComponentGraph.class
                            .getProtectionDomain()
                            .getCodeSource()
                            .getLocation()
                            .toURI())
                    .toAbsolutePath()
                    .normalize();
            Path library = Files.isDirectory(location) ? location : location.getParent();
            if (library == null
                    || !Files.isDirectory(library)
                    || !Files.isRegularFile(library.resolve("com.javaclaw.javaclaw-app-server.jar"))) {
                return null;
            }
            return library;
        } catch (Exception ignored) {
            return null;
        }
    }

    /** 关闭订阅和本次持有的传输；重复调用无副作用。 同步边界使 JVM 关闭钩子等待窗口线程完成清理，避免把“正在关闭”误当成“已关闭”。 */
    @Override
    public synchronized void close() throws Exception {
        if (closed) {
            return;
        }
        closed = true;
        try {
            viewModel.close();
        } finally {
            transportOwner.close();
        }
    }

    private record ClientTransport(JavaClawClient client, AutoCloseable owner) {}
}
