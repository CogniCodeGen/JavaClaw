package com.javaclaw.browser;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.CookieManager;
import java.net.CookieStore;
import java.net.HttpCookie;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * 持久化 Cookie 存储
 *
 * <p>将 {@link java.net.CookieManager} 中的 Cookie 持久化到 JSON 文件，
 * 实现浏览器会话跨重启保持。每个工作区拥有独立的 Cookie 文件。</p>
 *
 * <p>工作原理：在全局安装自定义 {@link CookieManager}，JavaFX WebView 会使用该管理器。
 * 定期或在关键时刻（切换工作区、关闭应用）调用 {@link #save()} 持久化到磁盘。</p>
 *
 * @author JavaClaw
 */
public class PersistentCookieStore {

    private static final Logger log = LoggerFactory.getLogger(PersistentCookieStore.class);

    private static final String COOKIES_FILE = "cookies.json";
    private static final ObjectMapper objectMapper =
            new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private final CookieManager cookieManager;
    private Path cookieFile;

    public PersistentCookieStore() {
        this.cookieManager = new CookieManager();
    }

    /**
     * 初始化并安装为全局 CookieHandler
     *
     * <p>必须在创建 WebView 之前调用。</p>
     */
    public void install(Path browserDir) {
        this.cookieFile = browserDir.resolve(COOKIES_FILE);
        loadCookies();
        java.net.CookieHandler.setDefault(cookieManager);
        log.info("持久化 Cookie 管理器已安装，Cookie 文件: {}", cookieFile);
    }

    /**
     * 切换到新的工作区浏览器目录
     *
     * <p>保存当前 Cookie，清空内存，从新目录加载。</p>
     */
    public void switchTo(Path browserDir) {
        save();
        clearMemory();
        this.cookieFile = browserDir.resolve(COOKIES_FILE);
        loadCookies();
        log.info("Cookie 存储已切换到: {}", cookieFile);
    }

    /**
     * 保存当前内存中的 Cookie 到磁盘
     */
    public void save() {
        if (cookieFile == null) return;

        CookieStore store = cookieManager.getCookieStore();
        List<HttpCookie> cookies = store.getCookies();
        if (cookies.isEmpty()) {
            log.debug("无 Cookie 需要保存");
            return;
        }

        List<Map<String, String>> records = new ArrayList<>();
        for (HttpCookie cookie : cookies) {
            if (cookie.hasExpired()) continue;

            Map<String, String> record = new LinkedHashMap<>();
            record.put("name", cookie.getName());
            record.put("value", cookie.getValue());
            record.put("domain", cookie.getDomain());
            record.put("path", cookie.getPath());
            record.put("maxAge", String.valueOf(cookie.getMaxAge()));
            record.put("secure", String.valueOf(cookie.getSecure()));
            record.put("httpOnly", String.valueOf(cookie.isHttpOnly()));
            records.add(record);
        }

        try {
            Files.createDirectories(cookieFile.getParent());
            objectMapper.writeValue(cookieFile.toFile(), records);
            log.info("已保存 {} 个 Cookie 到: {}", records.size(), cookieFile);
        } catch (IOException e) {
            log.error("保存 Cookie 失败", e);
        }
    }

    /**
     * 从磁盘加载 Cookie 到内存
     */
    private void loadCookies() {
        if (cookieFile == null || !Files.exists(cookieFile)) return;

        try {
            List<Map<String, String>> records = objectMapper.readValue(
                    cookieFile.toFile(), new TypeReference<>() {});

            CookieStore store = cookieManager.getCookieStore();
            int loaded = 0;
            for (Map<String, String> record : records) {
                try {
                    HttpCookie cookie = new HttpCookie(record.get("name"), record.get("value"));
                    cookie.setDomain(record.get("domain"));
                    cookie.setPath(record.get("path") != null ? record.get("path") : "/");

                    String maxAge = record.get("maxAge");
                    if (maxAge != null) {
                        cookie.setMaxAge(Long.parseLong(maxAge));
                    }
                    cookie.setSecure(Boolean.parseBoolean(record.get("secure")));
                    cookie.setHttpOnly(Boolean.parseBoolean(record.get("httpOnly")));

                    if (!cookie.hasExpired()) {
                        // 使用域名构建 URI
                        String domain = cookie.getDomain();
                        if (domain != null && domain.startsWith(".")) {
                            domain = domain.substring(1);
                        }
                        String scheme = cookie.getSecure() ? "https" : "http";
                        URI uri = URI.create(scheme + "://" + domain + cookie.getPath());
                        store.add(uri, cookie);
                        loaded++;
                    }
                } catch (Exception e) {
                    log.warn("加载单个 Cookie 失败: {}", record.get("name"), e);
                }
            }
            log.info("已从磁盘加载 {} 个 Cookie", loaded);
        } catch (IOException e) {
            log.warn("加载 Cookie 文件失败: {}", e.getMessage());
        }
    }

    /**
     * 清空内存中的 Cookie
     */
    private void clearMemory() {
        cookieManager.getCookieStore().removeAll();
    }

    public CookieManager getCookieManager() {
        return cookieManager;
    }
}
