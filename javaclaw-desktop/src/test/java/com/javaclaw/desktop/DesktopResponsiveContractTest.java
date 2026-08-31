package com.javaclaw.desktop;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DesktopResponsiveContractTest {
    @Test
    void breakpointsMatchTheReviewedThreeColumnPolicy() {
        assertEquals(MainController.ShellMode.WIDE, MainController.responsiveMode(1180));
        assertEquals(MainController.ShellMode.STANDARD, MainController.responsiveMode(1179));
        assertEquals(MainController.ShellMode.STANDARD, MainController.responsiveMode(960));
        assertEquals(MainController.ShellMode.COMPACT, MainController.responsiveMode(959));
        assertEquals(MainController.ShellMode.COMPACT, MainController.responsiveMode(600));
    }
}
