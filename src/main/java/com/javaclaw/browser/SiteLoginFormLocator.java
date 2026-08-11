package com.javaclaw.browser;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;

/** Locates common login form controls without exposing credential values. */
final class SiteLoginFormLocator {

    private static final String[] USERNAME_CANDIDATES = {
        "input[autocomplete='username']",
        "input[name='username']",
        "input[name='email']",
        "input[type='email']",
        "input[id*='user' i]",
        "input[id*='email' i]",
        "input[name*='login' i]",
        "input[name*='account' i]"
    };

    private static final String[] SUBMIT_CANDIDATES = {
        "button[type='submit']",
        "input[type='submit']",
        "button:has-text('登 录')",
        "button:has-text('登录')",
        "button:has-text('Sign in')",
        "button:has-text('Log in')",
        "button:has-text('Login')"
    };

    private SiteLoginFormLocator() {}

    static String usernameSelector(Page page) {
        return firstPresent(page, USERNAME_CANDIDATES);
    }

    static String submitSelector(Page page) {
        return firstPresent(page, SUBMIT_CANDIDATES);
    }

    private static String firstPresent(Page page, String[] candidates) {
        for (String selector : candidates) {
            try {
                if (page.locator(selector).count() > 0) return selector;
            } catch (PlaywrightException unsupportedSelector) {
                // A selector unsupported by one page must not suppress the remaining heuristics.
            }
        }
        return null;
    }
}
