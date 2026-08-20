package com.javaclaw.architecture;

import com.javaclaw.ui.javafx.theme.ThemeManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServicePluginUiStyleContractTest {
    private static final Path PROJECT = Path.of(System.getProperty("user.dir"));
    private static final Path MAIN = PROJECT.resolve("src/main");
    private static final Pattern FXML_STYLE = Pattern.compile("styleClass=\"([^\"]+)\"");
    private static final Pattern JAVA_STYLE_CALL = Pattern.compile(
            "getStyleClass\\(\\)\\.(?:add|addAll)\\((.*?)\\);", Pattern.DOTALL);
    private static final Pattern STRING_LITERAL = Pattern.compile("\"([^\"]+)\"");
    private static final Pattern CSS_RULE = Pattern.compile("(?s)([^{}]+)\\{([^{}]*)}");
    private static final Pattern COLOR_LITERAL = Pattern.compile(
            "(?i)(#[0-9a-f]{3,8}\\b|rgba?\\s*\\()");

    @Test
    void declarativeUiUsesOnlyHostStandardStyleClasses() throws IOException {
        List<Path> fxmlFiles = List.of(
                resource("fxml/plugin/service-plugin-configuration.fxml"),
                resource("fxml/settings/local-inference-profiles.fxml"),
                resource("fxml/settings/local-inference-assets.fxml"),
                resource("fxml/settings/local-inference-api.fxml"));
        for (Path file : fxmlFiles) {
            var styles = FXML_STYLE.matcher(Files.readString(file));
            while (styles.find()) {
                for (String value : styles.group(1).split("\\s*,\\s*")) {
                    assertStandard(value, file);
                }
            }
        }

        List<Path> javaFiles = List.of(
                javaFile("plugin/ServicePluginConfigurationController.java"),
                javaFile("plugin/ServicePluginConfigurationPane.java"),
                javaFile("plugin/ServicePluginDeclarativePageRenderer.java"),
                javaFile("plugin/ServicePluginEndpointConfigurationPane.java"),
                javaFile("plugin/ServicePluginPanel.java"),
                javaFile("plugin/ServicePluginSchemaConfigurationPane.java"),
                javaFile("settings/InferencePluginConfigurationFactory.java"));
        for (Path file : javaFiles) {
            var calls = JAVA_STYLE_CALL.matcher(Files.readString(file));
            while (calls.find()) {
                var values = STRING_LITERAL.matcher(calls.group(1));
                while (values.find()) assertStandard(values.group(1), file);
            }
        }
    }

    @Test
    void servicePluginCssUsesLookedUpTokensAndGlobalFonts() throws IOException {
        String css = Files.readString(resource("css/plugins.css"));
        var rules = CSS_RULE.matcher(css);
        int checked = 0;
        while (rules.find()) {
            if (!rules.group(1).contains(".service-plugin-")) continue;
            checked++;
            String body = rules.group(2);
            assertFalse(COLOR_LITERAL.matcher(body).find(),
                    () -> "服务插件样式包含硬编码颜色: " + rules.group(1).strip());
            assertFalse(body.contains("-fx-font-family"),
                    () -> "服务插件样式覆盖字体: " + rules.group(1).strip());
            assertFalse(body.toLowerCase().contains("url("),
                    () -> "服务插件样式加载外部资源: " + rules.group(1).strip());
        }
        assertTrue(checked >= 10, "应检查所有服务插件组件样式");

        String fonts = Files.readString(javaFile("theme/FontManager.java"));
        assertTrue(fonts.contains(".service-plugin-log"));
        assertTrue(fonts.contains(".service-plugin-code"));
        assertTrue(fonts.contains(".service-plugin-secret"));
    }

    @ParameterizedTest
    @MethodSource("themeIds")
    void everySystemThemeCanSupplyPluginTokens(String themeId) throws IOException {
        String css = Files.readString(resource("css/chat.css"));
        if (ThemeManager.DEFAULT_THEME.equals(themeId)) {
            assertTrue(css.contains("-jc-surface-page:"));
            assertTrue(css.contains("-jc-text-title:"));
            assertTrue(css.contains("-jc-border:"));
            return;
        }
        String marker = ".theme-" + themeId + " {";
        int start = css.indexOf(marker);
        assertTrue(start >= 0, "缺少主题覆盖: " + themeId);
        int end = css.indexOf('}', start);
        String block = css.substring(start, end);
        assertTrue(block.contains("-jc-surface-page:"));
        assertTrue(block.contains("-jc-text-title:"));
        assertTrue(block.contains("-jc-border:"));
        assertTrue(block.contains("-jc-primary-50:"));
    }

    static Stream<String> themeIds() {
        return ThemeManager.THEMES.stream().map(ThemeManager.Theme::id);
    }

    private static void assertStandard(String value, Path file) {
        assertTrue(value.startsWith("jc-") || value.startsWith("settings-")
                        || value.startsWith("service-plugin-"),
                () -> "插件配置 UI 使用非标准样式类 " + value + " in " + file);
    }

    private static Path resource(String relative) {
        return MAIN.resolve("resources").resolve(relative);
    }

    private static Path javaFile(String relative) {
        return MAIN.resolve("java/com/javaclaw/ui/javafx").resolve(relative);
    }
}
