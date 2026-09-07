package com.javaclaw.server.coding;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.javaclaw.builtin.contracts.CodingResults;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodingFailureDisclosureTest {
    @Test
    void lowLevelHostDetailsAreRedactedWhileStableRecoveryCategoriesRemainAvailable() {
        var cases = List.of(
                new Diagnostic(new IllegalStateException(), "CODING_PREFLIGHT_FAILED"),
                new Diagnostic(new IllegalStateException("Conflict at /host/private-file"), "FILE_DIGEST_CONFLICT"),
                new Diagnostic(
                        new IllegalStateException("digest differs at /host/private-file"), "FILE_DIGEST_CONFLICT"),
                new Diagnostic(new IllegalStateException("摘要不同 /host/private-file"), "FILE_DIGEST_CONFLICT"),
                new Diagnostic(new IllegalStateException("EXECUTION_ROOT_BUSY: /host/private-file"), "EXECUTION_BUSY"),
                new Diagnostic(new SecurityException("secret-bearing host detail"), "CODING_PERMISSION_DENIED"));
        for (Diagnostic sample : cases) {
            var result = CodingFailures.result("operation", sample.cause());
            var failure = assertInstanceOf(CodingResults.Failure.class, result.value());
            assertEquals(sample.code(), failure.errorCode());
            assertEquals("operation", failure.operationId());
            assertTrue(failure.retryable());
            assertFalse(failure.message().contains("/host/"));
            assertFalse(failure.message().contains("secret-bearing"));
            assertTrue(result.facts().isEmpty());
            assertFalse(result.success());
        }
    }

    @Test
    void missingToolchainsExposeOnlyKnownKindsAndUnverifiedDeclarationsRequireANewTurn() {
        var known = failure("TOOLCHAIN_NOT_READY: /private/downloads: JDK");
        assertEquals("TOOLCHAIN_MISSING", known.errorCode());
        assertTrue(known.message().startsWith("JDK "));
        assertFalse(known.message().contains("/private/"));
        assertTrue(known.retryable());
        var unknown = failure("TOOLCHAIN_MISSING: /private/unknown-artifact");
        assertTrue(unknown.message().startsWith("工具链 "));
        var unverified = failure("TOOLCHAIN_DECLARATIONS_UNVERIFIED: /private/project");
        assertEquals("TOOLCHAIN_DECLARATIONS_UNVERIFIED", unverified.errorCode());
        assertFalse(unverified.retryable());
        assertFalse(unverified.message().contains("/private/"));
    }

    @Test
    void quarantinedWorkspacesCannotAdvertiseAnAutomaticRetryAndDeclarationEvidenceIsBounded() {
        var quarantined = failure("WORKSPACE_SECURITY_LOCKED: /private/recovery-file");
        assertEquals("WORKSPACE_SECURITY_LOCKED", quarantined.errorCode());
        assertFalse(quarantined.retryable());
        assertFalse(quarantined.message().contains("/private/"));
        var declaration = failure("TOOLCHAIN_DECLARATION_CONFLICT: " + "版本要求".repeat(1000));
        assertEquals("TOOLCHAIN_DECLARATION_CONFLICT", declaration.errorCode());
        assertEquals(2000, declaration.message().length());
        assertFalse(declaration.retryable());
    }

    private static CodingResults.Failure failure(String message) {
        return assertInstanceOf(
                CodingResults.Failure.class,
                CodingFailures.result("operation", new IllegalStateException(message))
                        .value());
    }

    private record Diagnostic(Exception cause, String code) {}
}
