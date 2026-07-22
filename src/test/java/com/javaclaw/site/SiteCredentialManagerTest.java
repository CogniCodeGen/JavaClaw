package com.javaclaw.site;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SiteCredentialManagerTest {

    @Test
    void wildcardCredentialMatchesOnlySubdomains() {
        assertTrue(SiteCredentialManager.wildcardMatches("login.example.com", "example.com"));
        assertTrue(SiteCredentialManager.wildcardMatches("deep.login.example.com", "example.com"));
        assertFalse(SiteCredentialManager.wildcardMatches("example.com", "example.com"));
        assertFalse(SiteCredentialManager.wildcardMatches("notexample.com", "example.com"));
    }
}
