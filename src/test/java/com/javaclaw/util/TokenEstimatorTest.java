package com.javaclaw.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TokenEstimatorTest {

    @Test
    void truncationNeverSplitsSupplementaryUnicode() {
        assertEquals(3, TokenEstimator.estimate("😀x"));
        assertEquals("", TokenEstimator.truncateToTokens("😀x", 1));
        assertEquals("😀", TokenEstimator.truncateToTokens("😀x", 2));
        assertEquals("😀x", TokenEstimator.truncateToTokens("😀x", 3));

        String value = "ab😀🚀中cd🧠tail";
        for (int budget = 0; budget <= TokenEstimator.estimate(value); budget++) {
            String prefix = TokenEstimator.truncateToTokens(value, budget);
            assertTrue(value.startsWith(prefix));
            assertTrue(TokenEstimator.estimate(prefix) <= budget);
            assertFalse(hasUnpairedSurrogate(prefix),
                    "unpaired surrogate at budget " + budget);
        }
    }

    @Test
    void existingAsciiBmpAndNegativeBudgetSemanticsRemainStable() {
        assertEquals("abcd", TokenEstimator.truncateToTokens("abcdef", 1));
        assertEquals("中文", TokenEstimator.truncateToTokens("中文说明", 2));
        assertEquals("unchanged", TokenEstimator.truncateToTokens("unchanged", -1));
        assertEquals("", TokenEstimator.truncateToTokens(null, 1));
    }

    private static boolean hasUnpairedSurrogate(String value) {
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (index + 1 >= value.length()
                        || !Character.isLowSurrogate(value.charAt(++index))) return true;
            } else if (Character.isLowSurrogate(current)) {
                return true;
            }
        }
        return false;
    }
}
