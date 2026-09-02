package com.javaclaw.launcher;

import java.util.ArrayList;
import java.util.List;

/** 从完整发行布局启动 App Server；只把校验通过的独立 Worker image 交给服务端。 */
public final class AppServerLauncher {
    private static final List<String> PROPAGATED_PROPERTIES =
            List.of("javaclaw.data.root", "javaclaw.log.dir", "javaclaw.log.process");

    private AppServerLauncher() {}

    /**
     * 启动并等待 App Server；不使用宿主 Java、PATH 或开发 classpath 回退。
     *
     * @param arguments App Server 的 stdio、UDS 或 Named Pipe 参数
     * @throws Exception 发行布局、进程启动或服务退出失败
     */
    public static void main(String[] arguments) throws Exception {
        RuntimeLayout layout = RuntimeLayout.fromSystemProperties();
        Process server =
                new ProcessBuilder(command(layout, arguments)).inheritIO().start();
        int exitCode = server.waitFor();
        if (exitCode != 0) {
            throw new IllegalStateException("JavaClaw App Server exited with " + exitCode);
        }
    }

    static List<String> command(RuntimeLayout layout, String[] arguments) {
        ArrayList<String> command = new ArrayList<>();
        command.add(layout.javaExecutable().toString());
        command.add("--enable-native-access=ALL-UNNAMED");
        addPropagatedProperties(command);
        command.addAll(layout.appServerProperties());
        command.add("-cp");
        command.add(layout.classpath());
        command.add("com.javaclaw.server.AppServerMain");
        command.addAll(List.of(arguments));
        return List.copyOf(command);
    }

    private static void addPropagatedProperties(List<String> command) {
        for (String name : PROPAGATED_PROPERTIES) {
            String value = System.getProperty(name, "").strip();
            if (!value.isEmpty()) {
                command.add("-D" + name + "=" + value);
            }
        }
    }
}
