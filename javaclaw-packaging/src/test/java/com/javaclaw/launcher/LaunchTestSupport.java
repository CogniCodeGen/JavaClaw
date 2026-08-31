package com.javaclaw.launcher;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** 将 verify 阶段已经打成 JAR 的 Reactor 依赖还原为编译目录，保证测试真实覆盖无需打包的 IDE 布局。 */
final class LaunchTestSupport {
    private LaunchTestSupport() {}

    static String ideClasspath() throws IOException {
        List<String> entries = new ArrayList<>();
        for (Path entry : RuntimeLayout.classPathEntries(System.getProperty("java.class.path"))) {
            Path parent = entry.getParent();
            if (Files.isRegularFile(entry)
                    && entry.getFileName().toString().startsWith("javaclaw-")
                    && parent != null
                    && parent.getFileName().toString().equals("target")
                    && Files.isDirectory(parent.resolve("classes"))) {
                entries.add(parent.resolve("classes").toString());
            } else {
                entries.add(entry.toString());
            }
        }
        return String.join(File.pathSeparator, entries);
    }
}
