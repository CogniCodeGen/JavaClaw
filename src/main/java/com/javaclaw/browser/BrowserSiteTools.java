package com.javaclaw.browser;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.agent.ToolCallOrigin;
import com.javaclaw.agent.ToolConfirmationManager;
import com.javaclaw.agent.model.ToolResponse;
import com.javaclaw.api.interaction.ChoiceOption;
import com.javaclaw.site.SiteCredential;
import com.javaclaw.site.SiteCredentialManager;
import com.javaclaw.util.ProjectAccessPolicy;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.*;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/** Site navigation, account selection and authenticated-session tools. */
final class BrowserSiteTools {

    private static final Logger log = LoggerFactory.getLogger(BrowserSiteTools.class);

    private final PlaywrightBrowserManager browserManager;
    private final SiteCredentialManager siteCredentials;
    private final SnapshotManager snapshotManager;
    private final ToolCallOrigin origin;
    private final BrowserOperationGate gate;
    private final BrowserTargetResolver targets;
    private final SiteSessionRestorer sessionRestorer;

    BrowserSiteTools(
            PlaywrightBrowserManager browserManager,
            SiteCredentialManager siteCredentials,
            SnapshotManager snapshotManager,
            ToolCallOrigin origin,
            BrowserOperationGate gate) {
        this.browserManager = java.util.Objects.requireNonNull(browserManager, "browserManager");
        this.siteCredentials = java.util.Objects.requireNonNull(siteCredentials, "siteCredentials");
        this.snapshotManager = java.util.Objects.requireNonNull(snapshotManager, "snapshotManager");
        this.origin = java.util.Objects.requireNonNull(origin, "origin");
        this.gate = java.util.Objects.requireNonNull(gate, "gate");
        this.targets = new BrowserTargetResolver(snapshotManager);
        this.sessionRestorer = new SiteSessionRestorer(new ObjectMapper());
    }

    @Tool(
            name = "web_navigate",
            description =
                    "导航到指定 URL 地址。自动补全 https:// 前缀。导航后建议使用 web_snapshot 获取页面元素。"
                            + "若站点已保存会话会自动恢复；交互任务检测到登录页时，会打开可见浏览器让用户本人登录，并在成功后询问是否保存站点。")
    public String navigate(
            @ToolParam(
                            name = "url",
                            description = "目标 URL 地址，例如 www.baidu.com 或 https://github.com")
                    String url) {
        gate.enter();
        try {
            log.debug("工具调用: web_navigate({})", url);
            try {
                if (!ToolConfirmationManager.requestConfirmation(
                        origin, "web_navigate", "导航到: " + url)) {
                    return ToolResponse.error("web_navigate", "用户取消了操作");
                }

                String normalizedUrl =
                        ProjectAccessPolicy.requireSafeBrowserUrl(
                                PlaywrightBrowserManager.normalizeUrl(url));
                SiteResolution siteResolution = resolveSiteForNavigation(normalizedUrl);
                if (siteResolution.error() != null) {
                    return ToolResponse.error("web_navigate", siteResolution.error());
                }
                SiteCredential site = siteResolution.credential();
                Page page = browserManager.getActivePage();
                if (page == null) return ToolResponse.error("web_navigate", "浏览器未启动");

                // 站点匹配：导航前若有已存储会话 → 注入到 BrowserContext，避免再次登录
                boolean sessionRestored = false;
                if (site != null) {
                    String storage = siteCredentials.readSession(site.getId());
                    if (storage != null && !storage.isBlank()) {
                        sessionRestored = sessionRestorer.restore(page, storage, normalizedUrl);
                    }
                }

                Response response =
                        page.navigate(
                                normalizedUrl,
                                new Page.NavigateOptions()
                                        .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));

                int status = (response != null) ? response.status() : 0;
                String title = page.title();

                // 导航后自动清除旧引用
                snapshotManager.clearRefs();

                SiteLoginSupport.LoginAssessment login = assessLoginPage(page, status);
                if (login.loginRequired()) {
                    if (origin.kind() == ToolCallOrigin.Kind.INTERACTIVE) {
                        return performInteractiveLogin(
                                "web_navigate", normalizedUrl, page.url(), login.reason());
                    }
                    String modeHint =
                            origin.kind() == ToolCallOrigin.Kind.SCHEDULED
                                    ? "定时任务无法等待用户登录，请先在交互聊天中登录并保存站点后重试。"
                                    : "当前为托管任务，请先在交互聊天中登录并保存站点，再继续任务。";
                    return ToolResponse.success(
                            "web_navigate",
                            String.format(
                                    "已导航到: %s%n标题: %s%nHTTP状态: %d%n" + "[登录] 检测到页面需要身份验证（%s）。%s",
                                    normalizedUrl, title, status, login.reason(), modeHint));
                }

                String sessionRestoreNote = "";
                if (sessionRestored && site != null) {
                    siteCredentials.touchUsage(site.getId());
                    sessionRestoreNote = "\n[站点] 已恢复 " + site.getName() + " 的已保存会话";
                }

                // 若站点已登记但没有可恢复会话，按是否保存账号密码给出对应路径
                String credentialHint = "";
                if (site != null && !sessionRestored) {
                    if (hasStoredPassword(site)) {
                        credentialHint =
                                String.format(
                                        "\n[站点] 已登记账号（用户名: %s），但无可用会话；需要时可调用 site_login_now。",
                                        safeUsername(site.getUsername()));
                    } else {
                        credentialHint = "\n[站点] 已登记为浏览器会话登录；会话不可用时请调用 site_login_interactive。";
                    }
                }

                return ToolResponse.success(
                        "web_navigate",
                        String.format(
                                "已导航到: %s\n标题: %s\nHTTP状态: %d%s%s\n提示: 使用 web_snapshot 获取页面可交互元素",
                                normalizedUrl, title, status, sessionRestoreNote, credentialHint));
            } catch (PlaywrightException e) {
                log.error("web_navigate 执行异常", e);
                return ToolResponse.error("web_navigate", "导航失败: " + e.getMessage());
            }

        } finally {
            gate.exit();
        }
    }

    // ==================== 站点管理工具 ====================

    @Tool(
            name = "site_select_account",
            description =
                    "为当前聊天/任务选择访问某站点时使用的账号身份。"
                            + "同一站点保存多个账号或用户明确要求换号时，必须先调用本工具；"
                            + "account 可传账号配置名称、用户名、配置 ID，或 new 表示使用全新空白账号。"
                            + "切换会创建干净 BrowserContext，旧账号数据不会混入。")
    public String siteSelectAccount(
            @ToolParam(name = "url", description = "目标站点 URL，例如 https://github.com") String url,
            @ToolParam(name = "account", description = "账号配置名称、用户名、配置 ID；传 new/新账号 表示不恢复已保存登录")
                    String account) {
        gate.enter();
        try {
            log.debug("工具调用: site_select_account({}, {})", url, account);
            try {
                String normalizedUrl = PlaywrightBrowserManager.normalizeUrl(url);
                if (!ToolConfirmationManager.requestConfirmation(
                        origin, "site_select_account", "切换站点账号将清空当前浏览器会话状态: " + normalizedUrl)) {
                    return ToolResponse.error("site_select_account", "用户取消了账号切换");
                }

                SiteCredentialManager manager = siteCredentials;
                String scopeId = browserManager.getActiveScopeId();
                String requested = account == null ? "" : account.trim();
                if (requested.equalsIgnoreCase("new")
                        || requested.equalsIgnoreCase("new-account")
                        || requested.equals("新账号")
                        || requested.equals("不使用已保存账号")) {
                    if (!manager.bindNewAccount(scopeId, normalizedUrl)) {
                        return ToolResponse.error("site_select_account", "保存新账号选择失败");
                    }
                    browserManager.replaceActiveContextWithBlank();
                    snapshotManager.clearRefs();
                    return ToolResponse.success(
                            "site_select_account", "已为当前会话切换到全新空白账号；访问站点后可登录并另存为新账号");
                }

                List<SiteCredential> matches = manager.findAllByUrl(normalizedUrl);
                List<SiteCredential> selected =
                        matches.stream()
                                .filter(
                                        candidate ->
                                                requested.equals(candidate.getId())
                                                        || requested.equalsIgnoreCase(
                                                                nullToEmpty(candidate.getName()))
                                                        || requested.equalsIgnoreCase(
                                                                nullToEmpty(
                                                                        candidate.getUsername())))
                                .toList();
                if (selected.size() != 1) {
                    return ToolResponse.error(
                            "site_select_account",
                            selected.isEmpty()
                                    ? "未找到账号「" + requested + "」。可用账号: " + accountSummary(matches)
                                    : "账号名称不唯一，请改用配置 ID。可用账号: " + accountSummary(matches));
                }

                SiteCredential credential = selected.getFirst();
                if (!manager.bindAccount(scopeId, normalizedUrl, credential.getId())) {
                    return ToolResponse.error("site_select_account", "保存账号选择失败");
                }
                browserManager.replaceActiveContextWithBlank();
                snapshotManager.clearRefs();
                return ToolResponse.success(
                        "site_select_account",
                        "当前会话已切换到账号「" + accountLabel(credential) + "」，下次导航将恢复该账号会话");
            } catch (Exception e) {
                log.error("切换站点账号失败", e);
                return ToolResponse.error("site_select_account", "切换账号失败: " + e.getMessage());
            }

        } finally {
            gate.exit();
        }
    }

    @Tool(
            name = "site_login_interactive",
            description =
                    "当页面要求登录时，临时打开可见浏览器让用户本人完成登录（支持 SSO、验证码和双因素认证）。"
                            + "用户确认登录完成后会校验页面，并询问是否保存站点会话；保存后下次访问自动登录。"
                            + "仅用于有用户在场的交互聊天。")
    public String siteLoginInteractive() {
        gate.enter();
        try {
            log.debug("工具调用: site_login_interactive");
            Page page = browserManager.getActivePage();
            if (page == null) {
                return ToolResponse.error("site_login_interactive", "浏览器未启动");
            }
            if (SiteLoginSupport.hostOf(page.url()) == null) {
                return ToolResponse.error(
                        "site_login_interactive", "当前页面不是可登录的网站，请先用 web_navigate 打开目标站点");
            }
            if (origin.kind() != ToolCallOrigin.Kind.INTERACTIVE) {
                return ToolResponse.error(
                        "site_login_interactive", "当前任务无法等待用户操作；请在交互聊天中完成登录并保存站点后重试");
            }
            return performInteractiveLogin(
                    "site_login_interactive", page.url(), page.url(), "用户请求交互式登录");

        } finally {
            gate.exit();
        }
    }

    @Tool(
            name = "site_login_now",
            description =
                    "在当前页面用「站点管理」中已登记的凭据自动填充并提交登录表单。"
                            + "无需指定用户名/密码：工具内部根据当前页面 URL 匹配到站点条目后直接填入。"
                            + "支持可选选择器覆盖默认启发式（用户名/密码/提交按钮）。登录成功后会询问是否保存会话。")
    public String siteLoginNow(
            @ToolParam(name = "username_selector", description = "用户名输入框的 CSS 选择器；留空则按常见命名启发式查找")
                    String usernameSelector,
            @ToolParam(
                            name = "password_selector",
                            description = "密码输入框的 CSS 选择器；留空则按 input[type=password] 自动定位")
                    String passwordSelector,
            @ToolParam(
                            name = "submit_selector",
                            description = "提交按钮的 CSS 选择器；留空则尝试 button[type=submit] / 含登录文案的按钮")
                    String submitSelector) {
        gate.enter();
        try {
            log.debug("工具调用: site_login_now");
            String stateBeforeLogin = null;
            try {
                Page page = browserManager.getActivePage();
                if (page == null) return ToolResponse.error("site_login_now", "浏览器未启动");
                if (!ToolConfirmationManager.requestConfirmation(
                        origin, "site_login_now", "在当前页面 [" + page.url() + "] 用已登记凭据自动登录")) {
                    return ToolResponse.error("site_login_now", "用户取消了操作");
                }

                String currentUrl = page.url();
                SiteCredential site = selectedSiteForCurrentScope(currentUrl);
                if (site == null) {
                    List<SiteCredential> matches = siteCredentials.findAllByUrl(currentUrl);
                    if (matches.size() > 1) {
                        return ToolResponse.error(
                                "site_login_now",
                                "当前站点有多个账号，请先调用 site_select_account。可用账号: "
                                        + accountSummary(matches));
                    }
                    return ToolResponse.error(
                            "site_login_now",
                            "当前 URL " + currentUrl + " 未匹配任何站点凭据。请在「设置 → 站点管理」中登记。");
                }
                if (!hasStoredPassword(site)) {
                    return ToolResponse.error(
                            "site_login_now",
                            "该站点只保存了浏览器会话，没有账号密码；请调用 site_login_interactive 让用户本人登录");
                }

                stateBeforeLogin = page.context().storageState();

                // 1) 用户名
                String userSel =
                        (usernameSelector != null && !usernameSelector.isBlank())
                                ? usernameSelector
                                : SiteLoginFormLocator.usernameSelector(page);
                if (userSel == null) {
                    return ToolResponse.error(
                            "site_login_now",
                            "未找到用户名输入框。请通过 web_snapshot 查看后用 username_selector 参数指定。");
                }
                page.fill(userSel, site.getUsername());

                // 2) 密码（直接由本工具读取，不进入 LLM 上下文）
                String passSel =
                        (passwordSelector != null && !passwordSelector.isBlank())
                                ? passwordSelector
                                : "input[type='password']";
                page.fill(passSel, site.getPassword());

                // 3) 提交
                String submitSel =
                        (submitSelector != null && !submitSelector.isBlank())
                                ? submitSelector
                                : SiteLoginFormLocator.submitSelector(page);
                String beforeUrl = page.url();
                if (submitSel != null) {
                    page.click(submitSel);
                } else {
                    page.locator(passSel).press("Enter");
                }

                // 4) 等待导航或表单消失（最多 10s）
                try {
                    page.waitForURL(
                            u -> !u.equals(beforeUrl),
                            new Page.WaitForURLOptions().setTimeout(10_000));
                } catch (PlaywrightException ignored) {
                    // 有些 SPA 不变更 URL，仍可能登录成功；继续走会话校验
                }

                // 5) 仅在页面已离开登录态后，询问是否持久化本次会话
                SiteLoginSupport.LoginAssessment login = assessLoginPage(page, 0);
                if (login.loginRequired()) {
                    browserManager.keepSessionTransientUntilTaskReset(stateBeforeLogin);
                    return ToolResponse.error(
                            "site_login_now",
                            "提交后页面仍要求登录（"
                                    + login.reason()
                                    + "），可能需要验证码或双因素认证；请改用 site_login_interactive");
                }
                boolean saved =
                        ToolConfirmationManager.requestExplicitUserConfirmation(
                                "保存站点",
                                "检测到「"
                                        + site.getName()
                                        + "」已登录。是否保存本次浏览器会话？\n"
                                        + "保存后，下次访问该站点会自动登录。",
                                120);
                if (saved) {
                    String storageState =
                            SiteLoginSupport.filterStorageStateForUrl(
                                    page.context().storageState(), currentUrl);
                    if (!siteCredentials.tryWriteSession(site.getId(), storageState)) {
                        browserManager.keepSessionTransientUntilTaskReset(stateBeforeLogin);
                        return ToolResponse.error(
                                "site_login_now", "登录已完成，但站点会话保存失败；本次仍可使用，重启后不会自动登录");
                    }
                } else {
                    browserManager.keepSessionTransientUntilTaskReset(stateBeforeLogin);
                }

                snapshotManager.clearRefs();
                return ToolResponse.success(
                        "site_login_now",
                        String.format(
                                "已使用 %s 的凭据登录，%s。当前 URL: %s",
                                site.getName(), saved ? "会话已保存，下次将自动登录" : "本次未保存站点会话", page.url()));
            } catch (PlaywrightException e) {
                browserManager.keepSessionTransientUntilTaskReset(stateBeforeLogin);
                log.error("site_login_now 执行异常", e);
                return ToolResponse.error("site_login_now", "自动登录失败: " + e.getMessage());
            }

        } finally {
            gate.exit();
        }
    }

    @Tool(
            name = "site_fill_password",
            description =
                    "把已登记的密码填入指定输入框。用于 site_login_now 启发式无法覆盖的非常规登录表单。"
                            + "本工具不向 LLM 暴露密码，密码由站点管理器内部读取。")
    public String siteFillPassword(
            @ToolParam(name = "target_selector", description = "目标密码输入框的 CSS 选择器或元素引用 @e1")
                    String targetSelector) {
        gate.enter();
        try {
            log.debug("工具调用: site_fill_password({})", targetSelector);
            try {
                Page page = browserManager.getActivePage();
                if (page == null) return ToolResponse.error("site_fill_password", "浏览器未启动");
                if (!ToolConfirmationManager.requestConfirmation(
                        origin, "site_fill_password", "填入已登记密码到: " + targetSelector)) {
                    return ToolResponse.error("site_fill_password", "用户取消了操作");
                }
                if (targetSelector == null || targetSelector.isBlank()) {
                    return ToolResponse.error("site_fill_password", "target_selector 不能为空");
                }

                SiteCredential site = selectedSiteForCurrentScope(page.url());
                if (site == null) {
                    return ToolResponse.error(
                            "site_fill_password", "当前 URL 未选择明确账号；同站点多账号时请先调用 site_select_account");
                }
                if (!hasStoredPassword(site)) {
                    return ToolResponse.error(
                            "site_fill_password", "该站点没有保存账号密码，请改用 site_login_interactive");
                }

                Locator locator = targets.resolve(page, targetSelector);
                if (locator == null) {
                    return ToolResponse.error("site_fill_password", "无法解析元素: " + targetSelector);
                }
                locator.fill(site.getPassword());
                return ToolResponse.success(
                        "site_fill_password", "已将 " + site.getName() + " 的密码填入 " + targetSelector);
            } catch (PlaywrightException e) {
                log.error("site_fill_password 执行异常", e);
                return ToolResponse.error("site_fill_password", "填充失败: " + e.getMessage());
            }

        } finally {
            gate.exit();
        }
    }

    @Tool(
            name = "site_save_session",
            description =
                    "把当前浏览器会话（cookies + localStorage）保存到站点，" + "站点尚未登记时会按当前域名自动创建；下次访问会自动恢复登录。")
    public String siteSaveSession() {
        gate.enter();
        try {
            log.debug("工具调用: site_save_session");
            try {
                Page page = browserManager.getActivePage();
                if (page == null) return ToolResponse.error("site_save_session", "浏览器未启动");
                if (!ToolConfirmationManager.requestConfirmation(
                        origin, "site_save_session", "保存当前站点及登录会话: " + page.url())) {
                    return ToolResponse.error("site_save_session", "用户取消了操作");
                }

                SiteCredentialManager manager = siteCredentials;
                String scopeId = browserManager.getActiveScopeId();
                SiteCredential site = selectedSiteForCurrentScope(page.url());
                if (site == null) {
                    if (!manager.isNewAccountBound(scopeId, page.url())
                            && manager.findAllByUrl(page.url()).size() > 1) {
                        return ToolResponse.error(
                                "site_save_session", "当前站点有多个账号且尚未选择，请先调用 site_select_account");
                    }
                    site = newUniqueSessionSite(page.url(), page.url());
                }
                String storageState =
                        SiteLoginSupport.filterStorageStateForUrl(
                                page.context().storageState(), page.url());
                site = manager.saveSessionChecked(site, storageState, scopeId, page.url());
                return ToolResponse.success(
                        "site_save_session", "已保存 " + site.getName() + " 的会话，下次访问该站点会自动恢复");
            } catch (Exception e) {
                log.error("site_save_session 执行异常", e);
                return ToolResponse.error("site_save_session", "站点会话未能确认写入数据库，请重试");
            }

        } finally {
            gate.exit();
        }
    }

    @Tool(
            name = "site_clear_session",
            description = "清除匹配的站点条目的已保存会话（cookies/storage），下次访问需重新登录。" + "凭据条目本身保留，仅删除其会话快照。")
    public String siteClearSession() {
        gate.enter();
        try {
            log.debug("工具调用: site_clear_session");
            try {
                Page page = browserManager.getActivePage();
                if (page == null) return ToolResponse.error("site_clear_session", "浏览器未启动");
                if (!ToolConfirmationManager.requestConfirmation(
                        origin, "site_clear_session", "清除站点会话: " + page.url())) {
                    return ToolResponse.error("site_clear_session", "用户取消了操作");
                }

                SiteCredential site = selectedSiteForCurrentScope(page.url());
                if (site == null) {
                    return ToolResponse.error(
                            "site_clear_session", "当前 URL 未选择明确的站点账号；请先调用 site_select_account");
                }
                siteCredentials.clearSession(site.getId());
                return ToolResponse.success("site_clear_session", "已清除 " + site.getName() + " 的会话");
            } catch (Exception e) {
                log.error("site_clear_session 执行异常", e);
                return ToolResponse.error("site_clear_session", "清除失败: " + e.getMessage());
            }

        } finally {
            gate.exit();
        }
    }

    // ==================== 站点工具内部辅助 ====================

    /** 打开可见浏览器等待用户本人登录，验证完成后询问是否持久化站点会话。 */
    private String performInteractiveLogin(
            String responseToolName, String targetUrl, String loginUrl, String detectionReason) {
        boolean interactionAttempted = false;
        boolean persistSession = false;
        try {
            interactionAttempted = true;
            Page openedPage = browserManager.showPageForUser(loginUrl);
            String stateBeforeLogin = openedPage.context().storageState();
            String urlBeforeLogin = openedPage.url();

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
            if (SiteLoginSupport.hostOf(targetUrl) != null
                    && !SiteLoginSupport.looksLikeLoginUrl(targetUrl)) {
                page.navigate(
                        targetUrl,
                        new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
            }

            SiteLoginSupport.LoginAssessment assessment = assessLoginPage(page, 0);
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
                            page.context().storageState(), saveUrl);
            String comparableBeforeLogin =
                    SiteLoginSupport.filterStorageStateForUrl(stateBeforeLogin, saveUrl);
            if (java.util.Objects.equals(comparableBeforeLogin, storageState)
                    && java.util.Objects.equals(urlBeforeLogin, finalUrl)
                    && SiteLoginSupport.looksLikeLoginUrl(finalUrl)) {
                return ToolResponse.error(
                        responseToolName, "未检测到页面或登录会话发生变化，请完成登录后重新调用 site_login_interactive");
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
        if (SiteLoginSupport.looksLikeLoginUrl(targetUrl)
                && finalHost != null
                && (!java.util.Objects.equals(targetHost, finalHost)
                        || !SiteLoginSupport.looksLikeLoginUrl(finalUrl))) {
            return finalUrl;
        }
        return targetHost == null ? finalUrl : targetUrl;
    }

    private SiteResolution resolveSiteForNavigation(String url) {
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

        if (origin.kind() != ToolCallOrigin.Kind.INTERACTIVE) {
            return SiteResolution.failed(
                    "站点存在多个已保存账号，当前任务不能猜测账号。请先调用 "
                            + "site_select_account(url, account) 明确选择。可用账号: "
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

    private SiteCredential selectedSiteForCurrentScope(String url) {
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

    private SiteCredential newUniqueSessionSite(String targetUrl, String loginUrl) {
        SiteCredential site = SiteLoginSupport.newSessionSite(targetUrl, loginUrl);
        int existing = siteCredentials.findAllByUrl(targetUrl).size();
        if (existing > 0) {
            site.setName(site.getName() + "（账号 " + (existing + 1) + "）");
        }
        return site;
    }

    private static String accountSummary(List<SiteCredential> credentials) {
        if (credentials == null || credentials.isEmpty()) return "无";
        return credentials.stream()
                .map(site -> accountLabel(site) + " [id=" + site.getId() + "]")
                .collect(Collectors.joining("；"));
    }

    private static String accountLabel(SiteCredential site) {
        if (site == null) return "新账号";
        String name = nullToEmpty(site.getName());
        String username = nullToEmpty(site.getUsername());
        if (name.isBlank()) name = site.getHostPattern();
        return username.isBlank() ? name : name + "（" + username + "）";
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private record SiteResolution(SiteCredential credential, String error) {
        static SiteResolution selected(SiteCredential credential) {
            return new SiteResolution(credential, null);
        }

        static SiteResolution failed(String error) {
            return new SiteResolution(null, error);
        }
    }

    private SiteLoginSupport.LoginAssessment assessLoginPage(Page page, int status) {
        SiteLoginSupport.LoginSignals signals =
                new SiteLoginSupport.LoginSignals(
                        page == null ? "" : page.url(),
                        status,
                        hasVisible(
                                page,
                                "input[type='password']",
                                "input[autocomplete='one-time-code']",
                                "input[name*='otp' i]",
                                "input[name*='verification' i]"),
                        hasVisible(
                                page,
                                "input[autocomplete='username']",
                                "input[type='email']",
                                "input[name*='user' i]",
                                "input[name*='account' i]",
                                "input[name*='login' i]"),
                        hasVisible(
                                page,
                                "button:has-text('登录')",
                                "button:has-text('Sign in')",
                                "button:has-text('Log in')",
                                "button:has-text('Login')",
                                "[role='button']:has-text('登录')",
                                "input[type='submit'][value*='登录']"),
                        hasVisible(
                                page,
                                "a:has-text('退出登录')",
                                "button:has-text('退出登录')",
                                "a:has-text('Sign out')",
                                "button:has-text('Sign out')",
                                "a:has-text('Log out')",
                                "button:has-text('Log out')"));
        return SiteLoginSupport.assess(signals);
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

    private static boolean hasStoredPassword(SiteCredential site) {
        return site != null
                && site.getUsername() != null
                && !site.getUsername().isBlank()
                && site.getPassword() != null
                && !site.getPassword().isBlank();
    }

    /** 用户名脱敏展示用（避免响应里完整泄漏） */
    private static String safeUsername(String u) {
        if (u == null || u.isBlank()) return "(未设置)";
        if (u.length() <= 3) return u.charAt(0) + "***";
        return u.substring(0, 2) + "***" + u.substring(Math.max(2, u.length() - 2));
    }
}
