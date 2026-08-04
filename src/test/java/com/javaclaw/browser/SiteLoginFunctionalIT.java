package com.javaclaw.browser;

import com.javaclaw.agent.ToolCallOrigin;
import com.javaclaw.agent.ToolConfirmationManager;
import com.javaclaw.agent.model.ToolResponse;
import com.javaclaw.api.interaction.ConfirmRequest;
import com.javaclaw.api.interaction.ChoiceRequest;
import com.javaclaw.api.interaction.ToastRequest;
import com.javaclaw.api.interaction.UserInteractionPort;
import com.javaclaw.config.AppDatabase;
import com.javaclaw.config.WorkspaceManager;
import com.javaclaw.site.SiteCredential;
import com.javaclaw.site.SiteCredentialManager;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.Cookie;
import com.microsoft.playwright.options.WaitUntilState;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 站点登录功能测试。
 *
 * <p>该类以 {@code *IT} 命名，不进入普通 {@code mvn test}，避免日常单元测试弹出浏览器。
 * 显式运行 {@code mvn -Dtest=SiteLoginFunctionalIT test} 时，会启动本地登录站点和真实 Chrome：
 * 首次登录阶段短暂显示浏览器，确认后恢复无头模式，再用全新 BrowserContext 验证自动登录。</p>
 */
class SiteLoginFunctionalIT {

    private static final String TEST_HOST = "127.0.0.1";
    private static final String SESSION_COOKIE = "functional-session=authenticated";
    private static final String STORAGE_KEY = "functional-login-token";

    private static HttpServer server;
    private static String baseUrl;

    @TempDir
    Path tempDir;

    private UserInteractionPort previousPort;
    private boolean previousConfirmationEnabled;

    @BeforeAll
    static void startTestSite() throws IOException {
        // 与真实应用启动顺序一致：所有工作区维度配置必须在 WorkspaceManager.init() 后加载。
        WorkspaceManager.getInstance().init();
        server = HttpServer.create(new InetSocketAddress(TEST_HOST, 0), 0);
        server.createContext("/", SiteLoginFunctionalIT::handleRequest);
        server.start();
        baseUrl = "http://" + TEST_HOST + ":" + server.getAddress().getPort();
    }

    @AfterAll
    static void stopTestSite() {
        if (server != null) {
            server.stop(0);
        }
    }

    @BeforeEach
    void isolateBrowserAndSiteState() throws Exception {
        previousPort = ToolConfirmationManager.getPort();
        previousConfirmationEnabled = ToolConfirmationManager.isEnabled();
        ToolConfirmationManager.setEnabled(false);
        removeTestSites();
        clearWorkspaceBrowserState();
    }

    @AfterEach
    void restoreSharedState() throws Exception {
        try {
            removeTestSites();
            clearWorkspaceBrowserState();
        } finally {
            ToolConfirmationManager.setPort(previousPort);
            ToolConfirmationManager.setEnabled(previousConfirmationEnabled);
        }
    }

    @Test
    @Timeout(90)
    void savedSessionAutomaticallyLogsInWithCookieAndLocalStorage() {
        PlaywrightBrowserManager firstBrowser = newBrowser("saved-first");
        try {
            AutomatedLoginPort firstLogin = new AutomatedLoginPort(firstBrowser, true, true);
            ToolConfirmationManager.setPort(firstLogin);

            String firstResult = new PlaywrightBrowserTools(
                    firstBrowser, ToolCallOrigin.INTERACTIVE).navigate(baseUrl + "/private");

            assertTrue(ToolResponse.isSuccess(firstResult), firstResult);
            assertTrue(firstResult.contains("站点会话已保存"), firstResult);
            assertEquals(1, firstLogin.loginPrompts.get());
            assertEquals(1, firstLogin.savePrompts.get());

            SiteCredential savedSite = SiteCredentialManager.getInstance()
                    .findByUrl(baseUrl + "/private");
            assertNotNull(savedSite);
            assertTrue(savedSite.isHasSession());
            String savedState = SiteCredentialManager.getInstance()
                    .readSession(savedSite.getId());
            assertNotNull(savedState);
            assertTrue(savedState.contains(STORAGE_KEY),
                    "保存的 storageState 应包含 localStorage 登录令牌");
        } finally {
            firstBrowser.shutdown();
        }

        PlaywrightBrowserManager secondBrowser = newBrowser("saved-second");
        try {
            AutomatedLoginPort shouldNotPrompt = new AutomatedLoginPort(
                    secondBrowser, false, false);
            ToolConfirmationManager.setPort(shouldNotPrompt);

            String secondResult = new PlaywrightBrowserTools(
                    secondBrowser, ToolCallOrigin.INTERACTIVE).navigate(baseUrl + "/private");

            assertTrue(ToolResponse.isSuccess(secondResult), secondResult);
            assertTrue(secondResult.contains("已恢复"), secondResult);
            assertEquals(0, shouldNotPrompt.loginPrompts.get(),
                    "恢复成功时不应再次要求用户登录");
            assertEquals("AUTOMATIC_LOGIN_OK",
                    secondBrowser.getActivePage().locator("#result").textContent());
        } finally {
            secondBrowser.shutdown();
        }
    }

    @Test
    @Timeout(90)
    void decliningSaveRequiresLoginAgainInFreshBrowser() throws Exception {
        PlaywrightBrowserManager firstBrowser = newBrowser("decline-first", true);
        try {
            AutomatedLoginPort declineSave = new AutomatedLoginPort(firstBrowser, true, false);
            ToolConfirmationManager.setPort(declineSave);

            String firstResult = new PlaywrightBrowserTools(
                    firstBrowser, ToolCallOrigin.INTERACTIVE).navigate(baseUrl + "/private");

            assertTrue(ToolResponse.isSuccess(firstResult), firstResult);
            assertTrue(firstResult.contains("未保存站点"), firstResult);
            assertEquals(1, declineSave.loginPrompts.get());
            assertEquals(1, declineSave.savePrompts.get());
            assertNull(SiteCredentialManager.getInstance().findByUrl(baseUrl + "/private"));
        } finally {
            firstBrowser.shutdown();
        }

        assertNull(readWorkspaceBrowserState(),
                "完整隔离模式不再持久化任何工作区级浏览器认证态");

        PlaywrightBrowserManager secondBrowser = newBrowser("decline-second", true);
        try {
            AutomatedLoginPort loginAgain = new AutomatedLoginPort(secondBrowser, true, false);
            ToolConfirmationManager.setPort(loginAgain);

            String secondResult = new PlaywrightBrowserTools(
                    secondBrowser, ToolCallOrigin.INTERACTIVE).navigate(baseUrl + "/private");

            assertTrue(ToolResponse.isSuccess(secondResult), secondResult);
            assertEquals(1, loginAgain.loginPrompts.get(),
                    "未保存后使用全新浏览器应再次进入交互式登录");
        } finally {
            secondBrowser.shutdown();
        }
    }

    @Test
    @Timeout(90)
    void failedLoginIsNotOfferedForSaving() {
        PlaywrightBrowserManager browser = newBrowser("failed-login");
        try {
            AutomatedLoginPort failedLogin = new AutomatedLoginPort(browser, false, true);
            ToolConfirmationManager.setPort(failedLogin);

            String result = new PlaywrightBrowserTools(
                    browser, ToolCallOrigin.INTERACTIVE).navigate(baseUrl + "/private");

            assertFalse(ToolResponse.isSuccess(result), result);
            assertTrue(result.contains("仍显示登录状态"), result);
            assertEquals(1, failedLogin.loginPrompts.get());
            assertEquals(0, failedLogin.savePrompts.get(),
                    "登录校验失败时不应询问是否保存");
            assertNull(SiteCredentialManager.getInstance().findByUrl(baseUrl + "/private"));
        } finally {
            browser.shutdown();
        }
    }

    @Test
    @Timeout(90)
    void managedAndScheduledTasksDoNotOpenInteractiveLogin() {
        assertUnattendedLoginIsDeferred(
                "managed-login",
                ToolCallOrigin.managedTask("functional-task", tempDir.toString()),
                "当前为托管任务");
        assertUnattendedLoginIsDeferred(
                "scheduled-login",
                ToolCallOrigin.scheduled("functional-schedule"),
                "定时任务无法等待用户登录");
    }

    @Test
    @Timeout(90)
    void signedInPasswordSettingsPageIsNotMistakenForLogin() {
        PlaywrightBrowserManager browser = newBrowser("password-settings");
        try {
            AutomatedLoginPort shouldNotPrompt = new AutomatedLoginPort(browser, true, true);
            ToolConfirmationManager.setPort(shouldNotPrompt);

            String result = new PlaywrightBrowserTools(
                    browser, ToolCallOrigin.INTERACTIVE)
                    .navigate(baseUrl + "/password-settings");

            assertTrue(ToolResponse.isSuccess(result), result);
            assertEquals(0, shouldNotPrompt.loginPrompts.get());
            assertEquals("PASSWORD_SETTINGS",
                    browser.getActivePage().locator("#result").textContent());
        } finally {
            browser.shutdown();
        }
    }

    @Test
    @Timeout(90)
    void storedCredentialsLoginAlsoRequiresAndPersistsSaveDecision() {
        SiteCredential credential = new SiteCredential();
        credential.setName("功能测试账号");
        credential.setHostPattern(TEST_HOST);
        credential.setLoginUrl(baseUrl + "/login");
        credential.setUsername("demo");
        credential.setPassword("secret");
        SiteCredentialManager.getInstance().put(credential);

        PlaywrightBrowserManager browser = newBrowser("stored-credentials");
        try {
            AutomatedLoginPort saveSession = new AutomatedLoginPort(browser, true, true);
            ToolConfirmationManager.setPort(saveSession);
            browser.getActivePage().navigate(
                    baseUrl + "/login",
                    new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));

            String result = new PlaywrightBrowserTools(
                    browser, ToolCallOrigin.INTERACTIVE).siteLoginNow("", "", "");

            assertTrue(ToolResponse.isSuccess(result), result);
            assertTrue(result.contains("会话已保存"), result);
            assertEquals(0, saveSession.loginPrompts.get(),
                    "账号密码自动登录不应走手动登录提示");
            assertEquals(1, saveSession.savePrompts.get());
            assertTrue(credential.isHasSession());
            assertTrue(SiteCredentialManager.getInstance()
                    .readSession(credential.getId()).contains(STORAGE_KEY));
        } finally {
            browser.shutdown();
        }
    }

    @Test
    @Timeout(90)
    void expiredSavedSessionFallsBackToInteractiveLogin() {
        SiteCredential credential = new SiteCredential();
        credential.setName("过期会话");
        credential.setHostPattern(TEST_HOST);
        credential.setLoginUrl(baseUrl + "/login");
        credential.setUsername("");
        credential.setPassword("");
        SiteCredentialManager manager = SiteCredentialManager.getInstance();
        manager.put(credential);
        assertTrue(manager.tryWriteSession(
                credential.getId(), "{\"cookies\":[],\"origins\":[]}"));

        PlaywrightBrowserManager browser = newBrowser("expired-session");
        try {
            AutomatedLoginPort refreshSession = new AutomatedLoginPort(browser, true, true);
            ToolConfirmationManager.setPort(refreshSession);

            String result = new PlaywrightBrowserTools(
                    browser, ToolCallOrigin.INTERACTIVE).navigate(baseUrl + "/private");

            assertTrue(ToolResponse.isSuccess(result), result);
            assertEquals(1, refreshSession.loginPrompts.get());
            assertEquals(1, refreshSession.savePrompts.get());
            assertTrue(manager.readSession(credential.getId()).contains(STORAGE_KEY),
                    "过期会话应被新登录态替换");
        } finally {
            browser.shutdown();
        }
    }

    @Test
    @Timeout(90)
    void loggingInThenCancellingDoesNotPersistAuthentication() throws Exception {
        PlaywrightBrowserManager browser = newBrowser("login-then-cancel", true);
        try {
            LoginThenCancelPort cancel = new LoginThenCancelPort(browser);
            ToolConfirmationManager.setPort(cancel);

            String result = new PlaywrightBrowserTools(
                    browser, ToolCallOrigin.INTERACTIVE).navigate(baseUrl + "/private");

            assertFalse(ToolResponse.isSuccess(result), result);
            assertTrue(result.contains("用户取消"), result);
            assertEquals(1, cancel.loginPrompts.get());
            assertEquals(0, cancel.savePrompts.get());
            assertNull(SiteCredentialManager.getInstance().findByUrl(baseUrl + "/private"));
        } finally {
            browser.shutdown();
        }

        assertNull(readWorkspaceBrowserState(),
                "取消登录后不应产生工作区级浏览器认证态");
    }

    @Test
    @Timeout(90)
    void conversationContextsKeepDifferentAccountsIsolated() {
        PlaywrightBrowserManager browser = newBrowser("conversation-isolation");
        String scopeA = PlaywrightBrowserManager.conversationScopeId("chat-a");
        String scopeB = PlaywrightBrowserManager.conversationScopeId("chat-b");
        try {
            browser.activateScope(scopeA);
            Page pageA = browser.getActivePage();
            pageA.navigate(baseUrl + "/identity");
            pageA.context().addCookies(java.util.List.of(
                    new Cookie("account", "ACCOUNT_A").setUrl(baseUrl)));
            pageA.evaluate("localStorage.setItem('account-local', 'ACCOUNT_A')");
            pageA.reload();
            assertEquals("ACCOUNT_A", pageA.locator("#result").textContent());

            browser.activateScope(scopeB);
            Page pageB = browser.getActivePage();
            pageB.navigate(baseUrl + "/identity");
            assertEquals("ANONYMOUS", pageB.locator("#result").textContent(),
                    "新聊天会话不得继承会话 A 的 Cookie");
            assertNull(pageB.evaluate("localStorage.getItem('account-local')"),
                    "新聊天会话不得继承会话 A 的 localStorage");

            pageB.context().addCookies(java.util.List.of(
                    new Cookie("account", "ACCOUNT_B").setUrl(baseUrl)));
            pageB.evaluate("localStorage.setItem('account-local', 'ACCOUNT_B')");
            pageB.reload();
            assertEquals("ACCOUNT_B", pageB.locator("#result").textContent());

            browser.activateScope(scopeA);
            Page restoredA = browser.getActivePage();
            restoredA.navigate(baseUrl + "/identity");
            assertEquals("ACCOUNT_A", restoredA.locator("#result").textContent(),
                    "切回会话 A 后应恢复 A 自己的 Cookie");
            assertEquals("ACCOUNT_A",
                    restoredA.evaluate("localStorage.getItem('account-local')"));

            // A 仍为活动 Context 时，B 已请求接管；释放 A 不得误取消 B，
            // 也不得把 B 的内存快照一起关掉。
            browser.activateScope(scopeB);
            browser.releaseScope(scopeA);
            assertEquals(scopeB, browser.getActiveScopeId());
            Page restoredB = browser.getActivePage();
            restoredB.navigate(baseUrl + "/identity");
            assertEquals("ACCOUNT_B", restoredB.locator("#result").textContent());
        } finally {
            browser.shutdown();
            SiteCredentialManager.getInstance().clearScopeBindings(scopeA);
            SiteCredentialManager.getInstance().clearScopeBindings(scopeB);
        }
    }

    @Test
    @Timeout(90)
    void multipleSavedAccountsAreSelectedAndBoundPerConversation() {
        SiteCredentialManager manager = SiteCredentialManager.getInstance();
        SiteCredential accountA = savedIdentity("测试账号 A", "user-a", "ACCOUNT_A");
        SiteCredential accountB = savedIdentity("测试账号 B", "user-b", "ACCOUNT_B");
        String scopeA = PlaywrightBrowserManager.conversationScopeId("profile-a");
        String scopeB = PlaywrightBrowserManager.conversationScopeId("profile-b");
        PlaywrightBrowserManager browser = newBrowser("multiple-accounts");
        try {
            browser.activateScope(scopeA);
            ToolConfirmationManager.setPort(new AccountChoicePort(accountA.getId()));
            String resultA = new PlaywrightBrowserTools(
                    browser, ToolCallOrigin.INTERACTIVE).navigate(baseUrl + "/identity");
            assertTrue(ToolResponse.isSuccess(resultA), resultA);
            assertEquals("ACCOUNT_A",
                    browser.getActivePage().locator("#result").textContent());
            assertEquals(accountA.getId(),
                    manager.findBoundByUrl(scopeA, baseUrl + "/identity").getId());

            browser.activateScope(scopeB);
            ToolConfirmationManager.setPort(new AccountChoicePort(accountB.getId()));
            String resultB = new PlaywrightBrowserTools(
                    browser, ToolCallOrigin.INTERACTIVE).navigate(baseUrl + "/identity");
            assertTrue(ToolResponse.isSuccess(resultB), resultB);
            assertEquals("ACCOUNT_B",
                    browser.getActivePage().locator("#result").textContent());
            assertEquals(accountB.getId(),
                    manager.findBoundByUrl(scopeB, baseUrl + "/identity").getId());

            browser.activateScope(scopeA);
            browser.getActivePage().navigate(baseUrl + "/identity");
            assertEquals("ACCOUNT_A",
                    browser.getActivePage().locator("#result").textContent(),
                    "会话 B 选择账号 B 后不得改写会话 A 的身份");
        } finally {
            browser.shutdown();
            manager.clearScopeBindings(scopeA);
            manager.clearScopeBindings(scopeB);
        }
    }

    private void assertUnattendedLoginIsDeferred(
            String browserName, ToolCallOrigin origin, String expectedHint) {
        PlaywrightBrowserManager browser = newBrowser(browserName);
        try {
            AutomatedLoginPort shouldNotPrompt = new AutomatedLoginPort(browser, true, true);
            ToolConfirmationManager.setPort(shouldNotPrompt);

            String result = new PlaywrightBrowserTools(browser, origin)
                    .navigate(baseUrl + "/private");

            assertTrue(ToolResponse.isSuccess(result), result);
            assertTrue(result.contains(expectedHint), result);
            assertEquals(0, shouldNotPrompt.loginPrompts.get());
            assertEquals(0, shouldNotPrompt.savePrompts.get());
        } finally {
            browser.shutdown();
        }
    }

    private PlaywrightBrowserManager newBrowser(String name) {
        return newBrowser(name, false);
    }

    private PlaywrightBrowserManager newBrowser(String name, boolean persistCookies) {
        return new PlaywrightBrowserManager(
                true,
                tempDir.resolve(name).resolve("browser"),
                tempDir.resolve(name).resolve("screenshots"),
                persistCookies);
    }

    private SiteCredential savedIdentity(String name, String username, String accountValue) {
        SiteCredential credential = new SiteCredential();
        credential.setName(name);
        credential.setHostPattern(TEST_HOST);
        credential.setLoginUrl(baseUrl + "/identity");
        credential.setUsername(username);
        credential.setPassword("");
        SiteCredentialManager manager = SiteCredentialManager.getInstance();
        manager.put(credential);
        assertTrue(manager.tryWriteSession(credential.getId(), """
                {
                  "cookies": [{
                    "name": "account",
                    "value": "%s",
                    "domain": "%s",
                    "path": "/",
                    "expires": -1,
                    "httpOnly": false,
                    "secure": false,
                    "sameSite": "Lax"
                  }],
                  "origins": []
                }
                """.formatted(accountValue, TEST_HOST)));
        return credential;
    }

    private static void removeTestSites() {
        SiteCredentialManager manager = SiteCredentialManager.getInstance();
        manager.clearScopeBindings("interactive:default");
        var ids = manager.all().stream()
                .filter(site -> TEST_HOST.equalsIgnoreCase(site.getHostPattern()))
                .map(SiteCredential::getId)
                .toList();
        for (String id : new ArrayList<>(ids)) {
            manager.remove(id);
        }
    }

    private static void clearWorkspaceBrowserState() throws Exception {
        try (Connection connection = AppDatabase.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "DELETE FROM browser_state WHERE workspace_id = ?")) {
            statement.setString(1, AppDatabase.currentWorkspaceId());
            statement.executeUpdate();
        }
    }

    private static String readWorkspaceBrowserState() throws Exception {
        try (Connection connection = AppDatabase.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT state_json FROM browser_state WHERE workspace_id = ?")) {
            statement.setString(1, AppDatabase.currentWorkspaceId());
            try (var resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getString("state_json") : null;
            }
        }
    }

    private static void handleRequest(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            if ("/private".equals(path)) {
                handlePrivate(exchange);
            } else if ("/login".equals(path)) {
                handleLogin(exchange);
            } else if ("/password-settings".equals(path)) {
                sendHtml(exchange, 200, """
                        <!doctype html>
                        <html lang="zh-CN">
                        <head><meta charset="utf-8"><title>密码设置</title></head>
                        <body>
                          <h1 id="result">PASSWORD_SETTINGS</h1>
                          <form>
                            <input name="new-password" type="password">
                            <button type="button">更新密码</button>
                          </form>
                          <a href="/logout">退出登录</a>
                        </body>
                        </html>
                        """);
            } else if ("/identity".equals(path)) {
                String account = cookieValue(exchange, "account");
                sendHtml(exchange, 200, """
                        <!doctype html>
                        <html lang="zh-CN">
                        <head><meta charset="utf-8"><title>身份</title></head>
                        <body><h1 id="result">%s</h1></body>
                        </html>
                        """.formatted(account == null ? "ANONYMOUS" : account));
            } else if ("/favicon.ico".equals(path)) {
                exchange.sendResponseHeaders(204, -1);
            } else {
                redirect(exchange, "/private");
            }
        } finally {
            exchange.close();
        }
    }

    private static String cookieValue(HttpExchange exchange, String name) {
        String header = exchange.getRequestHeaders().getFirst("Cookie");
        if (header == null) return null;
        for (String token : header.split(";")) {
            String[] pair = token.trim().split("=", 2);
            if (pair.length == 2 && name.equals(pair[0])) return pair[1];
        }
        return null;
    }

    private static void handlePrivate(HttpExchange exchange) throws IOException {
        if (!hasAuthenticatedCookie(exchange)) {
            redirect(exchange, "/login?next=%2Fprivate");
            return;
        }
        sendHtml(exchange, 200, """
                <!doctype html>
                <html lang="zh-CN">
                <head><meta charset="utf-8"><title>受保护页面</title></head>
                <body>
                <main id="app"></main>
                <script>
                  const app = document.getElementById('app');
                  if (localStorage.getItem('%s') === 'authenticated') {
                    document.title = '自动登录成功';
                    app.innerHTML = '<h1 id="result">AUTOMATIC_LOGIN_OK</h1>'
                      + '<button type="button">退出登录</button>';
                  } else {
                    document.title = '需要登录';
                    app.innerHTML = `%s`;
                  }
                </script>
                </body>
                </html>
                """.formatted(STORAGE_KEY, loginForm("localStorage 令牌缺失")));
    }

    private static void handleLogin(HttpExchange exchange) throws IOException {
        if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            String body = new String(
                    exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (body.contains("username=demo") && body.contains("password=secret")) {
                exchange.getResponseHeaders().add(
                        "Set-Cookie", SESSION_COOKIE + "; Path=/; HttpOnly; SameSite=Lax");
                sendHtml(exchange, 200, """
                        <!doctype html>
                        <html><head><meta charset="utf-8"><title>登录完成</title></head>
                        <body>
                        <script>
                          localStorage.setItem('%s', 'authenticated');
                          location.replace('/private');
                        </script>
                        </body></html>
                        """.formatted(STORAGE_KEY));
                return;
            }
            sendHtml(exchange, 401, pageWithForm("账号或密码错误"));
            return;
        }
        sendHtml(exchange, 200, pageWithForm(""));
    }

    private static boolean hasAuthenticatedCookie(HttpExchange exchange) {
        return exchange.getRequestHeaders().getOrDefault("Cookie", java.util.List.of()).stream()
                .anyMatch(value -> value.contains(SESSION_COOKIE));
    }

    private static String pageWithForm(String error) {
        return """
                <!doctype html>
                <html lang="zh-CN">
                <head><meta charset="utf-8"><title>站点登录</title></head>
                <body>%s</body>
                </html>
                """.formatted(loginForm(error));
    }

    private static String loginForm(String error) {
        return """
                <form method="post" action="/login">
                  <label>账号 <input id="username" name="username"
                    autocomplete="username" type="text"></label>
                  <label>密码 <input id="password" name="password"
                    autocomplete="current-password" type="password"></label>
                  <button id="submit" type="submit">登录</button>
                  <p id="error">%s</p>
                </form>
                """.formatted(error);
    }

    private static void redirect(HttpExchange exchange, String location) throws IOException {
        exchange.getResponseHeaders().add("Location", location);
        exchange.sendResponseHeaders(302, -1);
    }

    private static void sendHtml(HttpExchange exchange, int status, String html)
            throws IOException {
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set(
                "Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private static final class AutomatedLoginPort implements UserInteractionPort {

        private final PlaywrightBrowserManager browser;
        private final boolean validCredentials;
        private final boolean saveSession;
        private final AtomicInteger loginPrompts = new AtomicInteger();
        private final AtomicInteger savePrompts = new AtomicInteger();

        private AutomatedLoginPort(
                PlaywrightBrowserManager browser, boolean validCredentials, boolean saveSession) {
            this.browser = browser;
            this.validCredentials = validCredentials;
            this.saveSession = saveSession;
        }

        @Override
        public boolean confirm(ConfirmRequest request) {
            if ("完成站点登录".equals(request.toolName())) {
                loginPrompts.incrementAndGet();
                Page page = browser.getActivePage();
                page.fill("#username", validCredentials ? "demo" : "invalid");
                page.fill("#password", validCredentials ? "secret" : "wrong");
                page.click("#submit");
                if (validCredentials) {
                    page.waitForURL(
                            url -> URI.create(url).getPath().equals("/private"),
                            new Page.WaitForURLOptions()
                                    .setTimeout(Duration.ofSeconds(10).toMillis())
                                    .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
                }
                return true;
            }
            if ("保存站点".equals(request.toolName())) {
                savePrompts.incrementAndGet();
                return saveSession;
            }
            return true;
        }

        @Override
        public void notify(ToastRequest request) {
        }
    }

    private static final class LoginThenCancelPort implements UserInteractionPort {

        private final PlaywrightBrowserManager browser;
        private final AtomicInteger loginPrompts = new AtomicInteger();
        private final AtomicInteger savePrompts = new AtomicInteger();

        private LoginThenCancelPort(PlaywrightBrowserManager browser) {
            this.browser = browser;
        }

        @Override
        public boolean confirm(ConfirmRequest request) {
            if ("完成站点登录".equals(request.toolName())) {
                loginPrompts.incrementAndGet();
                Page page = browser.getActivePage();
                page.fill("#username", "demo");
                page.fill("#password", "secret");
                page.click("#submit");
                page.waitForURL(
                        url -> URI.create(url).getPath().equals("/private"),
                        new Page.WaitForURLOptions()
                                .setTimeout(Duration.ofSeconds(10).toMillis())
                                .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
                return false;
            }
            if ("保存站点".equals(request.toolName())) {
                savePrompts.incrementAndGet();
                return false;
            }
            return true;
        }

        @Override
        public void notify(ToastRequest request) {
        }
    }

    private static final class AccountChoicePort implements UserInteractionPort {
        private final String credentialId;

        private AccountChoicePort(String credentialId) {
            this.credentialId = credentialId;
        }

        @Override
        public boolean confirm(ConfirmRequest request) {
            return true;
        }

        @Override
        public String choose(ChoiceRequest request) {
            return credentialId;
        }

        @Override
        public void notify(ToastRequest request) {
        }
    }
}
