package com.javaclaw.launcher;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.List;
import javax.imageio.ImageIO;

/** macOS 固定环境的像素回归门禁；其他平台只生成截图并执行结构契约。 */
final class VisualRegressionGate {
    private static final int CHANNEL_TOLERANCE = 16;
    private static final int SMOOTHING_RADIUS = 1;
    private static final double TOTAL_AREA_LIMIT = 0.005;
    private static final double REGION_AREA_LIMIT = 0.001;

    private VisualRegressionGate() {}

    static void verify(Path screenshot, boolean update) throws IOException {
        if (update && System.getenv("CI") != null) {
            throw new IllegalStateException("CI 不允许更新视觉基准");
        }
        Path baseline = baselineRoot(update);
        List<Path> current = screenshots(screenshot);
        if (current.isEmpty()) {
            throw new IllegalStateException("没有生成可用于视觉回归的截图：" + screenshot);
        }
        if (update) {
            Files.createDirectories(baseline);
            for (Path image : current) {
                Files.copy(
                        image, baseline.resolve(baselineName(screenshot, image)), StandardCopyOption.REPLACE_EXISTING);
            }
            return;
        }
        for (Path image : current) {
            Path expected = baseline.resolve(baselineName(screenshot, image));
            if (!Files.isRegularFile(expected)) {
                throw new IllegalStateException("缺少 macOS 视觉基准：" + expected);
            }
            compare(expected, image);
        }
    }

    static Path baselineRoot(boolean update) {
        String override = System.getProperty("javaclaw.visual.baseline");
        if (override != null && !override.isBlank()) {
            return Path.of(override);
        }
        if (update && !Boolean.getBoolean("javaclaw.visual.promote")) {
            return modulePath("target/visual/baseline-candidate/macos");
        }
        return modulePath("src/test/resources/visual/macos");
    }

    private static Path modulePath(String relative) {
        Path moduleSource = Path.of("src/test/java/com/javaclaw/launcher");
        if (Files.isDirectory(moduleSource)) {
            return Path.of(relative);
        }
        return Path.of("javaclaw-packaging").resolve(relative);
    }

    private static List<Path> screenshots(Path screenshot) throws IOException {
        Path directory = screenshot.toAbsolutePath().getParent();
        if (directory == null || !Files.isDirectory(directory)) {
            return List.of();
        }
        String filename = screenshot.getFileName().toString();
        String stem = filename.endsWith(".png") ? filename.substring(0, filename.length() - 4) : filename;
        try (var files = Files.list(directory)) {
            return files.filter(Files::isRegularFile)
                    .filter(value -> {
                        String name = value.getFileName().toString();
                        return !name.equals(stem + "-diff.png")
                                && (name.equals(stem + ".png") || name.startsWith(stem + "-") && name.endsWith(".png"));
                    })
                    .sorted(Comparator.comparing(value -> value.getFileName().toString()))
                    .toList();
        }
    }

    private static String baselineName(Path rootScreenshot, Path image) {
        String root = rootScreenshot.getFileName().toString().replaceFirst("\\.png$", "");
        String name = image.getFileName().toString();
        if (name.equals(root + ".png")) {
            return "main.png";
        }
        return name.substring(root.length() + 1);
    }

    private static void compare(Path baseline, Path current) throws IOException {
        BufferedImage expectedImage = read(baseline);
        BufferedImage actualImage = read(current);
        if (expectedImage.getWidth() != actualImage.getWidth()
                || expectedImage.getHeight() != actualImage.getHeight()) {
            throw new IllegalStateException("视觉截图尺寸变化："
                    + baseline.getFileName()
                    + " expected="
                    + expectedImage.getWidth()
                    + "×"
                    + expectedImage.getHeight()
                    + " actual="
                    + actualImage.getWidth()
                    + "×"
                    + actualImage.getHeight());
        }
        int width = expectedImage.getWidth();
        int height = expectedImage.getHeight();
        int[] expected = smooth(expectedImage);
        int[] actual = smooth(actualImage);
        boolean[] masked = masks(baseline, width, height);
        boolean[] changed = new boolean[width * height];
        int changedPixels = 0;
        int comparablePixels = 0;
        for (int index = 0; index < changed.length; index++) {
            if (masked[index]) {
                continue;
            }
            comparablePixels++;
            if (different(expected[index], actual[index])) {
                changed[index] = true;
                changedPixels++;
            }
        }
        int largestRegion = largestRegion(changed, width, height);
        double totalRatio = comparablePixels == 0 ? 0 : (double) changedPixels / comparablePixels;
        double regionRatio = comparablePixels == 0 ? 0 : (double) largestRegion / comparablePixels;
        if (totalRatio > TOTAL_AREA_LIMIT || regionRatio > REGION_AREA_LIMIT) {
            Path difference =
                    current.resolveSibling(current.getFileName().toString().replaceFirst("\\.png$", "-diff.png"));
            writeDifference(actualImage, changed, difference);
            throw new IllegalStateException("视觉回归超过阈值："
                    + current.getFileName()
                    + " total="
                    + percent(totalRatio)
                    + " region="
                    + percent(regionRatio)
                    + " diff="
                    + difference);
        }
    }

    private static BufferedImage read(Path path) throws IOException {
        BufferedImage value = ImageIO.read(path.toFile());
        if (value == null) {
            throw new IOException("无法读取 PNG：" + path);
        }
        return value;
    }

    private static int[] smooth(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        int[] source = image.getRGB(0, 0, width, height, null, 0, width);
        int[] result = new int[source.length];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int alpha = 0;
                int red = 0;
                int green = 0;
                int blue = 0;
                int count = 0;
                for (int dy = -1; dy <= 1; dy++) {
                    int py = y + dy;
                    if (py < 0 || py >= height) {
                        continue;
                    }
                    for (int dx = -1; dx <= 1; dx++) {
                        int px = x + dx;
                        if (px < 0 || px >= width) {
                            continue;
                        }
                        int pixel = source[py * width + px];
                        alpha += pixel >>> 24 & 0xff;
                        red += pixel >>> 16 & 0xff;
                        green += pixel >>> 8 & 0xff;
                        blue += pixel & 0xff;
                        count++;
                    }
                }
                result[y * width + x] = alpha / count << 24 | red / count << 16 | green / count << 8 | blue / count;
            }
        }
        return result;
    }

    private static boolean different(int expected, int actual) {
        return channel(expected, actual, 24) > CHANNEL_TOLERANCE
                || channel(expected, actual, 16) > CHANNEL_TOLERANCE
                || channel(expected, actual, 8) > CHANNEL_TOLERANCE
                || channel(expected, actual, 0) > CHANNEL_TOLERANCE;
    }

    private static int channel(int left, int right, int shift) {
        return Math.abs((left >>> shift & 0xff) - (right >>> shift & 0xff));
    }

    private static boolean[] masks(Path baseline, int width, int height) throws IOException {
        boolean[] result = new boolean[width * height];
        Path mask = baseline.resolveSibling(baseline.getFileName() + ".mask");
        if (!Files.isRegularFile(mask)) {
            return result;
        }
        for (String line : Files.readAllLines(mask)) {
            String value = line.strip();
            if (value.isEmpty() || value.startsWith("#")) {
                continue;
            }
            String[] fields = value.split(",");
            if (fields.length != 4) {
                throw new IllegalStateException("动态区域遮罩必须使用 x,y,width,height：" + mask);
            }
            int x = Integer.parseInt(fields[0].strip());
            int y = Integer.parseInt(fields[1].strip());
            int maskedWidth = Integer.parseInt(fields[2].strip());
            int maskedHeight = Integer.parseInt(fields[3].strip());
            for (int py = Math.max(0, y - SMOOTHING_RADIUS);
                    py < Math.min(height, y + maskedHeight + SMOOTHING_RADIUS);
                    py++) {
                for (int px = Math.max(0, x - SMOOTHING_RADIUS);
                        px < Math.min(width, x + maskedWidth + SMOOTHING_RADIUS);
                        px++) {
                    result[py * width + px] = true;
                }
            }
        }
        return result;
    }

    private static int largestRegion(boolean[] changed, int width, int height) {
        boolean[] visited = new boolean[changed.length];
        int largest = 0;
        var queue = new ArrayDeque<Integer>();
        for (int start = 0; start < changed.length; start++) {
            if (!changed[start] || visited[start]) {
                continue;
            }
            int size = 0;
            visited[start] = true;
            queue.add(start);
            while (!queue.isEmpty()) {
                int index = queue.removeFirst();
                size++;
                int x = index % width;
                int y = index / width;
                if (x > 0) {
                    add(index - 1, changed, visited, queue);
                }
                if (x + 1 < width) {
                    add(index + 1, changed, visited, queue);
                }
                if (y > 0) {
                    add(index - width, changed, visited, queue);
                }
                if (y + 1 < height) {
                    add(index + width, changed, visited, queue);
                }
            }
            largest = Math.max(largest, size);
        }
        return largest;
    }

    private static void add(int index, boolean[] changed, boolean[] visited, ArrayDeque<Integer> queue) {
        if (changed[index] && !visited[index]) {
            visited[index] = true;
            queue.add(index);
        }
    }

    private static void writeDifference(BufferedImage actual, boolean[] changed, Path path) throws IOException {
        int width = actual.getWidth();
        int height = actual.getHeight();
        var result = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        int[] pixels = actual.getRGB(0, 0, width, height, null, 0, width);
        for (int index = 0; index < pixels.length; index++) {
            if (changed[index]) {
                int pixel = pixels[index];
                int red = Math.min(255, (pixel >>> 16 & 0xff) / 2 + 128);
                int green = (pixel >>> 8 & 0xff) / 3;
                int blue = (pixel & 0xff) / 3;
                pixels[index] = 0xff000000 | red << 16 | green << 8 | blue;
            }
        }
        result.setRGB(0, 0, width, height, pixels, 0, width);
        ImageIO.write(result, "png", path.toFile());
    }

    private static String percent(double value) {
        return String.format(java.util.Locale.ROOT, "%.3f%%", value * 100);
    }
}
