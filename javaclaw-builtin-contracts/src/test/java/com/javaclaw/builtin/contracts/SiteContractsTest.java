package com.javaclaw.builtin.contracts;

import java.lang.reflect.ParameterizedType;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.CredentialMetadata;
import com.javaclaw.api.CredentialRef;
import com.javaclaw.api.PrivateNetworkGrantRef;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SiteContractsTest {
    @Test
    void publicSiteResultsExposeOnlyProjectionAndBooleanAuthorityStatus() {
        Set<Class<?>> forbidden = Set.of(
                CredentialRef.class,
                PrivateNetworkGrantRef.class,
                SiteContracts.Site.class,
                SiteContracts.SiteCredential.class);
        assertTrue(java.util.Arrays.stream(SiteContracts.Projection.class.getRecordComponents())
                .noneMatch(component -> forbidden.contains(component.getType())));
        assertEquals(
                SiteContracts.Projection.class, SiteContracts.LoginSaveResult.class.getRecordComponents()[0].getType());

        ParameterizedType matches =
                (ParameterizedType) SiteContracts.SearchResult.class.getRecordComponents()[0].getGenericType();
        assertEquals(SiteContracts.Projection.class, matches.getActualTypeArguments()[0]);
        assertFalse(java.util.Arrays.stream(SiteContracts.LoginSaveResult.class.getRecordComponents())
                .anyMatch(component -> forbidden.contains(component.getType())));
    }

    @Test
    void managementWriteContractCannotCarryAuthorityReferencesOrUnboundedRows() {
        assertEquals(
                Set.of("id", "name", "origin", "allowedOrigins", "enabled"),
                java.util.Arrays.stream(SiteManagementContracts.SaveRequest.class.getRecordComponents())
                        .map(java.lang.reflect.RecordComponent::getName)
                        .collect(java.util.stream.Collectors.toUnmodifiableSet()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SiteManagementContracts.SaveRequest(
                        "site",
                        "Site",
                        URI.create("https://example.com"),
                        List.of(
                                new SiteManagementContracts.AllowedOrigin("same", URI.create("https://example.com")),
                                new SiteManagementContracts.AllowedOrigin(
                                        "same", URI.create("https://cdn.example.com"))),
                        true));
        List<SiteManagementContracts.AllowedOrigin> tooMany = java.util.stream.IntStream.rangeClosed(0, 100)
                .mapToObj(index -> new SiteManagementContracts.AllowedOrigin(
                        "origin-" + index, URI.create("https://host-" + index + ".example.com")))
                .toList();
        assertThrows(
                IllegalArgumentException.class,
                () -> new SiteManagementContracts.SaveRequest(
                        "site", "Site", URI.create("https://host-0.example.com"), tooMany, true));
    }

    @Test
    void siteNormalizesExactOriginsAndSearchResults() {
        SiteContracts.Site site = site(
                URI.create("https://Example.COM"),
                Set.of(URI.create("https://EXAMPLE.com"), URI.create("https://docs.example.com")));
        SiteContracts.SearchRequest request = new SiteContracts.SearchRequest("docs", 100);
        SiteContracts.Projection projection = SiteContracts.Projection.from(site);
        SiteContracts.SearchResult result = new SiteContracts.SearchResult(List.of(projection));

        assertEquals(URI.create("https://example.com"), site.origin());
        assertEquals(
                Set.of(URI.create("https://example.com"), URI.create("https://docs.example.com")),
                site.allowedOrigins());
        assertEquals(100, request.limit());
        assertEquals(projection, result.matches().getFirst());
    }

    @Test
    void siteRejectsUnsafeOrNonOriginAuthorities() {
        assertInvalidSite("http://example.com", Set.of(URI.create("http://example.com")));
        assertInvalidSite("https://user@example.com", Set.of(URI.create("https://example.com")));
        assertInvalidSite("https:///relative", Set.of(URI.create("https://example.com")));
        assertInvalidSite("https://example.com/docs", Set.of(URI.create("https://example.com")));
        assertInvalidSite("https://example.com", Set.of(URI.create("https://other.example")));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SiteContracts.Site(
                        "site",
                        1,
                        2,
                        "Docs",
                        URI.create("https://example.com"),
                        Set.of(URI.create("https://example.com")),
                        SiteContracts.SiteCredential.none(),
                        Optional.empty(),
                        true,
                        BuiltinContractsFixtures.NOW));
    }

    @Test
    void credentialBindingNeverContainsSecretAndRequiresPurposeNamespace() {
        SiteContracts.SiteCredential bearer = new SiteContracts.SiteCredential(
                SiteContracts.CredentialKind.BEARER,
                Optional.of(new CredentialRef(SiteContracts.SITE_CREDENTIAL_NAMESPACE, "opaque")),
                Optional.empty());
        SiteContracts.SiteCredential apiKey = new SiteContracts.SiteCredential(
                SiteContracts.CredentialKind.API_KEY_HEADER,
                Optional.of(new CredentialRef(SiteContracts.SITE_CREDENTIAL_NAMESPACE, "opaque")),
                Optional.of(" X-Api-Key "));

        assertEquals("x-api-key", apiKey.apiKeyHeader().orElseThrow());
        assertEquals(SiteContracts.CredentialKind.BEARER, bearer.kind());
        assertThrows(
                IllegalArgumentException.class,
                () -> new SiteContracts.SiteCredential(
                        SiteContracts.CredentialKind.BROWSER_STORAGE,
                        Optional.of(new CredentialRef(SiteContracts.SITE_CREDENTIAL_NAMESPACE, "opaque")),
                        Optional.empty()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SiteContracts.SiteCredential(
                        SiteContracts.CredentialKind.API_KEY_HEADER,
                        Optional.of(new CredentialRef(SiteContracts.SITE_CREDENTIAL_NAMESPACE, "opaque")),
                        Optional.of("cookie")));
    }

    @Test
    void snapshotRequestAllowsOnlyBoundedHttpsUri() {
        SiteContracts.SnapshotRequest request =
                new SiteContracts.SnapshotRequest("site", 1, 1, URI.create("https://example.com:443/docs"), 200_000);
        SiteContracts.PageSnapshot snapshot = new SiteContracts.PageSnapshot(
                URI.create("https://example.com/final"), " Title ", "text", BuiltinContractsFixtures.NOW);

        assertEquals(443, request.uri().getPort());
        assertEquals("Title", snapshot.title());
        assertInvalidSnapshot("http://example.com", 10);
        assertInvalidSnapshot("https://user@example.com", 10);
        assertEquals(
                444,
                new SiteContracts.SnapshotRequest("site", 1, 1, URI.create("https://example.com:444"), 10)
                        .uri()
                        .getPort());
        assertInvalidSnapshot("https://example.com", 0);
        assertInvalidSnapshot("https://example.com", 200_001);
    }

    @Test
    void snapshotTaskFreezesOriginAuthorityAndTimeCeilings() {
        SiteContracts.Site site = site(URI.create("https://example.com"), Set.of(URI.create("https://example.com")));
        SiteContracts.SnapshotTask task =
                new SiteContracts.SnapshotTask(site, URI.create("https://example.com/page"), 1, Duration.ofSeconds(60));

        assertEquals(Set.of(URI.create("https://example.com")), task.site().allowedOrigins());
        assertThrows(
                IllegalArgumentException.class,
                () -> new SiteContracts.SnapshotTask(
                        site, URI.create("https://other.example"), 1, Duration.ofSeconds(1)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SiteContracts.SnapshotTask(
                        site, URI.create("https://example.com"), 0, Duration.ofSeconds(1)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SiteContracts.SnapshotTask(
                        site, URI.create("https://example.com"), 1, Duration.ofMillis(999)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SiteContracts.SnapshotTask(
                        site, URI.create("https://example.com"), 1, Duration.ofSeconds(61)));
    }

    @Test
    void loginContractsEnforceUuidTenMinuteLimitAndOpaqueCredential() {
        SiteContracts.Site site = site(URI.create("https://example.com"), Set.of(URI.create("https://example.com")));
        String sessionId = java.util.UUID.randomUUID().toString();
        SiteContracts.LoginBeginTask task = new SiteContracts.LoginBeginTask(site, sessionId, Duration.ofMinutes(10));
        SiteContracts.LoginSession session = new SiteContracts.LoginSession(
                sessionId,
                site.id(),
                site.revision(),
                site.authorityRevision(),
                SiteContracts.LoginSessionState.SAVED,
                BuiltinContractsFixtures.NOW,
                BuiltinContractsFixtures.NOW.plus(Duration.ofMinutes(10)),
                Optional.empty());
        CredentialMetadata metadata = new CredentialMetadata(
                new CredentialRef(SiteContracts.BROWSER_CREDENTIAL_NAMESPACE, "opaque"),
                1,
                BuiltinContractsFixtures.NOW);
        SiteContracts.Site savedSite = new SiteContracts.Site(
                site.id(),
                2,
                2,
                site.name(),
                site.origin(),
                site.allowedOrigins(),
                new SiteContracts.SiteCredential(
                        SiteContracts.CredentialKind.BROWSER_STORAGE,
                        Optional.of(metadata.reference()),
                        Optional.empty()),
                Optional.empty(),
                true,
                BuiltinContractsFixtures.NOW);

        assertEquals(sessionId, task.sessionId());
        assertEquals(savedSite, new SiteContracts.LoginSaveCommit(savedSite, metadata, session).site());
        SiteContracts.LoginSaveResult publicResult =
                new SiteContracts.LoginSaveResult(SiteContracts.Projection.from(savedSite), true, session);
        assertEquals(savedSite.id(), publicResult.site().id());
        assertTrue(publicResult.credentialConfigured());
        assertThrows(
                IllegalArgumentException.class,
                () -> new SiteContracts.LoginBeginTask(
                        site, sessionId, Duration.ofMinutes(10).plusMillis(1)));
        assertThrows(IllegalArgumentException.class, () -> new SiteContracts.LoginControlRequest("../not-a-session"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SiteContracts.LoginSaveTask(sessionId, "save", "not-a-digest"));
    }

    private static SiteContracts.Site site(URI origin, Set<URI> allowedOrigins) {
        return new SiteContracts.Site(
                "site",
                1,
                1,
                "Docs",
                origin,
                allowedOrigins,
                SiteContracts.SiteCredential.none(),
                Optional.empty(),
                true,
                BuiltinContractsFixtures.NOW);
    }

    private static void assertInvalidSite(String uri, Set<URI> allowedOrigins) {
        assertThrows(IllegalArgumentException.class, () -> site(URI.create(uri), allowedOrigins));
    }

    private static void assertInvalidSnapshot(String uri, int maximumCharacters) {
        assertThrows(
                IllegalArgumentException.class,
                () -> new SiteContracts.SnapshotRequest("site", 1, 1, URI.create(uri), maximumCharacters));
    }
}
