package com.javaclaw.browser.worker;

import java.net.URI;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BrowserNetworkPolicyTest {
    private static final Set<URI> ORIGINS = Set.of(URI.create("https://docs.example.com:8443"));

    @Test
    void exactTlsOriginAllowsPageResources() {
        assertDoesNotThrow(() ->
                BrowserNetworkPolicy.requireFinalPage(URI.create("https://docs.example.com:8443/final"), ORIGINS));
        assertDoesNotThrow(
                () -> BrowserNetworkPolicy.requireBrokerTarget("https://docs.example.com:8443/image.png", ORIGINS));
    }

    @Test
    void cleartextCrossOriginAndDifferentPortAreRejected() {
        assertThrows(
                IllegalStateException.class,
                () -> BrowserNetworkPolicy.requireBrokerTarget("http://docs.example.com:8443/page", ORIGINS));
        assertThrows(
                IllegalStateException.class,
                () -> BrowserNetworkPolicy.requireBrokerTarget("https://other.example.com:8443/page", ORIGINS));
        assertThrows(
                IllegalStateException.class,
                () -> BrowserNetworkPolicy.requireBrokerTarget("https://docs.example.com/page", ORIGINS));
        assertThrows(
                IllegalStateException.class,
                () -> BrowserNetworkPolicy.requireBrokerTarget("wss://docs.example.com:8443/events", ORIGINS));
        assertThrows(
                IllegalStateException.class,
                () -> BrowserNetworkPolicy.requireFinalPage(URI.create("https://other.example.com"), ORIGINS));
    }

    @Test
    void malformedRelativeCredentialFragmentAndPortTargetsAreRejected() {
        assertThrows(
                IllegalStateException.class,
                () -> BrowserNetworkPolicy.requireFinalPage(URI.create("http://docs.example.com:8443"), ORIGINS));
        assertThrows(IllegalStateException.class, () -> BrowserNetworkPolicy.requireBrokerTarget("/relative", ORIGINS));
        assertThrows(
                IllegalStateException.class, () -> BrowserNetworkPolicy.requireBrokerTarget("https:opaque", ORIGINS));
        assertThrows(
                IllegalStateException.class,
                () -> BrowserNetworkPolicy.requireBrokerTarget("https://user@docs.example.com:8443/resource", ORIGINS));
        assertThrows(
                IllegalStateException.class,
                () -> BrowserNetworkPolicy.requireBrokerTarget(
                        "https://docs.example.com:8443/resource#fragment", ORIGINS));
        assertThrows(
                IllegalStateException.class,
                () -> BrowserNetworkPolicy.requireBrokerTarget("https://docs.example.com:0/resource", ORIGINS));
        assertThrows(
                IllegalStateException.class,
                () -> BrowserNetworkPolicy.requireBrokerTarget("https://docs.example.com:65536/resource", ORIGINS));
        assertThrows(
                IllegalStateException.class,
                () -> BrowserNetworkPolicy.requireBrokerTarget("https://[invalid", ORIGINS));
    }
}
