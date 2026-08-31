package com.javaclaw.launcher;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VisualRegressionGateTest {
    @TempDir
    Path temporary;

    @Test
    void acceptsIdenticalImagesAndRejectsOneContiguousRegression() throws Exception {
        Path baseline = temporary.resolve("baseline");
        java.nio.file.Files.createDirectories(baseline);
        Path current = temporary.resolve("ui.png");
        write(baseline.resolve("main.png"), false);
        write(current, false);
        String previous = System.getProperty("javaclaw.visual.baseline");
        System.setProperty("javaclaw.visual.baseline", baseline.toString());
        try {
            assertDoesNotThrow(() -> VisualRegressionGate.verify(current, false));
            write(current, true);
            assertThrows(IllegalStateException.class, () -> VisualRegressionGate.verify(current, false));
            java.nio.file.Files.writeString(baseline.resolve("main.png.mask"), "0,0,20,20\n");
            assertDoesNotThrow(() -> VisualRegressionGate.verify(current, false));
        } finally {
            restore("javaclaw.visual.baseline", previous);
        }
    }

    private static void write(Path path, boolean changed) throws Exception {
        var image = new BufferedImage(100, 100, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 100; y++) {
            for (int x = 0; x < 100; x++) {
                image.setRGB(x, y, changed && x < 20 && y < 20 ? 0xff101010 : 0xfff0f0f0);
            }
        }
        ImageIO.write(image, "png", path.toFile());
    }

    private static void restore(String name, String value) {
        if (value == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, value);
        }
    }
}
