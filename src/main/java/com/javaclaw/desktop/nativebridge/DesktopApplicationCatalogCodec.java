package com.javaclaw.desktop.nativebridge;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.javaclaw.desktop.api.DesktopApplicationCatalog;
import com.javaclaw.desktop.api.DesktopApplicationInfo;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.List;

/** Bounded metadata codec for the optional, read-only native catalog entry point. */
final class DesktopApplicationCatalogCodec {
    static final int MAX_BYTES = 32_768;
    private static final JsonFactory JSON = JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder()
                    .maxNestingDepth(8).maxStringLength(256).maxNumberLength(10).build())
            .build();

    private DesktopApplicationCatalogCodec() { }

    static DesktopApplicationCatalog read(MethodHandle function) {
        if (function == null) throw new UnsupportedOperationException(
                "当前桌面原生库不支持安装应用目录，请更新原生库");
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment output = arena.allocate(MAX_BYTES);
            MemorySegment required = arena.allocate(ValueLayout.JAVA_INT);
            int code = (int) function.invokeExact(output, MAX_BYTES, required);
            long bytes = Integer.toUnsignedLong(required.get(ValueLayout.JAVA_INT, 0));
            if (code != 0 || bytes < 2 || bytes > MAX_BYTES
                    || output.get(ValueLayout.JAVA_BYTE, bytes - 1) != 0)
                throw new IllegalStateException("安装应用目录返回无效数据（状态 " + code + "）");
            return decode(output.asSlice(0, bytes - 1).toArray(ValueLayout.JAVA_BYTE));
        } catch (UnsupportedOperationException | IllegalStateException failure) { throw failure; }
        catch (Throwable failure) { throw new IllegalStateException("安装应用目录读取失败", failure); }
    }

    static DesktopApplicationCatalog decode(byte[] bytes) throws IOException {
        if (bytes.length >= MAX_BYTES) throw new IOException("application catalog exceeds its bound");
        try (JsonParser parser = JSON.createParser(bytes)) {
            expect(parser.nextToken(), JsonToken.START_OBJECT);
            int schema = -1;
            int count = -1;
            Boolean truncated = null;
            List<DesktopApplicationInfo> applications = null;
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                expect(parser.currentToken(), JsonToken.FIELD_NAME);
                String field = parser.currentName();
                parser.nextToken();
                switch (field) {
                    case "schemaVersion" -> { expect(parser.currentToken(), JsonToken.VALUE_NUMBER_INT); schema = parser.getIntValue(); }
                    case "count" -> { expect(parser.currentToken(), JsonToken.VALUE_NUMBER_INT); count = parser.getIntValue(); }
                    case "truncated" -> {
                        if (parser.currentToken() != JsonToken.VALUE_TRUE && parser.currentToken() != JsonToken.VALUE_FALSE)
                            throw new IOException("invalid catalog truncation flag");
                        truncated = parser.getBooleanValue();
                    }
                    case "applications" -> applications = applications(parser);
                    default -> parser.skipChildren();
                }
            }
            if (schema != 1 || applications == null || truncated == null
                    || count != applications.size() || parser.nextToken() != null)
                throw new IOException("invalid application catalog envelope");
            return new DesktopApplicationCatalog(applications, truncated);
        } catch (IllegalArgumentException invalid) { throw new IOException("invalid application metadata", invalid); }
    }

    private static List<DesktopApplicationInfo> applications(JsonParser parser) throws IOException {
        expect(parser.currentToken(), JsonToken.START_ARRAY);
        List<DesktopApplicationInfo> values = new ArrayList<>();
        while (parser.nextToken() != JsonToken.END_ARRAY) {
            if (values.size() >= 256) throw new IOException("too many installed applications");
            expect(parser.currentToken(), JsonToken.START_OBJECT);
            String name = null, display = "", identity = null, launch = null;
            List<String> aliases = List.of();
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                expect(parser.currentToken(), JsonToken.FIELD_NAME);
                String field = parser.currentName();
                parser.nextToken();
                switch (field) {
                    case "name" -> name = text(parser);
                    case "displayName" -> display = text(parser);
                    case "applicationId" -> identity = text(parser);
                    case "launchName" -> launch = text(parser);
                    case "aliases" -> aliases = aliases(parser);
                    default -> parser.skipChildren();
                }
            }
            if (name == null || identity == null || launch == null)
                throw new IOException("application identity or launch name missing");
            values.add(new DesktopApplicationInfo(name, display, identity, launch, aliases));
        }
        return values;
    }

    private static List<String> aliases(JsonParser parser) throws IOException {
        expect(parser.currentToken(), JsonToken.START_ARRAY);
        List<String> values = new ArrayList<>();
        while (parser.nextToken() != JsonToken.END_ARRAY) {
            if (values.size() >= 16) throw new IOException("too many application aliases");
            values.add(text(parser));
        }
        return values;
    }

    private static String text(JsonParser parser) throws IOException {
        expect(parser.currentToken(), JsonToken.VALUE_STRING);
        String value = parser.getText();
        if (value.length() > 256) throw new IOException("application text exceeds its bound");
        return value;
    }

    private static void expect(JsonToken actual, JsonToken expected) throws IOException {
        if (actual != expected) throw new IOException("invalid application catalog structure");
    }
}
