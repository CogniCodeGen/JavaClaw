package com.javaclaw.browser;

import com.microsoft.playwright.options.Cookie;

import java.net.URI;
import java.util.Locale;

/** Applies the cookie's domain, path, and secure attributes to a URL. */
final class CookieUrlMatcher {
    private CookieUrlMatcher() { }

    static URI requireHttpUrl(String value) {
        try {
            URI uri = URI.create(value.strip());
            String scheme = uri.getScheme();
            if (scheme == null || uri.getHost() == null
                    || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
                throw new IllegalArgumentException("Cookie 过滤需要完整的 HTTP 或 HTTPS URL");
            }
            return uri;
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("Cookie 过滤需要完整的 HTTP 或 HTTPS URL", invalid);
        }
    }

    static boolean matches(Cookie cookie, URI url) {
        if (cookie == null || url == null || url.getHost() == null
                || cookie.domain == null || cookie.domain.isBlank()) return false;
        String host = url.getHost().toLowerCase(Locale.ROOT);
        if (!domainMatches(cookie.domain, host)) return false;
        if (Boolean.TRUE.equals(cookie.secure)
                && !"https".equalsIgnoreCase(url.getScheme())) return false;
        String requestPath = url.getRawPath();
        if (requestPath == null || requestPath.isEmpty()) requestPath = "/";
        String cookiePath = cookie.path == null || cookie.path.isEmpty() ? "/" : cookie.path;
        return requestPath.equals(cookiePath) || requestPath.startsWith(cookiePath)
                && (cookiePath.endsWith("/") || requestPath.length() > cookiePath.length()
                    && requestPath.charAt(cookiePath.length()) == '/');
    }

    static boolean domainMatches(String cookieDomain, String requestHost) {
        if (cookieDomain == null || cookieDomain.isBlank()
                || requestHost == null || requestHost.isBlank()) return false;
        String domain = cookieDomain.toLowerCase(Locale.ROOT);
        String host = requestHost.toLowerCase(Locale.ROOT);
        boolean domainCookie = domain.startsWith(".");
        if (domainCookie) domain = domain.substring(1);
        return !domain.isBlank() && (host.equals(domain)
                || domainCookie && host.endsWith("." + domain));
    }
}
