package com.javaclaw.desktop.ffm;

import com.fasterxml.jackson.core.JsonFactory;
import com.javaclaw.desktop.api.DesktopApplicationCatalog;
import com.javaclaw.desktop.api.DesktopApplicationInfo;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.TreeSet;

/** Platform-independent catalog ordering, de-duplication and metadata bounds. */
public final class DesktopApplicationCatalogPolicy {
    private static final int MAX_APPLICATIONS = 256;
    private static final int MAX_METADATA_BYTES = 32_768;
    private static final JsonFactory JSON = new JsonFactory();

    private DesktopApplicationCatalogPolicy() { }

    public static DesktopApplicationCatalog bounded(Collection<DesktopApplicationInfo> candidates,
                                                     boolean sourceTruncated) {
        var unique = new LinkedHashMap<String, DesktopApplicationInfo>();
        candidates.stream().sorted(Comparator.comparing(DesktopApplicationInfo::applicationId)
                .thenComparing(DesktopApplicationInfo::name)).forEach(application ->
                unique.merge(application.applicationId(), application, DesktopApplicationCatalogPolicy::merge));
        var selected = new java.util.ArrayList<DesktopApplicationInfo>();
        int bytes = 128;
        for (DesktopApplicationInfo application : unique.values()) {
            var aliases = new TreeSet<>(application.aliases());
            var normalized = new DesktopApplicationInfo(application.name(), application.displayName(),
                    application.applicationId(), application.launchName(), aliases.stream().limit(8).toList());
            int required = encodedBytes(normalized) + 1;
            if (selected.size() == MAX_APPLICATIONS || required > MAX_METADATA_BYTES - bytes) break;
            selected.add(normalized);
            bytes += required;
        }
        return new DesktopApplicationCatalog(List.copyOf(selected), sourceTruncated || selected.size() < unique.size());
    }

    private static DesktopApplicationInfo merge(DesktopApplicationInfo first, DesktopApplicationInfo second) {
        var aliases = new TreeSet<>(first.aliases());
        aliases.addAll(second.aliases());
        aliases.add(second.name());
        if (!second.displayName().isBlank()) aliases.add(second.displayName());
        aliases.remove(first.name());
        return new DesktopApplicationInfo(first.name(), first.displayName(), first.applicationId(),
                first.launchName(), aliases.stream().limit(8).toList());
    }

    private static int encodedBytes(DesktopApplicationInfo application) {
        try (var output = new ByteArrayOutputStream(); var json = JSON.createGenerator(output)) {
            json.writeStartObject();
            json.writeStringField("name", application.name());
            json.writeStringField("displayName", application.displayName());
            json.writeStringField("applicationId", application.applicationId());
            json.writeStringField("launchName", application.launchName());
            json.writeArrayFieldStart("aliases");
            for (String alias : application.aliases()) json.writeString(alias);
            json.writeEndArray();
            json.writeEndObject();
            json.flush();
            return output.size();
        } catch (IOException impossible) { throw new IllegalStateException("Cannot encode application metadata", impossible); }
    }
}
