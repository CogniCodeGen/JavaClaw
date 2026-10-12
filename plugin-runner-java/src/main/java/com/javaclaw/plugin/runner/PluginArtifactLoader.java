package com.javaclaw.plugin.runner;

import com.javaclaw.plugin.sdk.Plugin;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/** 每个工件使用独立子优先加载器，插件私有依赖不被 Runner 的依赖覆盖。 */
final class PluginArtifactLoader extends URLClassLoader {
    private static final List<String> SHARED = List.of("java.", "javax.", "jdk.", "sun.",
            "com.javaclaw.plugin.sdk.", "com.javaclaw.plugin.protocol.v2.");
    private PluginArtifactLoader(URL[] urls) { super(urls, Plugin.class.getClassLoader()); }
    static PluginArtifactLoader open(RunnerBootstrap bootstrap) throws Exception {
        Path artifact = bootstrap.artifact();
        if (!Files.isRegularFile(artifact, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(artifact)) {
            throw new IOException("插件入口工件必须是普通文件");
        }
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(artifact)) {
            byte[] buffer = new byte[64 * 1024];
            for (int size; (size = input.read(buffer)) != -1;) digest.update(buffer, 0, size);
        }
        if (!MessageDigest.isEqual(HexFormat.of().parseHex(bootstrap.artifactFileHash()), digest.digest())) {
            throw new IOException("插件入口工件哈希不匹配");
        }
        List<URL> urls = new ArrayList<>();
        urls.add(artifact.toUri().toURL());
        Path root = artifact.getParent().toRealPath();
        Path lib = root.resolve("lib");
        if (Files.exists(lib)) {
            if (Files.isSymbolicLink(lib) || !lib.toRealPath().startsWith(root)) {
                throw new IOException("插件依赖目录越界");
            }
            try (var paths = Files.list(lib)) {
                for (Path path : paths.sorted().toList()) {
                    if (!path.getFileName().toString().endsWith(".jar")) continue;
                    if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                            || !path.toRealPath().startsWith(root)) throw new IOException("插件依赖工件越界");
                    urls.add(path.toUri().toURL());
                }
            }
        }
        return new PluginArtifactLoader(urls.toArray(URL[]::new));
    }
    Plugin instantiate(String mainClass) throws Exception {
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        try {
            thread.setContextClassLoader(this);
            Class<?> type = Class.forName(mainClass, true, this);
            if (!Plugin.class.isAssignableFrom(type)) throw new IllegalArgumentException("插件入口没有实现 SDK Plugin");
            return (Plugin) type.getConstructor().newInstance();
        } finally { thread.setContextClassLoader(previous); }
    }
    @Override public URL getResource(String name) {
        URL local = findResource(name);
        return local != null ? local : super.getResource(name);
    }
    @Override public java.util.Enumeration<URL> getResources(String name) throws IOException {
        java.util.LinkedHashSet<URL> resources = new java.util.LinkedHashSet<>();
        resources.addAll(java.util.Collections.list(findResources(name)));
        resources.addAll(java.util.Collections.list(getParent().getResources(name)));
        return java.util.Collections.enumeration(resources);
    }
    @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> type = findLoadedClass(name);
            if (type == null && SHARED.stream().anyMatch(name::startsWith)) {
                type = super.loadClass(name, false);
            } else if (type == null) {
                try { type = findClass(name); }
                catch (ClassNotFoundException absent) { type = super.loadClass(name, false); }
            }
            if (resolve) resolveClass(type);
            return type;
        }
    }
}
