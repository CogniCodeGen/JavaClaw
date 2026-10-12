package com.javaclaw.plugins.echo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

/** 格式校验属于插件，宿主无需理解文件名、架构或权重格式。 */
class EchoModelFormatTest {
    @TempDir Path temp;

    @Test void readsItsOwnModelFormatWithoutEngineConfigurationFiles() throws Exception {
        var model = EchoModelFormat.inspect(Path.of("fixtures/minimal").toAbsolutePath().toString());
        assertEquals("回声：", model.prefix());
        assertEquals(4096, model.contextLength());
        assertEquals(8, model.dimensions());
    }

    @Test void rejectsUnknownVersionsAndUnboundedDimensions() throws Exception {
        Path manifest = temp.resolve(EchoModelFormat.FILE_NAME);
        Files.writeString(manifest, "{\"format\":\"unknown\"}");
        assertThrows(IllegalArgumentException.class, () -> EchoModelFormat.inspect(temp.toString()));
        Files.writeString(manifest, "{\"format\":\"javaclaw.echo.v1\",\"embeddingDimensions\":100000}");
        assertThrows(IllegalArgumentException.class, () -> EchoModelFormat.inspect(temp.toString()));
    }
}
