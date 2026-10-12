package com.javaclaw.browser;

import com.javaclaw.agent.ToolCallOrigin;
import com.javaclaw.agent.ToolConfirmationManager;
import com.javaclaw.agent.model.ToolResponse;
import com.javaclaw.api.interaction.ChoiceOption;
import com.javaclaw.platform.json.JsonCodec;
import com.javaclaw.site.SiteCredential;
import com.javaclaw.site.SiteCredentialManager;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.WaitUntilState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/** 站点身份选择、交互式登录验证与会话持久化。 */
final class BrowserSiteLogin {
    private static final Logger log = LoggerFactory.getLogger(BrowserSiteLogin.class);
    private final PlaywrightBrowserManager browserManager;
    private final SiteCredentialManager siteCredentials;
    private final SnapshotManager snapshotManager;
    private final ToolCallOrigin origin;
    private final JsonCodec json;
    private final boolean eventDrivenInteraction;
    private final AccountChoice accountChoice;

    @FunctionalInterface
    interface AccountChoice {
        void stage(String tool, String url, List<SiteCredential> matches);
    }

    BrowserSiteLogin(PlaywrightBrowserManager browserManager, SiteCredentialManager siteCredentials,
            SnapshotManager snapshotManager, ToolCallOrigin origin, JsonCodec json,
            boolean eventDrivenInteraction, AccountChoice accountChoice) {
        this.browserManager = browserManager;
        this.siteCredentials = siteCredentials;
        this.snapshotManager = snapshotManager;
        this.origin = origin;
        this.json = json;
        this.eventDrivenInteraction = eventDrivenInteraction;
        this.accountChoice = accountChoice;
    }

    /** 打开可见浏览器等待用户本人登录，验证完成后询问是否持久化站点会话。 */
    String performInteractiveLogin(
            String responseToolName, String targetUrl, String loginUrl, String detectionReason,
            boolean protectedTargetObserved) {
        boolean interactionAttempted = false;
        boolean persistSession = false;
        try {
            interactionAttempted = true;
            Page openedPage = browserManager.showPageForUser(loginUrl);
            String stateBeforeLogin = openedPage.context().storageState();

            boolean loginFinished =
                    ToolConfirmationManager.requestExplicitUserConfirmation(
                            "完成站点登录",
                            "已打开可见浏览器："
                                    + loginUrl
                                    + "\n"
                                    + "请在浏览器中完成登录、验证码或双因素认证。\n"
                                    + "确认页面已经登录成功后，回到此处点击「同意」；取消则不保存。",
                            600);
            if (!loginFinished) {
                return ToolResponse.error(responseToolName, "用户取消了交互式登录或等待超时");
            }

            Page page = browserManager.getActivePage();
            if (page == null) {
                return ToolResponse.error(responseToolName, "登录窗口已关闭，未取得浏览器会话");
            }

            // SSO 可能把用户留在身份提供方的完成页或弹窗；回到原目标页才是对“已登录”的有效验证。
            int verificationStatus = 0;
            if (SiteLoginSupport.hostOf(targetUrl) != null) {
                Response verificationResponse = page.navigate(
                        targetUrl,
                        new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
                verificationStatus = verificationResponse == null ? 0 : verificationResponse.status();
            }

            SiteLoginSupport.LoginSignals signals = loginSignals(page, verificationStatus);
            SiteLoginSupport.LoginAssessment assessment = SiteLoginSupport.assess(signals);
            if (assessment.loginRequired()) {
                return ToolResponse.error(
                        responseToolName,
                        "页面仍显示登录状态（"
                                + assessment.reason()
                                + "）。请确认登录完成后重新调用 site_login_interactive");
            }

            String finalUrl = page.url();
            String saveUrl = chooseSiteUrl(targetUrl, finalUrl);
            String storageState =
                    SiteLoginSupport.filterStorageStateForUrl(
                            page.context().storageState(), saveUrl, json);
            String comparableBeforeLogin =
                    SiteLoginSupport.filterStorageStateForUrl(stateBeforeLogin, saveUrl, json);
            if (SiteLoginSupport.verifyLogin(signals,
                    !java.util.Objects.equals(comparableBeforeLogin, storageState),
                    targetUrl, finalUrl, protectedTargetObserved)
                    != SiteLoginSupport.VerificationStatus.AUTHENTICATED) {
                return ToolResponse.uncertain(responseToolName,
                        "目标站点的持久登录态尚未验证；请完成登录并重新访问受保护页面后再保存会话");
            }
            SiteCredentialManager manager = siteCredentials;
            String scopeId = browserManager.getActiveScopeId();
            SiteCredential site = selectedSiteForCurrentScope(targetUrl);
            if (site == null) {
                site = selectedSiteForCurrentScope(finalUrl);
            }

            String siteName = site == null ? SiteLoginSupport.hostOf(saveUrl) : site.getName();
            if (siteName == null || siteName.isBlank()) siteName = "当前站点";

            boolean save =
                    ToolConfirmationManager.requestExplicitUserConfirmation(
                            "保存站点",
                            "检测到「"
                                    + siteName
                                    + "」已登录。是否保存该站点的浏览器会话？\n"
                                    + "仅保存 Cookie 与站点存储，不保存本次手动输入的账号密码；"
                                    + "下次访问时会自动登录。",
                            120);
            if (save) {
                if (site == null) {
                    site = newUniqueSessionSite(saveUrl, loginUrl);
                }
                try {
                    site = manager.saveSessionChecked(site, storageState, scopeId, saveUrl);
                } catch (RuntimeException persistenceFailure) {
                    log.error("交互式登录后的站点会话事务保存失败", persistenceFailure);
                    return ToolResponse.error(responseToolName, "登录已完成，但站点会话保存失败；本次仍可使用，重启后不会自动登录");
                }
                persistSession = true;
            }

            return ToolResponse.success(
                    responseToolName,
                    "用户登录已完成，"
                            + (save ? "站点会话已保存，下次访问将自动登录" : "本次会话已用于当前任务，但未保存站点")
                            + "。当前 URL: "
                            + finalUrl
                            + (detectionReason == null || detectionReason.isBlank()
                                    ? ""
                                    : "\n原登录判定: " + detectionReason));
        } catch (Exception e) {
            log.error("交互式站点登录失败", e);
            return ToolResponse.error(responseToolName, "交互式登录失败，请检查页面状态后重试");
        } finally {
            if (interactionAttempted) {
                try {
                    browserManager.resumeAfterUserInteraction(persistSession);
                } catch (Exception e) {
                    log.warn("恢复无头浏览器失败: {}", e.getMessage());
                }
            }
            snapshotManager.clearRefs();
        }
    }

    private String chooseSiteUrl(String targetUrl, String finalUrl) {
        String finalHost = SiteLoginSupport.hostOf(finalUrl);
        String targetHost = SiteLoginSupport.hostOf(targetUrl);
        if (finalHost != null && targetHost != null
                && !java.util.Objects.equals(targetHost, finalHost)) {
            return finalUrl;
        }
        return targetHost == null ? finalUrl : targetUrl;
    }

    SiteResolution resolveSiteForNavigation(String url) {
        SiteCredentialManager manager = siteCredentials;
        String scopeId = browserManager.getActiveScopeId();
        if (manager.isNewAccountBound(scopeId, url)) {
            return SiteResolution.selected(null);
        }

        SiteCredential bound = manager.findBoundByUrl(scopeId, url);
        if (bound != null) return SiteResolution.selected(bound);

        List<SiteCredential> matches = manager.findAllByUrl(url);
        if (matches.isEmpty()) return SiteResolution.selected(null);
        if (matches.size() == 1) {
            SiteCredential only = matches.getFirst();
            manager.bindAccount(scopeId, url, only.getId());
            return SiteResolution.selected(only);
        }

        if (eventDrivenInteraction) {
            accountChoice.stage("web_navigate", url, matches);
            return SiteResolution.failed("需要用户明确选择站点账号；导航尚未发出");
        }

        if (origin.kind() != ToolCallOrigin.Kind.INTERACTIVE) {
            return SiteResolution.failed(
                    "站点存在多个已保存账号，当前任务不能猜测账号。请先调用 "
                            + "site_select_account(newAccount=false, accountId=准确 ID) 明确选择。可用账号: "
                            + accountSummary(matches));
        }

        List<ChoiceOption> options = new ArrayList<>();
        for (SiteCredential candidate : matches) {
            options.add(
                    new ChoiceOption(
                            candidate.getId(),
                            accountLabel(candidate),
                            candidate.isHasSession() ? "已保存登录会话" : "仅保存账号配置"));
        }
        options.add(new ChoiceOption("__new__", "使用新账号", "不恢复任何已保存登录状态"));
        String choice =
                ToolConfirmationManager.requestExplicitUserChoice(
                        "选择站点账号",
                        "「"
                                + SiteLoginSupport.hostOf(url)
                                + "」保存了多个账号。"
                                + "请选择本会话要使用的身份；选择只绑定当前会话。",
                        options,
                        120);
        if (choice == null) {
            return SiteResolution.failed("用户取消了站点账号选择");
        }

        boolean boundSuccessfully;
        SiteCredential selected;
        if ("__new__".equals(choice)) {
            boundSuccessfully = manager.bindNewAccount(scopeId, url);
            selected = null;
        } else {
            selected =
                    matches.stream()
                            .filter(candidate -> choice.equals(candidate.getId()))
                            .findFirst()
                            .orElse(null);
            boundSuccessfully =
                    selected != null && manager.bindAccount(scopeId, url, selected.getId());
        }
        if (!boundSuccessfully) {
            return SiteResolution.failed("保存当前会话的账号选择失败");
        }

        // 账号选择发生变化时必须换干净 Context，不能在旧账号 Cookie 上叠加新账号。
        browserManager.replaceActiveContextWithBlank();
        snapshotManager.clearRefs();
        return SiteResolution.selected(selected);
    }

    SiteCredential selectedSiteForCurrentScope(String url) {
        SiteCredentialManager manager = siteCredentials;
        String scopeId = browserManager.getActiveScopeId();
        if (manager.isNewAccountBound(scopeId, url)) return null;
        SiteCredential bound = manager.findBoundByUrl(scopeId, url);
        if (bound != null) return bound;
        List<SiteCredential> matches = manager.findAllByUrl(url);
        if (matches.size() != 1) return null;
        SiteCredential only = matches.getFirst();
        manager.bindAccount(scopeId, url, only.getId());
        return only;
    }

    SiteCredential newUniqueSessionSite(String targetUrl, String loginUrl) {
        SiteCredential site = SiteLoginSupport.newSessionSite(targetUrl, loginUrl);
        int existing = siteCredentials.findAllByUrl(targetUrl).size();
        if (existing > 0) {
            site.setName(site.getName() + "（账号 " + (existing + 1) + "）");
        }
        return site;
    }

    static String accountSummary(List<SiteCredential> credentials) {
        if (credentials == null || credentials.isEmpty()) return "无";
        return credentials.stream()
                .map(site -> accountLabel(site) + " [id=" + site.getId() + "]")
                .collect(Collectors.joining("；"));
    }

    static String accountLabel(SiteCredential site) {
        if (site == null) return "新账号";
        String name = nullToEmpty(site.getName());
        String username = nullToEmpty(site.getUsername());
        if (name.isBlank()) name = site.getHostPattern();
        return username.isBlank() ? name : name + "（" + username + "）";
    }

    static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    record SiteResolution(SiteCredential credential, String error) {
        static SiteResolution selected(SiteCredential credential) {
            return new SiteResolution(credential, null);
        }

        static SiteResolution failed(String error) {
            return new SiteResolution(null, error);
        }
    }

    SiteLoginSupport.LoginAssessment assessLoginPage(Page page, int status) {
        return SiteLoginSupport.assess(loginSignals(page, status));
    }

    SiteLoginSupport.LoginSignals loginSignals(Page page, int status) {
        return new SiteLoginSupport.LoginSignals(
                        status,
                        hasVisible(
                                page,
                                "form:has(input[type='password']):has(input[autocomplete='username'],input[type='email']):has(button[type='submit'],input[type='submit'])",
                                "form:has(input[autocomplete='current-password']):has(button[type='submit'],input[type='submit'])"),
                        hasVisible(page,
                                "form:has(input[autocomplete='one-time-code']):has(button[type='submit'],input[type='submit'])"));
    }

    private boolean hasVisible(Page page, String... selectors) {
        if (page == null || selectors == null) return false;
        for (String selector : selectors) {
            try {
                Locator locator = page.locator(selector);
                int count = locator.count();
                for (int i = 0; i < count; i++) {
                    if (locator.nth(i).isVisible()) return true;
                }
            } catch (PlaywrightException ignored) {
                // 单个非标准页面选择器失败不应中断登录判定
            }
        }
        return false;
    }

    static boolean hasStoredPassword(SiteCredential site) {
        return site != null
                && site.getUsername() != null
                && !site.getUsername().isBlank()
                && site.getPassword() != null
                && !site.getPassword().isBlank();
    }

    /** 用户名脱敏展示用（避免响应里完整泄漏） */
    static String safeUsername(String u) {
        if (u == null || u.isBlank()) return "(未设置)";
        if (u.length() <= 3) return u.charAt(0) + "***";
        return u.substring(0, 2) + "***" + u.substring(Math.max(2, u.length() - 2));
    }
}
