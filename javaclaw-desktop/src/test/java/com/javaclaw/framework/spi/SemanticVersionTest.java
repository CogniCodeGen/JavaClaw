package com.javaclaw.framework.spi;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SemanticVersionTest {

    @Test
    void prereleaseIdentifiersFollowSemverPrecedence() {
        assertBefore("1.0.0-alpha", "1.0.0-alpha.1");
        assertBefore("1.0.0-alpha.1", "1.0.0-alpha.beta");
        assertBefore("1.0.0-alpha.beta", "1.0.0-beta");
        assertBefore("1.0.0-beta.2", "1.0.0-beta.11");
        assertBefore("1.0.0-rc.1", "1.0.0");
        assertBefore("1.0.0-1", "1.0.0-alpha");
    }

    @Test
    void prereleaseSyntaxRejectsEmptyAndLeadingZeroNumericIdentifiers() {
        assertThrows(IllegalArgumentException.class,
                () -> SemanticVersion.parse("1.0.0-alpha..1"));
        assertThrows(IllegalArgumentException.class,
                () -> SemanticVersion.parse("1.0.0-01"));
        assertThrows(IllegalArgumentException.class,
                () -> SemanticVersion.parse("1.0.0+build"));
    }

    private static void assertBefore(String left, String right) {
        assertTrue(SemanticVersion.parse(left).compareTo(SemanticVersion.parse(right)) < 0,
                left + " must precede " + right);
    }
}
