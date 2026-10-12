package com.javaclaw.browser;

import com.microsoft.playwright.options.Cookie;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CookieUrlMatcherTest {
    @Test
    void matchesDomainPathAndSecureAttributesWithoutInspectingQueryText() {
        Cookie shared = new Cookie("session", "value")
                .setDomain(".example.com").setPath("/app").setSecure(true);
        assertTrue(CookieUrlMatcher.matches(shared,
                CookieUrlMatcher.requireHttpUrl("https://team.example.com/app/page")));
        assertFalse(CookieUrlMatcher.matches(shared,
                CookieUrlMatcher.requireHttpUrl("https://notexample.com/app/page?next=example.com")));
        assertFalse(CookieUrlMatcher.matches(shared,
                CookieUrlMatcher.requireHttpUrl("https://team.example.com/application")));
        assertFalse(CookieUrlMatcher.matches(shared,
                CookieUrlMatcher.requireHttpUrl("http://team.example.com/app")));
    }

    @Test
    void hostOnlyCookieDoesNotLeakToSubdomains() {
        Cookie hostOnly = new Cookie("session", "value")
                .setDomain("example.com").setPath("/");
        assertTrue(CookieUrlMatcher.matches(hostOnly,
                CookieUrlMatcher.requireHttpUrl("https://example.com/")));
        assertFalse(CookieUrlMatcher.matches(hostOnly,
                CookieUrlMatcher.requireHttpUrl("https://sub.example.com/")));
        assertThrows(IllegalArgumentException.class,
                () -> CookieUrlMatcher.requireHttpUrl("example.com/path"));
    }
}
