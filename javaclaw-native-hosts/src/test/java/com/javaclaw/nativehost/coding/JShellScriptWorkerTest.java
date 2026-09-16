package com.javaclaw.nativehost.coding;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JShellScriptWorkerTest {
    @TempDir
    Path temporary;

    @Test
    void Worker独立满足Java21API且不会编译到主运行目录() throws Exception {
        Path source = source();
        Path classes = Files.createDirectory(temporary.resolve("java21-classes"));
        String name = System.getProperty("os.name").startsWith("Windows") ? "javac.exe" : "javac";
        Process compiler = new ProcessBuilder(
                        Path.of(System.getProperty("java.home"), "bin", name).toString(),
                        "--release",
                        "21",
                        "-d",
                        classes.toString(),
                        source.toString())
                .redirectErrorStream(true)
                .start();
        try {
            assertTrue(compiler.waitFor(30, TimeUnit.SECONDS));
            assertEquals(
                    0,
                    compiler.exitValue(),
                    new String(compiler.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        } finally {
            if (compiler.isAlive()) {
                compiler.destroyForcibly();
                compiler.waitFor(5, TimeUnit.SECONDS);
            }
        }
        assertTrue(Files.isRegularFile(classes.resolve("JShellScriptWorker.class")));
    }

    @Test
    void 多行定义表达式与UTF8输出在同次会话顺序执行() throws Exception {
        Result result = run("int twice(int n) {\n return n * 2;\n}\n" + "System.out.println(\"中文😀\");\ntwice(21)\n");
        assertEquals(0, result.exit());
        assertTrue(result.stdout().contains("中文😀"));
        assertTrue(result.stdout().contains("42"));
    }

    @Test
    void 编译错误与执行异常停止后续片段并返回非零状态() throws Exception {
        Result rejected = run("int wrong = \"text\";\nSystem.out.println(\"AFTER\");");
        assertEquals(2, rejected.exit());
        assertTrue(rejected.stderr().contains("编译失败"));
        assertFalse(rejected.stdout().contains("AFTER"));
        Result exception = run("throw new RuntimeException(\"failure\");\nSystem.out.println(\"AFTER\");");
        assertEquals(3, exception.exit());
        assertTrue(exception.stderr().contains("failure"));
        assertFalse(exception.stdout().contains("AFTER"));
    }

    @Test
    void 不完整尾部与最终未解析依赖失败且会话不会跨调用保留() throws Exception {
        assertEquals(2, run("if (true) {").exit());
        assertEquals(2, run("int later() { return missing(); }").exit());
        assertEquals(0, run("int previous = 42;").exit());
        assertEquals(2, run("previous + 1").exit());
    }

    @Test
    void 显式SystemExit只保留真实进程状态不伪造完成回执() throws Exception {
        Result result = run("System.exit(0);\nSystem.out.println(\"AFTER\");");
        assertEquals(0, result.exit());
        assertFalse(result.stdout().contains("AFTER"));
    }

    private Result run(String input) throws Exception {
        String name = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        Path java = Path.of(System.getProperty("java.home"), "bin", name);
        Process process = new ProcessBuilder(
                        List.of(java.toString(), "--add-modules", "jdk.jshell", "--source", "21", source().toString()))
                .directory(temporary.toFile())
                .start();
        try {
            try (var stdin = process.getOutputStream()) {
                stdin.write(input.getBytes(StandardCharsets.UTF_8));
            }
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "JShell Worker must terminate within its test deadline");
            return new Result(
                    process.exitValue(),
                    new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8),
                    new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8));
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
        }
    }

    private Path source() throws Exception {
        return JShellScriptSource.materialize(Files.createDirectories(temporary.resolve("data-v6")))
                .path();
    }

    private record Result(int exit, String stdout, String stderr) {}
}
