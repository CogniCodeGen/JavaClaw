package com.javaclaw.browser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Playwright;

/** 仅由显式浏览器发行准备命令使用的构建入口，从刚安装的 Chromium 内置页面导出第三方声明。 不接收 URL、用户数据或 Agent 请求，不是 Browser Service 协议方法；产品执行仍只经过沙箱。 */
public final class BrowserDistributionCredits {
    private BrowserDistributionCredits() {}

    /** 无参数构建命令；禁止下载，拒绝非预期安装路径，只把声明原子写入同一浏览器缓存。 */
    public static void main(String[] args) throws Exception {
        if (args.length != 0) {
            throw new IllegalArgumentException("browser credit export takes no arguments");
        }
        LinkedHashMap<String, String> driverEnvironment = new LinkedHashMap<>();
        driverEnvironment.put("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1");
        String browserPath = System.getenv("PLAYWRIGHT_BROWSERS_PATH");
        if (browserPath != null && !browserPath.isBlank()) {
            // Playwright driver 是子进程；显式转发程序本地缓存，不能让它回退到用户主目录。
            driverEnvironment.put("PLAYWRIGHT_BROWSERS_PATH", browserPath);
        }
        try (var playwright = Playwright.create(new Playwright.CreateOptions().setEnv(Map.copyOf(driverEnvironment)))) {
            Path executable = Path.of(playwright.chromium().executablePath()).toRealPath();
            Path component = executable.getParent();
            while (component != null && !component.getFileName().toString().matches("chromium-[0-9]+")) {
                component = component.getParent();
            }
            if (component == null
                    || !Files.isRegularFile(component.resolve("INSTALLATION_COMPLETE"), LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException("Chromium installation is incomplete");
            }
            try (Browser browser = playwright
                    .chromium()
                    .launch(new BrowserType.LaunchOptions()
                            .setHeadless(true)
                            .setExecutablePath(executable)
                            .setTimeout(Duration.ofSeconds(30).toMillis()))) {
                var context = browser.newContext();
                context.route("**/*", route -> route.abort());
                var page = context.newPage();
                page.navigate(
                        "chrome://credits", new com.microsoft.playwright.Page.NavigateOptions().setTimeout(30_000));
                String credits = page.content();
                byte[] bytes = credits.getBytes(StandardCharsets.UTF_8);
                if (!credits.contains("Chromium")
                        || !credits.contains("license")
                        || bytes.length < 100
                        || bytes.length > 16 * 1024 * 1024) {
                    throw new IllegalStateException("Chromium did not expose complete bounded license notices");
                }
                Path target = component.getParent().resolve("javaclaw-" + component.getFileName() + "-credits.html");
                if (Files.isSymbolicLink(target)) {
                    throw new IllegalStateException("browser credit target is a symbolic link");
                }
                Path staging = Files.createTempFile(component.getParent(), ".javaclaw-credits-", ".tmp");
                try {
                    Files.write(staging, bytes);
                    Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } finally {
                    Files.deleteIfExists(staging);
                }
                System.out.println("Browser notices prepared: " + component.getFileName() + " · " + browser.version());
            }
        }
    }
}
