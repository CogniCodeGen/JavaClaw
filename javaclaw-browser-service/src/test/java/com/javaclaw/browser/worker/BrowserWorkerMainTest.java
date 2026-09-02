package com.javaclaw.browser.worker;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import com.javaclaw.protocol.LengthPrefixedFraming;
import com.javaclaw.protocol.ProtocolException;

import static org.junit.jupiter.api.Assertions.assertThrows;

class BrowserWorkerMainTest {
    private static final int MAXIMUM_FRAME_BYTES = 4 * 1024 * 1024;

    @Test
    void rejectsCommandLineArguments() {
        assertThrows(IllegalArgumentException.class, () -> BrowserWorkerMain.main(new String[] {"unexpected"}));
    }

    @Test
    void rejectsProtocolDowngradeBeforeLaunchingBrowser() throws Exception {
        String command = """
                {"version":1,"id":7,"operation":"snapshot","payload":{},"sensitiveStateBytes":0}
                """;
        ByteArrayOutputStream input = new ByteArrayOutputStream();
        LengthPrefixedFraming.write(input, command.strip().getBytes(StandardCharsets.UTF_8), MAXIMUM_FRAME_BYTES);
        InputStream originalInput = System.in;
        PrintStream originalOutput = System.out;
        try {
            System.setIn(new ByteArrayInputStream(input.toByteArray()));
            System.setOut(new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
            assertThrows(ProtocolException.class, () -> BrowserWorkerMain.main(new String[0]));
        } finally {
            System.setIn(originalInput);
            System.setOut(originalOutput);
        }
    }
}
