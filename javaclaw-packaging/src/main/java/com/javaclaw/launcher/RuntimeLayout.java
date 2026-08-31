package com.javaclaw.launcher;

import java.io.File;
import java.io.IOException;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReference;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.ZipFile;

import com.javaclaw.desktop.DesktopLaunchConfiguration;
import com.javaclaw.sdk.AppServerProcess;

/** 只读取当前运行 classpath 与相邻发行目录，不加载服务端类，也不扫描或改写用户目录。 */
final class RuntimeLayout {
    private static final String NATIVE_MODULE = "com.javaclaw.nativehosts";
    private static final String SERVER_MAIN = "com.javaclaw.server.bootstrap.AppServerMain";
    private static final String BROWSER_MAIN = "com.javaclaw.browser.BrowserServiceMain";

    private RuntimeLayout() {}

    static DesktopLaunchConfiguration discover() throws Exception {
        Path location = Path.of(JavaClawLauncher.class
                .getProtectionDomain()
                .getCodeSource()
                .getLocation()
                .toURI());
        LinkedHashMap<String, String> environment = new LinkedHashMap<>(System.getenv());
        String configuredProgramDirectory = System.getProperty("javaclaw.program.dir");
        if (configuredProgramDirectory != null && !configuredProgramDirectory.isBlank()) {
            environment.putIfAbsent("JAVACLAW_PROGRAM_DIR", configuredProgramDirectory);
        }
        return resolve(
                location,
                System.getProperty("java.class.path", ""),
                environment,
                Path.of(System.getProperty("java.home")),
                System.getProperty("os.name", ""));
    }

    static DesktopLaunchConfiguration resolve(
            Path launcherLocation, String classpath, Map<String, String> environment, Path javaHome, String osName)
            throws IOException {
        boolean windows = osName.toLowerCase(Locale.ROOT).contains("windows");
        boolean external = configured(
                environment, windows ? "JAVACLAW_WINDOWS_TRANSPORT_COMMAND_JSON" : "JAVACLAW_APP_SERVER_SOCKET");
        boolean customServer = configured(environment, "JAVACLAW_APP_SERVER_COMMAND_JSON")
                || configured(environment, "JAVACLAW_APP_SERVER_CLASSPATH");
        Path explicitProgramDirectory = configuredPath(environment.get("JAVACLAW_PROGRAM_DIR"));
        if (external || (customServer && !windows)) {
            // 已指定的外部服务或完整启动命令不依赖本机默认服务端产物；环境解析仍交给 Desktop/SDK。
            Path programDirectory = explicitProgramDirectory == null
                    ? Path.of(System.getProperty("user.dir", "."))
                            .toAbsolutePath()
                            .normalize()
                    : explicitProgramDirectory;
            return new DesktopLaunchConfiguration(
                    List.of(), Map.of("JAVACLAW_PROGRAM_DIR", programDirectory.toString()), List.of());
        }

        Path java =
                javaHome.resolve("bin").resolve(windows ? "java.exe" : "java").toRealPath();
        if (!Files.isExecutable(java)) {
            throw new IOException("Java 可执行文件不可用：" + java);
        }
        Path library = packagedLibrary(launcherLocation);
        Path programDirectory =
                explicitProgramDirectory == null ? defaultProgramDirectory(library) : explicitProgramDirectory;
        List<Path> entries = classPathEntries(
                library == null ? classpath : library.resolve("*").toString());
        requireResource(entries, "javafx/application/Application.class", "JavaFX Graphics");
        requireResource(entries, "javafx/fxml/FXMLLoader.class", "JavaFX FXML");
        if (!customServer) {
            requireResource(entries, SERVER_MAIN.replace('.', '/') + ".class", "App Server");
            requireResource(entries, BROWSER_MAIN.replace('.', '/') + ".class", "Browser Service");
        }

        List<Path> nativeCandidates;
        if (configured(environment, "JAVACLAW_SANDBOX_MODULE_PATH")) {
            nativeCandidates = splitModulePath(environment.get("JAVACLAW_SANDBOX_MODULE_PATH"));
        } else if (library != null) {
            Path distributionLayout = library.getParent().resolve("native-module-path");
            Path jpackageLayout = library.resolve("native-module-path");
            Path nativeDirectory = Files.isDirectory(distributionLayout) ? distributionLayout : jpackageLayout;
            if (!Files.isDirectory(nativeDirectory)) {
                throw new IOException("发行目录缺少 native-module-path，请重新构建发行物");
            }
            nativeCandidates = List.of(nativeDirectory);
        } else {
            nativeCandidates = entries;
        }
        List<Path> nativePaths = nativeModulePaths(nativeCandidates);
        String nativePath = joinPaths(nativePaths);
        String applicationPath =
                library == null ? joinPaths(entries) : library.resolve("*").toString();
        List<String> server = customServer ? List.of() : List.of(java.toString(), "-cp", applicationPath, SERVER_MAIN);
        LinkedHashMap<String, String> infrastructure = new LinkedHashMap<>();
        infrastructure.put("JAVACLAW_PROGRAM_DIR", programDirectory.toString());
        infrastructure.put("JAVACLAW_SANDBOX_MODULE_PATH", nativePath);
        if (!customServer) {
            infrastructure.put(
                    "JAVACLAW_BROWSER_SERVICE_COMMAND_JSON",
                    AppServerProcess.encodeInfrastructureCommand(
                            List.of(java.toString(), "-cp", applicationPath, BROWSER_MAIN)));
        }
        if (library != null) {
            infrastructure.put("JAVACLAW_BROWSER_SERVICE_LIB", library.toString());
            Path assets = library.resolve("ms-playwright");
            if (!Files.isRegularFile(assets.resolve("bundle.json"))) {
                throw new IOException("发行目录缺少匹配的浏览器资源清单，请重新构建完整发行物");
            }
            infrastructure.put("JAVACLAW_BROWSER_ASSET_DIR", assets.toString());
        } else {
            // 开发环境只读取程序目录内已显式准备的缓存；不回退到用户主目录，也不在启动时下载软件。
            Path assets = BrowserBundleStager.cache(environment, programDirectory);
            if (Files.isDirectory(assets)) {
                infrastructure.put(
                        "JAVACLAW_BROWSER_ASSET_DIR", assets.toRealPath().toString());
            }
        }
        List<String> host = windows
                ? List.of(
                        java.toString(),
                        "--module-path",
                        nativePath,
                        "--enable-native-access=" + NATIVE_MODULE,
                        "-m",
                        NATIVE_MODULE + "/com.javaclaw.nativehost.transport.windows.WindowsTransportHostMain")
                : List.of();
        return new DesktopLaunchConfiguration(server, infrastructure, host);
    }

    static List<Path> classPathEntries(String classpath) throws IOException {
        LinkedHashSet<Path> result = new LinkedHashSet<>();
        ArrayDeque<Path> pending = new ArrayDeque<>();
        for (String entry : classpath.split(Pattern.quote(File.pathSeparator), -1)) {
            Path path = Path.of(entry.isEmpty() ? "." : entry).toAbsolutePath().normalize();
            if (path.getFileName() != null && "*".equals(path.getFileName().toString())) {
                try (var files = Files.list(path.getParent())) {
                    files.filter(RuntimeLayout::isJar).sorted().forEach(pending::add);
                }
            } else {
                pending.add(path);
            }
        }
        while (!pending.isEmpty()) {
            Path entry = pending.removeFirst().toRealPath();
            if (!result.add(entry) || !isJar(entry)) {
                continue;
            }
            // IDEA 的 manifest classpath 缩短方式同样需要传递给子 JVM，不能只传那个临时 JAR。
            try (JarFile jar = openJar(entry)) {
                var manifest = jar.getManifest();
                String nested =
                        manifest == null ? null : manifest.getMainAttributes().getValue(Attributes.Name.CLASS_PATH);
                if (nested == null || nested.isBlank()) {
                    continue;
                }
                for (String relative : nested.strip().split("\\s+")) {
                    URI uri = entry.toUri().resolve(relative);
                    if (!"file".equalsIgnoreCase(uri.getScheme())) {
                        throw new IOException("启动 classpath 只允许本地文件：" + uri);
                    }
                    Path dependency = Path.of(uri);
                    if (Files.exists(dependency)) {
                        pending.add(dependency);
                    }
                }
            }
        }
        return List.copyOf(result);
    }

    static List<Path> nativeModulePaths(List<Path> candidates) throws IOException {
        Map<String, Map<URI, ModuleReference>> modules = new HashMap<>();
        for (Path candidate : candidates) {
            for (ModuleReference reference : ModuleFinder.of(candidate).findAll()) {
                if (!reference.descriptor().isAutomatic()) {
                    modules.computeIfAbsent(reference.descriptor().name(), ignored -> new LinkedHashMap<>())
                            .put(reference.location().orElseThrow(), reference);
                }
            }
        }
        ModuleFinder system = ModuleFinder.ofSystem();
        Set<String> visited = new LinkedHashSet<>();
        List<Path> result = new ArrayList<>();
        ArrayDeque<String> pending = new ArrayDeque<>(List.of(NATIVE_MODULE));
        while (!pending.isEmpty()) {
            String name = pending.removeFirst();
            if (!visited.add(name) || system.find(name).isPresent()) {
                continue;
            }
            Map<URI, ModuleReference> matches = modules.getOrDefault(name, Map.of());
            if (matches.size() != 1) {
                throw new IOException("Native Host 模块 " + name + (matches.isEmpty() ? " 缺失" : " 存在多个版本")
                        + "；请编译 javaclaw-packaging 的全部 Maven 依赖或重新构建发行物");
            }
            ModuleReference module = matches.values().iterator().next();
            result.add(Path.of(module.location().orElseThrow()).toRealPath());
            // 只加入实际 requires 闭包，不能把 Spring、JavaFX 等完整运行依赖当作 Native Host module-path。
            module.descriptor().requires().stream()
                    .filter(required -> !required.modifiers().contains(ModuleDescriptor.Requires.Modifier.STATIC))
                    .map(ModuleDescriptor.Requires::name)
                    .sorted()
                    .forEach(pending::add);
        }
        return List.copyOf(result);
    }

    private static Path packagedLibrary(Path location) throws IOException {
        Path actual = location.toRealPath();
        Path parent = actual.getParent();
        return Files.isRegularFile(actual)
                        && parent != null
                        && Files.isRegularFile(parent.resolve("com.javaclaw.javaclaw-app-server.jar"))
                ? parent
                : null;
    }

    private static List<Path> splitModulePath(String value) throws IOException {
        List<Path> result = new ArrayList<>();
        for (String entry : value.split(Pattern.quote(File.pathSeparator), -1)) {
            if (entry.isBlank()) {
                throw new IOException("JAVACLAW_SANDBOX_MODULE_PATH 不允许空路径项");
            }
            result.add(Path.of(entry).toRealPath());
        }
        return List.copyOf(result);
    }

    private static void requireResource(List<Path> entries, String resource, String component) throws IOException {
        for (Path entry : entries) {
            if (Files.isDirectory(entry) && Files.isRegularFile(entry.resolve(resource))) {
                return;
            }
            if (isJar(entry)) {
                try (JarFile jar = openJar(entry)) {
                    if (jar.getJarEntry(resource) != null) {
                        return;
                    }
                }
            }
        }
        throw new IOException("运行 classpath 缺少 " + component + "；IDE 请使用 javaclaw-packaging 模块并重新加载 Maven");
    }

    private static boolean isJar(Path path) {
        return Files.isRegularFile(path)
                && path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar");
    }

    private static JarFile openJar(Path path) throws IOException {
        return new JarFile(path.toFile(), false, ZipFile.OPEN_READ, Runtime.version());
    }

    private static boolean configured(Map<String, String> environment, String name) {
        return !environment.getOrDefault(name, "").isBlank();
    }

    private static Path defaultProgramDirectory(Path library) {
        if (library != null) {
            Path name = library.getFileName();
            Path parent = library.getParent();
            return name != null && parent != null && "lib".equals(name.toString())
                    ? parent.toAbsolutePath().normalize()
                    : library.toAbsolutePath().normalize();
        }
        return Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
    }

    private static Path configuredPath(String value) {
        return value == null || value.isBlank()
                ? null
                : Path.of(value).toAbsolutePath().normalize();
    }

    private static String joinPaths(List<Path> paths) {
        return paths.stream().map(Path::toString).collect(Collectors.joining(File.pathSeparator));
    }
}
