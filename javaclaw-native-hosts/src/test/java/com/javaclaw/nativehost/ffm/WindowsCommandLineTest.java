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
