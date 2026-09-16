package com.javaclaw.nativehost.ffm;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WindowsCommandLineTest {
    @Test
    void quotingPreservesSpacesQuotesAndTrailingSlashesWithoutShell() {
        assertEquals("plain", WindowsCommandLine.quote("plain"));
        assertEquals("\"\"", WindowsCommandLine.quote(""));
        assertEquals("\"hello world\"", WindowsCommandLine.quote("hello world"));
        assertEquals("\"a\\\"b\"", WindowsCommandLine.quote("a\"b"));
        assertEquals("\"C:\\Program Files\\\\\"", WindowsCommandLine.quote("C:\\Program Files\\"));
        assertEquals(
                "tool \"hello world\" \"a\\\"b\"", WindowsCommandLine.encode(List.of("tool", "hello world", "a\"b")));
    }

    @Test
    void 固定Cmd正文保留内部引号反斜杠元字符与首尾空白() {
        Path shell = systemShell();
        String source = "  echo \"a b\" && echo C:\\folder\\ ^& \"\"\n echo 中文  ";
        List<String> arguments = List.of(shell.toString(), "/d", "/s", "/c", source);
        assertEquals(
                WindowsCommandLine.quote(shell.toString()) + " /d /s /c \"" + source + "\"",
                WindowsCommandLine.encodeTarget(arguments, shell));
    }

    @Test
    void 非固定Cmd入口或不同开关维持普通Argv编码() {
        Path shell = systemShell();
        List<String> differentOptions = List.of(shell.toString(), "/c", "echo \"a b\"");
        assertEquals(
                WindowsCommandLine.encode(differentOptions), WindowsCommandLine.encodeTarget(differentOptions, shell));
        Path registered = shell.getParent().resolve("registered.exe");
        List<String> differentEntry = List.of(registered.toString(), "/d", "/s", "/c", "echo \"a b\"");
        assertEquals(
                WindowsCommandLine.encode(differentEntry), WindowsCommandLine.encodeTarget(differentEntry, registered));
    }

    @Test
    void 长度按转义后的UTF16单元校验且超长参数不会被截断() {
        assertEquals(
                32_766, WindowsCommandLine.encode(List.of("x".repeat(32_766))).length());
        assertThrows(
                IllegalArgumentException.class,
                () -> WindowsCommandLine.requireTransportFits(List.of("x".repeat(32_767))));
        // 每个内部引号增加反斜杠，原始正文低于上限也不能绕过最终命令行限制。
        assertThrows(IllegalArgumentException.class, () -> WindowsCommandLine.encode(List.of("\"".repeat(16_383))));
        Path shell = systemShell();
        int prefix = WindowsCommandLine.quote(shell.toString()).length() + " /d /s /c \"\"".length();
        assertEquals(
                8191,
                WindowsCommandLine.encodeTarget(
                                List.of(shell.toString(), "/d", "/s", "/c", "x".repeat(8191 - prefix)), shell)
                        .length());
        assertThrows(
                IllegalArgumentException.class,
                () -> WindowsCommandLine.encodeTarget(
                        List.of(shell.toString(), "/d", "/s", "/c", "x".repeat(8192 - prefix)), shell));
    }

    private static Path systemShell() {
        return Path.of(System.getenv().getOrDefault("SystemRoot", "C:\\Windows"))
                .resolve("System32")
                .resolve("cmd.exe")
                .toAbsolutePath()
                .normalize();
    }

    @Test
    void sandboxContextRejectsAmbiguousOrUnencodableEnvironmentNames() {
        LinkedHashMap<String, String> duplicate = new LinkedHashMap<>();
        duplicate.put("PATH", "first");
        duplicate.put("Path", "second");

        assertThrows(IllegalArgumentException.class, () -> new WindowsSandboxContext(Path.of("."), duplicate));
        assertThrows(
                IllegalArgumentException.class,
                () -> new WindowsSandboxContext(Path.of("."), Map.of("BAD=NAME", "value")));
        assertThrows(
                IllegalArgumentException.class,
                () -> new WindowsSandboxContext(Path.of("."), Map.of("NAME", "bad\0value")));
    }
}
