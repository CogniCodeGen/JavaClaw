package com.javaclaw.browser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javaclaw.platform.json.JsonCodec;
import com.javaclaw.site.SiteCredential;

import java.net.URI;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 站点登录流程中的纯逻辑辅助。
 *
 * <p>页面探测本身由 {@link PlaywrightBrowserTools} 完成；这里仅对探测信号评分，并负责从
 * URL 构造“仅保存浏览器会话、不保存账号密码”的站点条目，便于无浏览器环境的单元测试。</p>
 */
final class SiteLoginSupport {

    private SiteLoginSupport() {
    }

    record LoginSignals(int httpStatus, boolean credentialForm, boolean challengeForm) { }

    enum LoginEvidence { HTTP_UNAUTHORIZED, HTTP_FORBIDDEN, CREDENTIAL_FORM, CHALLENGE_FORM }

    record LoginAssessment(boolean loginRequired, Set<LoginEvidence> evidence) {
        LoginAssessment { evidence = Set.copyOf(evidence); }

        String reason() {
            return evidence.stream().map(value -> switch (value) {
                case HTTP_UNAUTHORIZED -> "HTTP 401";
                case HTTP_FORBIDDEN -> "HTTP 403";
                case CREDENTIAL_FORM -> "凭据表单";
                case CHALLENGE_FORM -> "验证表单";
            }).collect(java.util.stream.Collectors.joining("、"));
        }
    }

    enum VerificationStatus { AUTHENTICATED, LOGIN_REQUIRED, UNVERIFIED }

    /** A post-login check needs an observed challenge on the target before the session changed. */
    static VerificationStatus verifyLogin(LoginSignals signals, boolean sessionChanged,
            String targetUrl, String finalUrl, boolean protectedTargetObserved) {
        if (assess(signals).loginRequired()) return VerificationStatus.LOGIN_REQUIRED;
        String targetHost = hostOf(targetUrl);
        String finalHost = hostOf(finalUrl);
        if (!protectedTargetObserved || !sessionChanged
                || targetHost == null || !targetHost.equals(finalHost)
                || signals == null || signals.httpStatus() < 200
                || signals.httpStatus() >= 400) return VerificationStatus.UNVERIFIED;
        return VerificationStatus.AUTHENTICATED;
    }

    /** Only transport status and semantic form structure can require authentication. */
    static LoginAssessment assess(LoginSignals signals) {
        if (signals == null) return new LoginAssessment(false, Set.of());
        EnumSet<LoginEvidence> evidence = EnumSet.noneOf(LoginEvidence.class);
        if (signals.httpStatus() == 401) evidence.add(LoginEvidence.HTTP_UNAUTHORIZED);
        if (signals.httpStatus() == 403) evidence.add(LoginEvidence.HTTP_FORBIDDEN);
        if (signals.credentialForm()) evidence.add(LoginEvidence.CREDENTIAL_FORM);
        if (signals.challengeForm()) evidence.add(LoginEvidence.CHALLENGE_FORM);
        boolean challenge = evidence.contains(LoginEvidence.HTTP_UNAUTHORIZED)
                || evidence.contains(LoginEvidence.CREDENTIAL_FORM)
                || evidence.contains(LoginEvidence.CHALLENGE_FORM);
        return new LoginAssessment(challenge, evidence);
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
     * 只保留目标站点能接收的 Cookie 和完全相同 origin 的 localStorage。
     *
     * <p>{@code BrowserContext.storageState()} 包含 Context 访问过的全部站点。若原样保存到某个
     * 账号配置，恢复该账号时会顺带注入其他网站的身份令牌。这里在持久化边界做最小化裁剪；
     * 身份提供方若没有把会话换成目标站点自己的 Cookie，则下次会要求重新走 SSO，安全优先。</p>
     */
    static String filterStorageStateForUrl(
            String storageStateJson, String targetUrl, JsonCodec json) {
        if (storageStateJson == null || storageStateJson.isBlank()) {
            return "{\"cookies\":[],\"origins\":[]}";
        }
        URI target = CookieUrlMatcher.requireHttpUrl(targetUrl);
        String targetHost = target.getHost().toLowerCase(Locale.ROOT);
        try {
            JsonNode root = json.tree(storageStateJson);
            ObjectNode filtered = json.mapper().createObjectNode();
            ArrayNode cookies = filtered.putArray("cookies");
            JsonNode sourceCookies = root.path("cookies");
            if (sourceCookies.isArray()) {
                sourceCookies.forEach(cookie -> {
                    String domain = cookie.path("domain").asText("");
                    if (CookieUrlMatcher.domainMatches(domain, targetHost))
                        cookies.add(cookie.deepCopy());
                });
            }

            ArrayNode origins = filtered.putArray("origins");
            JsonNode sourceOrigins = root.path("origins");
            if (sourceOrigins.isArray()) {
                sourceOrigins.forEach(origin -> {
                    if (sameOrigin(target, origin.path("origin").asText("")))
                        origins.add(origin.deepCopy());
                });
            }
            return json.encode(filtered);
        } catch (Exception e) {
            throw new IllegalArgumentException("浏览器会话状态格式无效", e);
        }
    }

    private static boolean sameOrigin(URI target, String candidate) {
        try {
            URI origin = CookieUrlMatcher.requireHttpUrl(candidate);
            return target.getScheme().equalsIgnoreCase(origin.getScheme())
                    && target.getHost().equalsIgnoreCase(origin.getHost())
                    && effectivePort(target) == effectivePort(origin);
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    private static int effectivePort(URI value) {
        return value.getPort() >= 0 ? value.getPort()
                : "https".equalsIgnoreCase(value.getScheme()) ? 443 : 80;
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
