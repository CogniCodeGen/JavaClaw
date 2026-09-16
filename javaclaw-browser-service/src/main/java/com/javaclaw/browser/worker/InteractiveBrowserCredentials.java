package com.javaclaw.browser.worker;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;

import com.microsoft.playwright.ElementHandle;
import com.microsoft.playwright.Page;

import com.javaclaw.api.PrivateNetworkGrant;
import com.javaclaw.browser.protocol.InteractiveBrowserProtocol.CredentialsRequest;

/** 凭据只在私有帧与同一次页面脚本任务中使用；精确 Origin、document 和 form 检查与读写不可分离。 */
final class InteractiveBrowserCredentials {
    private static final String CHECK = """
            const user = args.user;
            if (location.origin !== args.origin || password.ownerDocument !== document
                || user.ownerDocument !== document || password.form !== user.form
                || !(password instanceof HTMLInputElement) || password.type !== 'password'
                || !(user instanceof HTMLInputElement) || !['text','email','tel'].includes(user.type)) {
              throw new Error('BROWSER_CREDENTIAL_TARGET_CHANGED');
            }
            """;
    private static final String CAPTURE = "(password, args) => {" + CHECK + """
            return user.value + '\\u0000' + password.value;
            }
            """;
    private static final String FILL = "(password, args) => {" + CHECK + """
            if (user.disabled || user.readOnly || password.disabled || password.readOnly) {
              throw new Error('BROWSER_CREDENTIAL_TARGET_CHANGED');
            }
            const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set;
            setter.call(user, args.username);
            setter.call(password, args.secret);
            for (const input of [user, password]) {
              input.dispatchEvent(new Event('input', {bubbles:true}));
              input.dispatchEvent(new Event('change', {bubbles:true}));
            }
            }
            """;

    private InteractiveBrowserCredentials() {}

    static byte[] capture(Page page, InteractiveBrowserPages pages, CredentialsRequest request) {
        ElementHandle username = pages.reference(page, request.target().usernameRef());
        ElementHandle password = pages.reference(page, request.target().passwordRef());
        String pair = (String)
                password.evaluate(CAPTURE, Map.of("user", username, "origin", origin(request.expectedOrigin())));
        byte[] bytes = pair.getBytes(StandardCharsets.UTF_8);
        try {
            remember(pages, bytes);
            return bytes;
        } catch (RuntimeException failure) {
            Arrays.fill(bytes, (byte) 0);
            throw failure;
        }
    }

    static void fill(Page page, InteractiveBrowserPages pages, CredentialsRequest request, byte[] bytes) {
        String[] pair = remember(pages, bytes);
        ElementHandle username = pages.reference(page, request.target().usernameRef());
        ElementHandle password = pages.reference(page, request.target().passwordRef());
        password.evaluate(
                FILL,
                Map.of(
                        "user",
                        username,
                        "origin",
                        origin(request.expectedOrigin()),
                        "username",
                        pair[0],
                        "secret",
                        pair[1]));
    }

    static String origin(URI value) {
        return PrivateNetworkGrant.normalizeOrigin(value).toString();
    }

    private static String[] remember(InteractiveBrowserPages pages, byte[] bytes) {
        String pair = new String(bytes, StandardCharsets.UTF_8);
        int separator = pair.indexOf('\0');
        if (bytes.length > 65_536
                || separator < 1
                || separator == pair.length() - 1
                || pair.indexOf('\0', separator + 1) >= 0) {
            throw new IllegalArgumentException("Browser credentials are invalid");
        }
        String username = pair.substring(0, separator);
        String secret = pair.substring(separator + 1);
        pages.secret(username);
        pages.secret(secret);
        return new String[] {username, secret};
    }
}
