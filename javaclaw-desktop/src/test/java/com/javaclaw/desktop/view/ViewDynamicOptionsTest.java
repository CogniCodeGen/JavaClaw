package com.javaclaw.desktop.view;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.javaclaw.extension.spi.ViewOption;
import com.javaclaw.extension.spi.ViewOptionFilter;
import com.javaclaw.extension.spi.ViewOptionSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ViewDynamicOptionsTest {
    @Test
    void resolvesOnlyRowsForSelectedToolAndKeepsDeclaredOptionFirst() {
        ViewData data = new ViewData(Map.of(
                "fields",
                new ViewData.Source(
                        List.of(
                                row("read_file", "/exitCode"),
                                row("write_file", "/success"),
                                row("read_file", "/verified")),
                        Map.of(),
                        "",
                        "",
                        false,
                        1,
                        0,
                        Optional.empty())));
        ViewOptionSource source = new ViewOptionSource(
                "fields", "fieldPointer", "fieldLabel", Optional.of(new ViewOptionFilter("toolName", "toolName")));

        assertEquals(
                List.of("/default", "/exitCode", "/verified"),
                ViewDynamicOptions.resolve(
                                List.of(new ViewOption("/default", "默认")), source, data, field -> "read_file")
                        .stream()
                        .map(ViewOption::value)
                        .toList());
    }

    private static Map<String, Object> row(String tool, String pointer) {
        return Map.of(
                "toolName", tool,
                "fieldPointer", pointer,
                "fieldLabel", pointer);
    }
}
