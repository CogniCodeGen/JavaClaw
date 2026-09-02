package com.javaclaw.builtin.extensions;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.CredentialMetadata;
import com.javaclaw.api.CredentialRef;
import com.javaclaw.api.PrivateNetworkGrant;
import com.javaclaw.api.PrivateNetworkGrantRef;
import com.javaclaw.api.PrivateNetworkPurpose;
import com.javaclaw.api.SecurityGrantState;
import com.javaclaw.builtin.contracts.DocumentContracts;
import com.javaclaw.builtin.contracts.SiteContracts;
import com.javaclaw.builtin.contracts.SiteManagementContracts;
import com.javaclaw.extension.spi.ExtensionContributions;
import com.javaclaw.extension.spi.ViewQueryRequest;
import com.javaclaw.extension.spi.ViewQueryResult;
import com.javaclaw.extension.spi.ViewSchema;

import static com.javaclaw.builtin.extensions.BuiltinExtensionTestSupport.NOW;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SiteManagementTest {
    private static final URI OLD_ORIGIN = URI.create("https://old.example.com");
    private static final URI NEW_ORIGIN = URI.create("https://new.example.com");
    private static final URI CDN_ORIGIN = URI.create("https://cdn.example.com");
    private static final CredentialRef CREDENTIAL =
            new CredentialRef(SiteContracts.SITE_CREDENTIAL_NAMESPACE, "credential-secret");
    private static final PrivateNetworkGrantRef GRANT = new PrivateNetworkGrantRef("private-grant", 7);

    @Test
    void createStartsWithoutCredentialOrPrivateNetworkGrant() throws Exception {
        BuiltinExtensionTestSupport support = new BuiltinExtensionTestSupport();
        SiteExtension bundle = new SiteExtension();
        var started = support.start(bundle);

        SiteContracts.Projection projection = support.decode(
                started.command(support.request(
                        "site/create", input("fresh", "Fresh", OLD_ORIGIN), Optional.of("create-fresh"), 0)),
                SiteContracts.Projection.class);
        SiteContracts.Site stored = stored(support, "fresh");
        assertEquals(1, projection.revision());
        assertEquals(1, projection.authorityRevision());
        assertFalse(projection.hasCredential());
        assertFalse(projection.hasPrivateGrant());
        assertEquals(SiteContracts.SiteCredential.none(), stored.credential());
        assertTrue(stored.privateNetworkGrant().isEmpty());
    }

    @Test
    void originChangeClearsReferencesIncrementsAuthorityAndOnlyInvalidatesSessions() throws Exception {
        BuiltinExtensionTestSupport support = new BuiltinExtensionTestSupport();
        SiteExtension bundle = new SiteExtension();
        var started = support.start(bundle);
        seed(support, secureSite("secure", OLD_ORIGIN));
        List<String> services = new ArrayList<>();
        support.service = (caller, serviceId, request) -> {
            services.add(serviceId);
            return support.payloads.encode(Map.of());
        };

        SiteContracts.Projection projection = support.decode(
                started.command(support.request(
                        "site/update", input("secure", "Secure", NEW_ORIGIN), Optional.of("move-site"), 1)),
                SiteContracts.Projection.class);
        SiteContracts.Site stored = stored(support, "secure");
        assertEquals(2, projection.revision());
        assertEquals(2, projection.authorityRevision());
        assertFalse(projection.hasCredential());
        assertFalse(projection.hasPrivateGrant());
        assertEquals(SiteContracts.SiteCredential.none(), stored.credential());
        assertTrue(stored.privateNetworkGrant().isEmpty());
        assertEquals(List.of(SiteContracts.BROWSER_INVALIDATE_SERVICE), services);
        assertFalse(services.contains(SiteContracts.BROWSER_SNAPSHOT_SERVICE));
        assertFalse(services.contains(SiteContracts.BROWSER_LOGIN_BEGIN_SERVICE));

        assertThrows(
                IllegalArgumentException.class,
                () -> started.command(support.request(
                        "site/update", input("secure", "Stale", NEW_ORIGIN), Optional.of("stale-site"), 1)));
    }

    @Test
    void displayOnlyEditPreservesReferencesButRequestCannotInjectAnotherSiteReference() throws Exception {
        BuiltinExtensionTestSupport support = new BuiltinExtensionTestSupport();
        SiteExtension bundle = new SiteExtension();
        var started = support.start(bundle);
        seed(support, secureSite("stable", OLD_ORIGIN));

        SiteContracts.Projection projection = support.decode(
                started.command(support.request(
                        "site/update", input("stable", "Renamed", OLD_ORIGIN), Optional.of("rename-site"), 1)),
                SiteContracts.Projection.class);
        SiteContracts.Site stored = stored(support, "stable");
        assertEquals(1, projection.authorityRevision());
        assertEquals(CREDENTIAL, stored.credential().reference().orElseThrow());
        assertEquals(GRANT, stored.privateNetworkGrant().orElseThrow());

        Map<?, ?> decoded =
                support.payloads.decode(support.payloads.encode(input("stable", "Forged", OLD_ORIGIN)), Map.class);
        Map<Object, Object> forged = new LinkedHashMap<>(decoded);
        forged.put("credential", Map.of("namespace", "site", "id", "other-site-secret"));
        assertThrows(
                RuntimeException.class,
                () -> started.command(support.request("site/update", forged, Optional.of("forge-site-reference"), 2)));
        assertEquals(
                CREDENTIAL, stored(support, "stable").credential().reference().orElseThrow());
    }

    @Test
    void allowedOriginChangeClearsExistingAuthorityReferences() throws Exception {
        BuiltinExtensionTestSupport support = new BuiltinExtensionTestSupport();
        SiteExtension bundle = new SiteExtension();
        var started = support.start(bundle);
        seed(support, secureSite("allowed", OLD_ORIGIN));

        SiteManagementContracts.SaveRequest changed = new SiteManagementContracts.SaveRequest(
                "allowed",
                "Allowed",
                OLD_ORIGIN,
                List.of(
                        new SiteManagementContracts.AllowedOrigin("primary", OLD_ORIGIN),
                        new SiteManagementContracts.AllowedOrigin("cdn", CDN_ORIGIN)),
                true);
        SiteContracts.Projection projection = support.decode(
                started.command(support.request("site/update", changed, Optional.of("change-allowlist"), 1)),
                SiteContracts.Projection.class);
        SiteContracts.Site stored = stored(support, "allowed");
        assertEquals(2, projection.authorityRevision());
        assertFalse(projection.hasCredential());
        assertFalse(projection.hasPrivateGrant());
        assertEquals(Set.of(OLD_ORIGIN, CDN_ORIGIN), stored.allowedOrigins());
    }

    @Test
    void credentialAndPrivateNetworkBindingsUseExactAuthorityRevisions() throws Exception {
        BuiltinExtensionTestSupport support = new BuiltinExtensionTestSupport();
        SiteExtension bundle = new SiteExtension();
        var started = support.start(bundle);
        create(started, support, "authority");
        support.claimCredential(new CredentialMetadata(CREDENTIAL, 3, NOW));
        PrivateNetworkGrant grant = activeGrant(support, "site-network", 4, OLD_ORIGIN);
        support.claimPrivateNetworkGrant(grant);

        SiteContracts.Projection credentialBound = support.decode(
                started.command(support.request(
                        "site/credential/bind",
                        new SiteManagementContracts.CredentialBindRequest(
                                "authority", 1, SiteContracts.CredentialKind.BEARER, CREDENTIAL.id(), Optional.empty()),
                        Optional.of("bind-site-credential"),
                        1)),
                SiteContracts.Projection.class);
        assertEquals(2, credentialBound.revision());
        assertEquals(2, credentialBound.authorityRevision());
        assertTrue(credentialBound.hasCredential());

        SiteContracts.Projection grantBound = support.decode(
                started.command(support.request(
                        "site/privateNetwork/bind",
                        new SiteManagementContracts.PrivateNetworkBindRequest("authority", 2, grant.id()),
                        Optional.of("bind-site-network"),
                        2)),
                SiteContracts.Projection.class);
        assertEquals(3, grantBound.revision());
        assertEquals(3, grantBound.authorityRevision());
        assertTrue(grantBound.hasPrivateGrant());

        assertThrows(
                IllegalArgumentException.class,
                () -> started.command(support.request(
                        "site/credential/clear",
                        new SiteManagementContracts.AuthorityClearRequest("authority", 2),
                        Optional.of("stale-site-authority"),
                        3)));
        SiteContracts.Projection credentialCleared = support.decode(
                started.command(support.request(
                        "site/credential/clear",
                        new SiteManagementContracts.AuthorityClearRequest("authority", 3),
                        Optional.of("clear-site-credential"),
                        3)),
                SiteContracts.Projection.class);
        assertFalse(credentialCleared.hasCredential());
        assertTrue(credentialCleared.hasPrivateGrant());

        SiteContracts.Projection grantCleared = support.decode(
                started.command(support.request(
                        "site/privateNetwork/clear",
                        new SiteManagementContracts.AuthorityClearRequest("authority", 4),
                        Optional.of("clear-site-network"),
                        4)),
                SiteContracts.Projection.class);
        assertFalse(grantCleared.hasPrivateGrant());
        assertEquals(5, grantCleared.authorityRevision());
    }

    @Test
    void managementOptionsExposeOnlyRedactedMetadata() throws Exception {
        BuiltinExtensionTestSupport support = new BuiltinExtensionTestSupport();
        SiteExtension bundle = new SiteExtension();
        var started = support.start(bundle);
        support.claimCredential(new CredentialMetadata(CREDENTIAL, 3, NOW));
        support.claimPrivateNetworkGrant(activeGrant(support, "site-network", 4, OLD_ORIGIN));

        ViewQueryResult credentials = support.decode(
                started.query(support.request(
                        "site/view.credentials",
                        new ViewQueryRequest("siteCredentials", Map.of(), "", 10, Optional.empty()),
                        Optional.empty(),
                        0)),
                ViewQueryResult.class);
        ViewQueryResult grants = support.decode(
                started.query(support.request(
                        "site/view.privateNetworkGrants",
                        new ViewQueryRequest("sitePrivateNetworkGrants", Map.of(), "", 10, Optional.empty()),
                        Optional.empty(),
                        0)),
                ViewQueryResult.class);
        assertEquals(1, credentials.rows().size());
        assertEquals(1, grants.rows().size());
        assertFalse(credentials.rows().getFirst().json().contains("namespace"));
        assertFalse(credentials.rows().getFirst().json().contains("reference"));
        assertFalse(grants.rows().getFirst().json().contains("dnsAddresses"));
        assertFalse(grants.rows().getFirst().json().contains("workspaceId"));
    }

    @Test
    void publicQueriesToolsSearchAndViewsNeverExposeOpaqueReferences() throws Exception {
        BuiltinExtensionTestSupport support = new BuiltinExtensionTestSupport();
        SiteExtension bundle = new SiteExtension();
        var started = support.start(bundle);
        seed(support, secureSite("redacted", OLD_ORIGIN));

        List<String> payloads = List.of(
                started.query(support.request("read", new DocumentContracts.Key("redacted"), Optional.empty(), 0))
                        .payload()
                        .json(),
                started.query(support.request("list", new DocumentContracts.PageRequest("", 10), Optional.empty(), 0))
                        .payload()
                        .json(),
                started.query(support.request(
                                "view.list",
                                new ViewQueryRequest("documents", Map.of(), "", 10, Optional.empty()),
                                Optional.empty(),
                                0))
                        .payload()
                        .json(),
                started.query(support.request(
                                "search", new SiteContracts.SearchRequest("old.example.com", 10), Optional.empty(), 0))
                        .payload()
                        .json(),
                started.tool(
                                "site.read.tool",
                                support.request("read", new DocumentContracts.Key("redacted"), Optional.empty(), 0))
                        .payload()
                        .json(),
                started.tool(
                                "site.list.tool",
                                support.request("list", new DocumentContracts.PageRequest("", 10), Optional.empty(), 0))
                        .payload()
                        .json());
        payloads.forEach(SiteManagementTest::assertRedacted);

        ViewQueryResult rows = support.decode(
                started.query(support.request(
                        "view.list",
                        new ViewQueryRequest("documents", Map.of(), "", 10, Optional.empty()),
                        Optional.empty(),
                        0)),
                ViewQueryResult.class);
        SiteContracts.Projection projection =
                support.payloads.decode(rows.rows().getFirst(), SiteContracts.Projection.class);
        assertTrue(projection.hasCredential());
        assertTrue(projection.hasPrivateGrant());
        assertFalse(started.contributions().stream()
                .filter(ExtensionContributions.Tool.class::isInstance)
                .map(ExtensionContributions.Tool.class::cast)
                .anyMatch(tool -> tool.contributionId().equals("documents.read.tool")
                        || tool.contributionId().equals("documents.list.tool")));
        assertManagementViewBindsIdentity(started);
    }

    private static void assertRedacted(String json) {
        assertFalse(json.contains(CREDENTIAL.id()));
        assertFalse(json.contains(GRANT.id()));
        assertFalse(json.contains("privateNetworkGrant"));
        assertFalse(json.contains("reference"));
    }

    private static void assertManagementViewBindsIdentity(BuiltinExtensionTestSupport.Started started) {
        ViewSchema schema = started.contributions().stream()
                .filter(ExtensionContributions.View.class::isInstance)
                .map(ExtensionContributions.View.class::cast)
                .filter(view -> view.contributionId().equals("site.management.view"))
                .map(ExtensionContributions.View::view)
                .findFirst()
                .orElseThrow();
        ViewSchema.Form edit = schema.nodes().stream()
                .filter(ViewSchema.Form.class::isInstance)
                .map(ViewSchema.Form.class::cast)
                .filter(form -> form.id().equals("site-edit"))
                .findFirst()
                .orElseThrow();
        assertFalse(edit.fields().stream()
                .anyMatch(field -> Set.of("id", "revision", "authorityRevision", "credential", "privateNetworkGrant")
                        .contains(field.name())));
        assertEquals(
                List.of("id"),
                edit.submit().commandBindings().stream()
                        .map(com.javaclaw.extension.spi.ViewCommandBinding::argumentName)
                        .toList());
        assertTrue(
                edit.submit().expectedRevision()
                        instanceof com.javaclaw.extension.spi.ExpectedRevisionBinding.SourceRevision);
    }

    private static SiteManagementContracts.SaveRequest input(String id, String name, URI origin) {
        return new SiteManagementContracts.SaveRequest(
                id, name, origin, List.of(new SiteManagementContracts.AllowedOrigin("origin-1", origin)), true);
    }

    private static void create(
            BuiltinExtensionTestSupport.Started started, BuiltinExtensionTestSupport support, String id)
            throws Exception {
        started.command(support.request("site/create", input(id, id, OLD_ORIGIN), Optional.of("create-" + id), 0));
    }

    private static PrivateNetworkGrant activeGrant(
            BuiltinExtensionTestSupport support, String id, long revision, URI origin) {
        return new PrivateNetworkGrant(
                id,
                revision,
                SecurityGrantState.ACTIVE,
                support.workspaceId,
                PrivateNetworkPurpose.SITE,
                origin,
                Set.of("10.0.0.8"),
                NOW.plus(Duration.ofHours(1)),
                NOW.minus(Duration.ofMinutes(1)),
                NOW);
    }

    private static SiteContracts.Site secureSite(String id, URI origin) {
        SiteContracts.SiteCredential credential = new SiteContracts.SiteCredential(
                SiteContracts.CredentialKind.BEARER, Optional.of(CREDENTIAL), Optional.empty());
        return new SiteContracts.Site(id, 1, 1, id, origin, Set.of(origin), credential, Optional.of(GRANT), true, NOW);
    }

    private static void seed(BuiltinExtensionTestSupport support, SiteContracts.Site site) {
        support.store.put(collection(support), site.id(), 0, support.payloads.encode(site));
    }

    private static SiteContracts.Site stored(BuiltinExtensionTestSupport support, String id) {
        return support.payloads.decode(
                support.store.get(collection(support), id).orElseThrow().payload(), SiteContracts.Site.class);
    }

    private static String collection(BuiltinExtensionTestSupport support) {
        return "documents." + support.workspaceId;
    }
}
