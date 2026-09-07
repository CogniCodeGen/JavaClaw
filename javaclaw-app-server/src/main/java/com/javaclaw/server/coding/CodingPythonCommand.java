package com.javaclaw.server.coding;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** 在沙箱内选择准备阶段创建的 venv；宿主不读取或启动缓存内可能被项目替换的解释器。 */
final class CodingPythonCommand {
    private static final String DISPATCH = """
            import os, sys
            directory = sys.argv[1]
            executable = os.path.join(directory, 'Scripts' if os.name == 'nt' else 'bin', 'python.exe' if os.name == 'nt' else 'python')
            selected = executable if os.path.isfile(executable) else sys.executable
            os.execv(selected, [selected] + sys.argv[2:])
            """;

    private CodingPythonCommand() {}

    static List<String> select(String name, List<String> original, Path cache) {
        if (!name.equals("python") && !name.equals("python3") && !name.equals("pip")) {
            return original;
        }
        var command = new ArrayList<>(List.of(
                original.getFirst(), "-c", DISPATCH, cache.resolve("venv").toString()));
        command.addAll(original.subList(1, original.size()));
        return command;
    }
}
