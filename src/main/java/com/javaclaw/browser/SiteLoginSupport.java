package com.javaclaw.browser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javaclaw.site.SiteCredential;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 站点登录流程中的纯逻辑辅助。
 *
 * <p>页面探测本身由 {@link PlaywrightBrowserTools} 完成；这里仅对探测信号评分，并负责从
 * URL 构造“仅保存浏览器会话、不保存账号密码”的站点条目，便于无浏览器环境的单元测试。</p>
 */
final class SiteLoginSupport {

    private SiteLoginSupport() {
    }

    record LoginSignals(
            String url,
            int httpStatus,
            boolean visiblePasswordField,
            boolean visibleIdentityField,
            boolean visibleLoginControl,
            boolean visibleSignedInControl
    ) {
    }

    record LoginAssessment(boolean loginRequired, int score, String reason) {
    }

    /**
     * 对登录信号做保守评分。HTTP 401/403 直接判定；普通页面至少需要多个登录特征同时出现，
     * 避免仅因“修改密码”或正文中的“登录”字样就打断用户。
     */
    static LoginAssessment assess(LoginSignals signals) {
        if (signals == null) {
            return new LoginAssessment(false, 0, "");
        }

        List<String> reasons = new ArrayList<>();
        int score = 0;
        if (signals.httpStatus() == 401 || signals.httpStatus() == 403) {
            score += 5;
            reasons.add("HTTP " + signals.httpStatus());
        }
        if (looksLikeLoginUrl(signals.url())) {
            score += 2;
            reasons.add("登录地址");
        }
        if (signals.visiblePasswordField()) {
            score += 2;
            reasons.add("密码输入框");
        }
        if (signals.visibleIdentityField()) {
            score += 1;
            reasons.add("账号输入框");
        }
        if (signals.visibleLoginControl()) {
            score += 1;
            reasons.add("登录按钮");
        }
        if (signals.visibleSignedInControl()) {
            score -= 3;
            reasons.add("已登录标识");
        }

        boolean required = signals.httpStatus() == 401 || signals.httpStatus() == 403 || score >= 3;
        return new LoginAssessment(required, score, String.join("、", reasons));
    }

    static boolean looksLikeLoginUrl(String url) {
        if (url == null || url.isBlank()) return false;
        try {
            URI uri = URI.create(url.trim());
            String candidate = ((uri.getPath() == null ? "" : uri.getPath()) + "?"
                    + (uri.getQuery() == null ? "" : uri.getQuery())).toLowerCase(Locale.ROOT);
            return containsPathToken(candidate, "login")
                    || containsPathToken(candidate, "signin")
                    || containsPathToken(candidate, "sign-in")
                    || containsPathToken(candidate, "authorize")
                    || containsPathToken(candidate, "authorization")
                    || containsPathToken(candidate, "sso")
                    || containsPathToken(candidate, "mfa")
                    || containsPathToken(candidate, "2fa")
                    || containsPathToken(candidate, "challenge");
        } catch (IllegalArgumentException ignored) {
            String lower = url.toLowerCase(Locale.ROOT);
            return lower.contains("/login") || lower.contains("/signin") || lower.contains("/sign-in");
        }
    }

    private static boolean containsPathToken(String value, String token) {
        return java.util.regex.Pattern.compile(
                        "(^|[/=?&_.-])" + java.util.regex.Pattern.quote(token)
                                + "([/?&#_.=-]|$)")
                .matcher(value)
                .find();
    }

    static SiteCredential newSessionSite(String targetUrl, String loginUrl) {
        String host = hostOf(targetUrl);
        if (host == null) {
            host = hostOf(loginUrl);
        }
        if (host == null) {
            throw new IllegalArgumentException("无法从当前页面识别站点域名");
        }

        SiteCredential credential = new SiteCredential();
        credential.setName(host);
        credential.setHostPattern(host);
        credential.setLoginUrl(blankToNull(loginUrl));
        credential.setUsername("");
        credential.setPassword("");
        credential.setNotes("通过浏览器手动登录保存的会话");
        return credential;
    }

    /**
     * 只保留目标站点及其父/子域的 Cookie 和 localStorage。
     *
     * <p>{@code BrowserContext.storageState()} 包含 Context 访问过的全部站点。若原样保存到某个
     * 账号配置，恢复该账号时会顺带注入其他网站的身份令牌。这里在持久化边界做最小化裁剪；
     * 身份提供方若没有把会话换成目标站点自己的 Cookie，则下次会要求重新走 SSO，安全优先。</p>
     */
    static String filterStorageStateForUrl(String storageStateJson, String targetUrl) {
        if (storageStateJson == null || storageStateJson.isBlank()) {
            return "{\"cookies\":[],\"origins\":[]}";
        }
        String targetHost = hostOf(targetUrl);
        if (targetHost == null) {
            throw new IllegalArgumentException("无法识别要保存会话的站点域名");
        }
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(storageStateJson);
            ObjectNode filtered = mapper.createObjectNode();
            ArrayNode cookies = filtered.putArray("cookies");
            JsonNode sourceCookies = root.path("cookies");
            if (sourceCookies.isArray()) {
                sourceCookies.forEach(cookie -> {
                    String domain = cookie.path("domain").asText("");
                    if (hostRelated(targetHost, domain)) cookies.add(cookie.deepCopy());
                });
            }

            ArrayNode origins = filtered.putArray("origins");
            JsonNode sourceOrigins = root.path("origins");
            if (sourceOrigins.isArray()) {
                sourceOrigins.forEach(origin -> {
                    String originHost = hostOf(origin.path("origin").asText(""));
                    if (hostRelated(targetHost, originHost)) origins.add(origin.deepCopy());
                });
            }
            return mapper.writeValueAsString(filtered);
        } catch (Exception e) {
            throw new IllegalArgumentException("浏览器会话状态格式无效", e);
        }
    }

    private static boolean hostRelated(String targetHost, String candidateDomain) {
        if (targetHost == null || candidateDomain == null || candidateDomain.isBlank()) return false;
        String candidate = candidateDomain.trim().toLowerCase(Locale.ROOT);
        while (candidate.startsWith(".")) candidate = candidate.substring(1);
        String target = targetHost.toLowerCase(Locale.ROOT);
        return target.equals(candidate)
                || target.endsWith("." + candidate)
                || candidate.endsWith("." + target);
    }

    static String hostOf(String url) {
        if (url == null || url.isBlank()) return null;
        try {
            String normalized = url.contains("://") ? url.trim() : "https://" + url.trim();
            String host = URI.create(normalized).getHost();
            return host == null || host.isBlank() ? null : host.toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static String blankToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
