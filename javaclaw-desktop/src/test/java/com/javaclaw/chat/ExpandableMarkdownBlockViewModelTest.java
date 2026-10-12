package com.javaclaw.chat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExpandableMarkdownBlockViewModelTest {

    @Test
    void contentNeedsAvailabilityAndExpandedState() {
        ExpandableMarkdownBlockViewModel model = new ExpandableMarkdownBlockViewModel();
        model.configure("编程专家", false);

        assertEquals("编程专家", model.titleProperty().get());
        assertFalse(model.contentVisibleBinding().get());

        model.revealContent();
        assertTrue(model.contentVisibleBinding().get());
        model.toggleExpanded();
        assertFalse(model.contentVisibleBinding().get());
        model.toggleExpanded();
        assertTrue(model.contentVisibleBinding().get());
    }

    @Test
    void unavailableContentCannotBeExpandedIntoAnEmptyCard() {
        ExpandableMarkdownBlockViewModel model = new ExpandableMarkdownBlockViewModel();
        model.configure("等待结果", false);

        model.toggleExpanded();

        assertTrue(model.expandedProperty().get());
        assertFalse(model.contentVisibleBinding().get());
    }
}
