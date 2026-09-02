package com.javaclaw.desktop.view;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ViewRequestEpochTest {
    @Test
    void newerRequestAndCancelInvalidateOlderResponses() {
        ViewRequestEpoch epochs = new ViewRequestEpoch();

        long first = epochs.begin();
        long second = epochs.begin();

        assertFalse(epochs.isCurrent(first));
        assertTrue(epochs.isCurrent(second));
        epochs.cancel();
        assertFalse(epochs.isCurrent(second));
    }
}
