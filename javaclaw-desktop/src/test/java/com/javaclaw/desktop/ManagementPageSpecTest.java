package com.javaclaw.desktop;

import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManagementPageSpecTest {
    @Test
    void allTwelvePagesKeepTheirReviewedWindowAndRailDimensions() {
        Map<String, double[]> expected = Map.ofEntries(
                Map.entry("Settings", values(1100, 760, 900, 680, 210)),
                Map.entry("Profiles", values(1100, 760, 900, 680, 232)),
                Map.entry("Memory", values(1000, 680, 820, 600, 212)),
                Map.entry("Knowledge", values(1340, 864, 1040, 680, 252)),
                Map.entry("Skills", values(920, 650, 780, 560, 232)),
                Map.entry("Automation", values(1240, 800, 960, 680, 258)),
                Map.entry("Schedules", values(960, 680, 780, 560, 264)),
                Map.entry("Plugins", values(960, 680, 780, 560, 228)),
                Map.entry("MCP", values(960, 680, 780, 560, 224)),
                Map.entry("Sites", values(1080, 740, 860, 620, 232)),
                Map.entry("Instructions", values(1000, 700, 820, 600, 232)),
                Map.entry("Worktrees", values(1100, 740, 900, 620, 252)));
        assertEquals(12, ManagementPageSpec.all().size());
        for (var entry : expected.entrySet()) {
            ManagementPageSpec actual = ManagementPageSpec.forSection(entry.getKey());
            assertEquals(entry.getValue()[0], actual.preferredWidth(), entry.getKey());
            assertEquals(entry.getValue()[1], actual.preferredHeight(), entry.getKey());
            assertEquals(entry.getValue()[2], actual.minimumWidth(), entry.getKey());
            assertEquals(entry.getValue()[3], actual.minimumHeight(), entry.getKey());
            assertEquals(entry.getValue()[4], actual.railWidth(), entry.getKey());
            assertTrue(actual.preferredWidth() >= actual.minimumWidth());
            assertTrue(actual.preferredHeight() >= actual.minimumHeight());
        }
    }

    private static double[] values(double... values) {
        return values;
    }
}
