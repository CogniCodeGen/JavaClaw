package com.javaclaw.browser;

import com.fasterxml.jackson.databind.JsonNode;
import com.javaclaw.platform.json.JsonCodec;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.options.Cookie;
import com.microsoft.playwright.options.SameSiteAttribute;
import com.microsoft.playwright.options.WaitUntilState;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Restores a persisted Playwright storage state into an existing browser context. */
final class SiteSessionRestorer {

    private static final Logger log = LoggerFactory.getLogger(SiteSessionRestorer.class);

    private final JsonCodec json;

    SiteSessionRestorer(JsonCodec json) {
        this.json = Objects.requireNonNull(json, "json");
    }

    /**
     * Restores cookies directly and hydrates origin-scoped local storage through a temporary page.
     * A partial local-storage navigation still counts as restored because the init script runs
     * before page resources complete; the caller verifies the resulting login state on the real
     * navigation.
     */
    boolean restore(Page activePage, String storageStateJson, String targetUrl) {
        try {
            BrowserContext context = activePage.context();
            JsonNode root = json.tree(storageStateJson);
            boolean restored = restoreCookies(context, root.path("cookies"));
            List<String> scripts = localStorageScripts(root.path("origins"));
            return scripts.isEmpty() ? restored : hydrateLocalStorage(context, targetUrl, scripts);
        } catch (Exception failure) {
            log.warn("注入站点会话失败", failure);
            return false;
        }
    }

    private boolean restoreCookies(BrowserContext context, JsonNode cookiesNode) {
        if (!cookiesNode.isArray()) return false;
        List<Cookie> cookies = new ArrayList<>();
        for (JsonNode node : cookiesNode) {
            Cookie cookie = cookieFrom(node);
            if (cookie != null) cookies.add(cookie);
        }
        if (cookies.isEmpty()) return false;
        context.addCookies(cookies);
        return true;
    }

    private static Cookie cookieFrom(JsonNode node) {
        if (!node.hasNonNull("name") || !node.hasNonNull("value")) return null;
        Cookie cookie = new Cookie(node.get("name").asText(), node.get("value").asText());
        if (node.has("domain")) cookie.setDomain(node.get("domain").asText());
        if (node.has("path")) cookie.setPath(node.get("path").asText());
        if (node.has("expires") && node.get("expires").asDouble() > 0) {
            cookie.setExpires(node.get("expires").asDouble());
        }
        if (node.has("httpOnly")) cookie.setHttpOnly(node.get("httpOnly").asBoolean());
        if (node.has("secure")) cookie.setSecure(node.get("secure").asBoolean());
        if (node.has("sameSite")) applySameSite(cookie, node.get("sameSite").asText());
        return cookie;
    }

    private static void applySameSite(Cookie cookie, String value) {
        try {
            cookie.setSameSite(SameSiteAttribute.valueOf(value.toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException unsupportedValue) {
            // Playwright defaults are safer than rejecting the complete session for one unknown
            // value.
        }
    }

    private List<String> localStorageScripts(JsonNode originsNode) throws Exception {
        List<String> scripts = new ArrayList<>();
        if (!originsNode.isArray()) return scripts;
        for (JsonNode originNode : originsNode) {
            String origin = originNode.path("origin").asText("");
            JsonNode entries = originNode.path("localStorage");
            if (origin.isBlank() || !entries.isArray() || entries.isEmpty()) continue;
            scripts.add(localStorageScript(origin, entries));
        }
        return scripts;
    }

    private String localStorageScript(String origin, JsonNode entries) throws Exception {
        return """
        (() => {
          try {
            if (window.location.origin !== %s) return;
            for (const entry of %s) {
              window.localStorage.setItem(entry.name, entry.value);
            }
          } catch (ignored) {
            // localStorage may be disabled by the page security policy.
          }
        })();
        """
                .formatted(json.encode(origin), json.encode(entries));
    }

    private static boolean hydrateLocalStorage(
            BrowserContext context, String targetUrl, List<String> scripts) {
        Page hydrationPage = context.newPage();
        try {
            scripts.forEach(hydrationPage::addInitScript);
            hydrationPage.navigate(
                    targetUrl,
                    new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
            return true;
        } catch (PlaywrightException incompleteNavigation) {
            log.warn("预载站点 localStorage 未完整结束: {}", incompleteNavigation.getMessage());
            return true;
        } finally {
            closeHydrationPage(hydrationPage);
        }
    }

    private static void closeHydrationPage(Page hydrationPage) {
        try {
            hydrationPage.close();
        } catch (RuntimeException closeFailure) {
            log.debug("关闭站点会话预载页失败", closeFailure);
        }
    }
}
