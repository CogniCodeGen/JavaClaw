package com.javaclaw.browser;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.platform.json.JsonCodec;
import com.javaclaw.site.SiteCredential;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SiteLoginSupportTest {

    private static final JsonCodec JSON = new JsonCodec(new ObjectMapper());

    @Test
    void detectsAuthenticationResponseWithoutFormSignals() {
        SiteLoginSupport.LoginAssessment assessment = SiteLoginSupport.assess(
                new SiteLoginSupport.LoginSignals(
                        401, false, false));

        assertTrue(assessment.loginRequired());
        assertTrue(assessment.reason().contains("HTTP 401"));
    }

    @Test
    void detectsLoginPageFromMultipleIndependentSignals() {
        SiteLoginSupport.LoginAssessment assessment = SiteLoginSupport.assess(
                new SiteLoginSupport.LoginSignals(
                        200, true, false));

        assertTrue(assessment.loginRequired());
        assertTrue(assessment.evidence().contains(SiteLoginSupport.LoginEvidence.CREDENTIAL_FORM));
    }

    @Test
    void doesNotInterruptOrdinaryPasswordSettingsPage() {
        SiteLoginSupport.LoginAssessment assessment = SiteLoginSupport.assess(
                new SiteLoginSupport.LoginSignals(
                        200, false, false));

        assertFalse(assessment.loginRequired());
    }

    @Test
    void pageAddressAloneDoesNotRequireLogin() {
        SiteLoginSupport.LoginAssessment assessment = SiteLoginSupport.assess(
                new SiteLoginSupport.LoginSignals(200, false, false));
        assertFalse(assessment.loginRequired());
        assertFalse(SiteLoginSupport.assess(
                new SiteLoginSupport.LoginSignals(403, false, false)).loginRequired(),
                "a bare 403 may be authorization denial after login");
    }

    @Test
    void loginVerificationRequiresTargetResponseAndSessionTransition() {
        var clear = new SiteLoginSupport.LoginSignals(200, false, false);
        assertEquals(SiteLoginSupport.VerificationStatus.AUTHENTICATED,
                SiteLoginSupport.verifyLogin(clear, true,
                        "https://app.example.com/private", "https://app.example.com/private", true));
        assertEquals(SiteLoginSupport.VerificationStatus.UNVERIFIED,
                SiteLoginSupport.verifyLogin(clear, true,
                        "https://app.example.com/public", "https://app.example.com/public", false),
                "a public page setting a cookie does not prove login");
        assertEquals(SiteLoginSupport.VerificationStatus.UNVERIFIED,
                SiteLoginSupport.verifyLogin(clear, false,
                        "https://app.example.com/private", "https://app.example.com/private", true));
        assertEquals(SiteLoginSupport.VerificationStatus.UNVERIFIED,
                SiteLoginSupport.verifyLogin(clear, true,
                        "https://app.example.com/private", "https://id.example.net/done", true));
        assertEquals(SiteLoginSupport.VerificationStatus.UNVERIFIED,
                SiteLoginSupport.verifyLogin(new SiteLoginSupport.LoginSignals(500, false, false), true,
                        "https://app.example.com/private", "https://app.example.com/private", true));
        assertEquals(SiteLoginSupport.VerificationStatus.LOGIN_REQUIRED,
                SiteLoginSupport.verifyLogin(new SiteLoginSupport.LoginSignals(200, true, false), true,
                        "https://app.example.com/private", "https://app.example.com/private", true));
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
                    {"name":"parentHostOnly","value":"secret","domain":"example.com","path":"/"},
                    {"name":"other","value":"b","domain":"other.test","path":"/"}
                  ],
                  "origins": [
                    {"origin":"https://app.example.com","localStorage":[{"name":"token","value":"a"}]},
                    {"origin":"https://child.app.example.com","localStorage":[{"name":"child","value":"secret"}]},
                    {"origin":"http://app.example.com","localStorage":[{"name":"insecure","value":"secret"}]},
                    {"origin":"https://other.test","localStorage":[{"name":"token","value":"b"}]}
                  ]
                }
                """, "https://app.example.com/dashboard", JSON);

        assertTrue(filtered.contains("\"app\""));
        assertTrue(filtered.contains("app.example.com"));
        assertFalse(filtered.contains("\"other\""));
        assertFalse(filtered.contains("other.test"));
        assertFalse(filtered.contains("parentHostOnly"));
        assertFalse(filtered.contains("\"child\""));
        assertFalse(filtered.contains("insecure"));
    }
}
