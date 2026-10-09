package com.javaclaw.desktop.ffm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.desktop.api.*;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DesktopApplicationCatalogPolicyTest {
    @Test void deterministicSortDeduplicatesIdentitiesAndMergesAliases() {
        var a = app("Alpha", "com.example.a", List.of("Alias", "Alpha"));
        var duplicate = app("Alternative", "com.example.a", List.of("Second"));
        var b = app("Beta", "com.example.b", List.of());
        var catalog = DesktopApplicationCatalogPolicy.bounded(List.of(b, duplicate, a), false);
        assertEquals(List.of("com.example.a", "com.example.b"),
                catalog.applications().stream().map(DesktopApplicationInfo::applicationId).toList());
        assertEquals("Alpha", catalog.applications().getFirst().name());
        assertTrue(catalog.applications().getFirst().aliases().containsAll(List.of("Alternative", "Alias", "Second")));
        assertEquals(catalog, DesktopApplicationCatalogPolicy.bounded(List.of(a, b, duplicate), false));
        assertFalse(catalog.truncated());
        assertThrows(UnsupportedOperationException.class, () -> catalog.applications().clear());
    }

    @Test void countAndUtf8MetadataBoundsAreBothEnforced() throws Exception {
        var many = IntStream.range(0, 300).mapToObj(i -> app("App " + i, "app." + i, List.of())).toList();
        var countBound = DesktopApplicationCatalogPolicy.bounded(many, false);
        assertTrue(countBound.truncated());
        assertTrue(countBound.applications().size() <= 256);
        var large = new ArrayList<DesktopApplicationInfo>();
        String name = "应".repeat(80);
        for (int i = 0; i < 100; i++) large.add(app(name, "app." + i, List.of(name + "一", name + "二")));
        var bytesBound = DesktopApplicationCatalogPolicy.bounded(large, false);
        assertTrue(bytesBound.truncated());
        assertTrue(new ObjectMapper().writeValueAsBytes(bytesBound).length < 32_768);
    }

    @Test void aliasBoundsAndSourceTruncationStayExplicit() {
        var aliases = IntStream.range(0, 16).mapToObj(i -> "alias" + i).toList();
        var catalog = DesktopApplicationCatalogPolicy.bounded(List.of(app("App", "app", aliases)), true);
        assertEquals(8, catalog.applications().getFirst().aliases().size());
        assertTrue(catalog.truncated());
        assertTrue(DesktopApplicationCatalogPolicy.bounded(List.of(), true).truncated());
    }

    @Test void invalidPublicIdentitiesRemainRejectedWithoutExecutablePathLeakage() {
        for (String invalid : List.of("../App", "C:\\App.exe", "foo/bar", "bad\nname", " App "))
            assertThrows(IllegalArgumentException.class, () -> app("App", invalid, List.of()));
        assertThrows(IllegalArgumentException.class, () -> app("应".repeat(86), "app", List.of()));
    }

    private static DesktopApplicationInfo app(String name, String id, List<String> aliases) {
        return new DesktopApplicationInfo(name, name, id, name, aliases);
    }
}
