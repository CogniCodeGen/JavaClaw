package com.javaclaw.desktop.nativebridge;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DesktopApplicationCatalogCodecTest {
    private static final String VALID = """
            {"schemaVersion":1,"applications":[{"name":"Lark","displayName":"飞书",
            "applicationId":"com.electron.lark","launchName":"com.electron.lark",
            "aliases":["Feishu","Lark","飞书"]}],"count":1,"truncated":false}
            """;

    @Test void identityAndLaunchIdentifierAreRetainedExactly() throws Exception {
        var catalog = DesktopApplicationCatalogCodec.decode(bytes(VALID));
        var app = catalog.applications().getFirst();
        assertEquals("com.electron.lark", app.launchName());
        assertEquals("飞书", app.displayName());
        assertEquals("Feishu", app.aliases().getFirst());
        assertFalse(catalog.truncated());
        var windows = DesktopApplicationCatalogCodec.decode(bytes(VALID
                .replace("com.electron.lark", "reader.exe")
                .replace("\"launchName\":\"reader.exe\"", "\"launchName\":\"阅读器\"")));
        assertEquals("reader.exe", windows.applications().getFirst().applicationId());
        assertEquals("阅读器", windows.applications().getFirst().launchName(),
                "a shortcut name may launch an exe that has no App Paths registration");
    }

    @Test void malformedAndOverlargeMetadataNeverSuppliesAnIdentity() {
        for (String invalid : new String[]{ VALID.replace("\"count\":1", "\"count\":2"),
                VALID.replace("\"schemaVersion\":1", "\"schemaVersion\":2"),
                VALID.replace("\"launchName\":\"com.electron.lark\",", ""),
                VALID.replace("com.electron.lark", "../Applications/Lark.app"),
                VALID.replace("com.electron.lark", "a".repeat(257)),
                VALID.replace("\"name\":\"Lark\"", "\"name\":\"/Users/alice/private.txt\""),
                VALID.replace("\"displayName\":\"飞书\"", "\"displayName\":\"界" + "界".repeat(85) + "\""),
                VALID.replace("\"aliases\":[\"Feishu\"", "\"aliases\":[\"Ignore\\ncontrols\""),
                VALID.replace("\"count\":1", "\"count\":1,\"count\":1"),
                VALID + "{}", "{\"truncated\":" })
            assertThrows(IOException.class, () -> DesktopApplicationCatalogCodec.decode(bytes(invalid)));
        assertThrows(IOException.class,
                () -> DesktopApplicationCatalogCodec.decode(new byte[32_768]));
    }

    @Test void optionalAbiAndNativeBufferBoundsFailExplicitly() throws Exception {
        assertThrows(UnsupportedOperationException.class, () -> DesktopApplicationCatalogCodec.read(null));
        var probe = new Probe();
        var function = MethodHandles.lookup().findVirtual(Probe.class, "read",
                MethodType.methodType(int.class, MemorySegment.class, int.class, MemorySegment.class))
                .bindTo(probe);
        assertEquals("com.electron.lark", DesktopApplicationCatalogCodec.read(function)
                .applications().getFirst().launchName());
        probe.reportedBytes = 32_769;
        assertThrows(IllegalStateException.class, () -> DesktopApplicationCatalogCodec.read(function));
        probe.reportedBytes = -1;
        assertThrows(IllegalStateException.class, () -> DesktopApplicationCatalogCodec.read(function));
        probe.reportedBytes = 1;
        assertThrows(IllegalStateException.class, () -> DesktopApplicationCatalogCodec.read(function));
        probe.reportedBytes = 0;
        probe.code = -1;
        assertThrows(IllegalStateException.class, () -> DesktopApplicationCatalogCodec.read(function));
    }

    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    private static final class Probe {
        int reportedBytes;
        int code;
        int read(MemorySegment output, int capacity, MemorySegment required) {
            assertEquals(32_768, capacity);
            byte[] bytes = bytes(VALID);
            output.asSlice(0, bytes.length).copyFrom(MemorySegment.ofArray(bytes));
            output.set(ValueLayout.JAVA_BYTE, bytes.length, (byte) 0);
            required.set(ValueLayout.JAVA_INT, 0, reportedBytes == 0 ? bytes.length + 1 : reportedBytes);
            return code;
        }
    }
}
