package com.javaclaw.browser;

import com.javaclaw.site.SiteCredential;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SiteLoginSupportTest {

    @Test
    void detectsAuthenticationResponseWithoutFormSignals() {
        SiteLoginSupport.LoginAssessment assessment = SiteLoginSupport.assess(
                new SiteLoginSupport.LoginSignals(
                        "https://example.com/private", 401,
                        false, false, false, false));

        assertTrue(assessment.loginRequired());
        assertTrue(assessment.reason().contains("HTTP 401"));
    }

    @Test
    void detectsLoginPageFromMultipleIndependentSignals() {
        SiteLoginSupport.LoginAssessment assessment = SiteLoginSupport.assess(
                new SiteLoginSupport.LoginSignals(
                        "https://example.com/auth/login?next=%2Fdashboard", 200,
                        true, true, true, false));

        assertTrue(assessment.loginRequired());
        assertTrue(assessment.score() >= 3);
    }

    @Test
    void doesNotInterruptOrdinaryPasswordSettingsPage() {
        SiteLoginSupport.LoginAssessment assessment = SiteLoginSupport.assess(
                new SiteLoginSupport.LoginSignals(
                        "https://example.com/settings/security", 200,
                        true, false, false, true));

        assertFalse(assessment.loginRequired());
    }

    @Test
    void loginUrlDetectionUsesPathAndQueryRatherThanHostname() {
        assertTrue(SiteLoginSupport.looksLikeLoginUrl("https://example.com/oauth2/authorize?client=x"));
        assertTrue(SiteLoginSupport.looksLikeLoginUrl("https://example.com/sign-in"));
        assertFalse(SiteLoginSupport.looksLikeLoginUrl("https://login.example.com/dashboard"));
    }

    @Test
    void createsSessionOnlySiteFromTargetHost() {
        SiteCredential site = SiteLoginSupport.newSessionSite(
                "https://app.example.com/dashboard",
                "https://id.example.net/sso/login");

        assertEquals("app.example.com", site.getName());
        assertEquals("app.example.com", site.getHostPattern());
        assertEquals("https://id.example.net/sso/login", site.getLoginUrl());
        assertEquals("", site.getUsername());
        assertEquals("", site.getPassword());
        assertFalse(site.isHasSession());
    }

    @Test
    void rejectsUrlsWithoutAHost() {
        assertNull(SiteLoginSupport.hostOf("about:blank"));
    }

    @Test
    void filtersStorageStateToTheSelectedSite() {
        String filtered = SiteLoginSupport.filterStorageStateForUrl("""
                {
                  "cookies": [
                    {"name":"app","value":"a","domain":".example.com","path":"/"},
                    {"name":"other","value":"b","domain":"other.test","path":"/"}
                  ],
                  "origins": [
                    {"origin":"https://app.example.com","localStorage":[{"name":"token","value":"a"}]},
                    {"origin":"https://other.test","localStorage":[{"name":"token","value":"b"}]}
                  ]
                }
                """, "https://app.example.com/dashboard");

        assertTrue(filtered.contains("\"app\""));
        assertTrue(filtered.contains("app.example.com"));
        assertFalse(filtered.contains("\"other\""));
        assertFalse(filtered.contains("other.test"));
    }
}
