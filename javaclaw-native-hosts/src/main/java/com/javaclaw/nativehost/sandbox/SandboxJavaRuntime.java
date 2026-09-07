package com.javaclaw.nativehost.sandbox;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

import com.javaclaw.api.SandboxCommand;

/**
 * 固定平台 Worker 的 Java 运行时；从已加载的发行代码来源解析绝对 classpath，不读取用户 PATH。
 *
 * @param executable 当前发行 Java 的规范可执行路径
 * @param classPath 绝对 classpath，适用于改变工作目录后的独立 Worker
 * @param readRoots 运行库及代码所在的只读目录
 */
public record SandboxJavaRuntime(Path executable, String classPath, List<Path> readRoots) {
    /** 固定只读目录列表。 */
    public SandboxJavaRuntime {
        readRoots = List.copyOf(readRoots);
    }

    /**
     * 解析当前发行运行库，开发 classpath 和已加载代码来源均转换为真实绝对路径。
     *
     * @return 可在 Sandbox 内启动固定 Worker 的运行时
     * @throws IOException 发行运行库或代码来源不可访问
     */
    public static SandboxJavaRuntime current() throws IOException {
        Path javaHome = Path.of(System.getProperty("java.home")).toRealPath();
        String executableName = System.getProperty("os.name", "")
                        .toLowerCase(java.util.Locale.ROOT)
                        .contains("windows")
                ? "java.exe"
                : "java";
        LinkedHashSet<Path> entries = new LinkedHashSet<>();
        for (String entry : System.getProperty("java.class.path", "").split(File.pathSeparator)) {
            if (!entry.isBlank()) {
                Path path = Path.of(entry);
                if (Files.exists(path)) {
                    entries.add(path.toRealPath());
                }
            }
        }
        addCodeSource(entries, SandboxJavaRuntime.class);
        addCodeSource(entries, SandboxCommand.class);
        if (entries.isEmpty()) {
            throw new IOException("packaged Sandbox Worker classpath is unavailable");
        }
        LinkedHashSet<Path> roots = new LinkedHashSet<>(List.of(javaHome));
        entries.forEach(path -> roots.add(Files.isDirectory(path) ? path : path.getParent()));
        String classPath = String.join(
                File.pathSeparator, entries.stream().map(Path::toString).toList());
        return new SandboxJavaRuntime(
                javaHome.resolve("bin").resolve(executableName).toRealPath(), classPath, List.copyOf(roots));
    }

    /**
     * 为仅依赖 Native Hosts 和 API 的固定 Worker 构造最小 classpath。
     *
     * <p>不继承 App Server 的上百个第三方 JAR，避免在受限打开文件数内加载入口前耗尽描述符。 测试入口的 CodeSource 可是测试 classes 目录，生产入口只能由平台代码提供。
     *
     * @param main 已加载的固定 Worker 类
     * @return 只含入口、Native Hosts 与 API 代码来源的运行时
     * @throws IOException 运行库或代码来源不可访问
     */
    public static SandboxJavaRuntime forWorker(Class<?> main) throws IOException {
        SandboxJavaRuntime current = current();
        LinkedHashSet<Path> entries = new LinkedHashSet<>();
        addCodeSource(entries, main);
        addCodeSource(entries, SandboxJavaRuntime.class);
        addCodeSource(entries, SandboxCommand.class);
        if (entries.isEmpty()) {
            throw new IOException("固定 Worker 没有可加载的代码来源");
        }
        LinkedHashSet<Path> roots = new LinkedHashSet<>();
        roots.add(Path.of(System.getProperty("java.home")).toRealPath());
        entries.forEach(path -> roots.add(Files.isDirectory(path) ? path : path.getParent()));
        return new SandboxJavaRuntime(
                current.executable(),
                String.join(
                        File.pathSeparator, entries.stream().map(Path::toString).toList()),
                List.copyOf(roots));
    }

    /**
     * 构造固定 Worker 的有界 JVM 参数；不使用启动时环境注入。
     *
     * @param main 平台代码中的固定入口，不从工具请求取得
     * @param arguments 已编码的固定入口参数
     * @param heapMiB JVM 堆上限 MiB，16 至 256，仍受整个进程树的资源上限约束
     * @return 不经 shell 的完整 argv
     */
    public List<String> command(Class<?> main, List<String> arguments, int heapMiB) {
        if (heapMiB < 16 || heapMiB > 256) {
            throw new IllegalArgumentException("Worker heap must be between 16 and 256 MiB");
        }
        ArrayList<String> argv = new ArrayList<>(List.of(
                executable.toString(),
                "--enable-native-access=ALL-UNNAMED",
                "-XX:-UsePerfData",
                "-XX:+DisableAttachMechanism",
                "-XX:+UseSerialGC",
                "-XX:ActiveProcessorCount=2",
                "-Djava.net.preferIPv4Stack=true",
                "-Xms16m",
                "-Xmx" + heapMiB + "m",
                "-XX:MaxMetaspaceSize=64m",
                "-XX:CompressedClassSpaceSize=32m",
                "-XX:ReservedCodeCacheSize=32m",
                "-cp",
                classPath,
                main.getName()));
        argv.addAll(arguments);
        return List.copyOf(argv);
    }

    private static void addCodeSource(LinkedHashSet<Path> entries, Class<?> type) throws IOException {
        var source = type.getProtectionDomain().getCodeSource();
        if (source == null) {
            return;
        }
        try {
            entries.add(Path.of(source.getLocation().toURI()).toRealPath());
        } catch (java.net.URISyntaxException invalid) {
            throw new IOException("invalid packaged Worker code source", invalid);
        }
    }
}
