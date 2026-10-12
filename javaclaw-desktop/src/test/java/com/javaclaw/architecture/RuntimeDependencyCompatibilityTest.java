package com.javaclaw.architecture;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class RuntimeDependencyCompatibilityTest {

    @Test
    void jacksonThreeCoreDatabindAndAnnotationsAreLinkCompatible() throws Exception {
        assertNotNull(Class.forName("com.fasterxml.jackson.annotation.JsonSerializeAs"));
        assertEquals(tools.jackson.databind.cfg.PackageVersion.VERSION.toString(),
                tools.jackson.core.json.PackageVersion.VERSION.toString(),
                "Jackson 3 core and databind must use the same release");
    }
}
