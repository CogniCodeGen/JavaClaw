package com.javaclaw.builtin.extensions;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.builtin.contracts.LoopContracts;
import com.javaclaw.builtin.contracts.SddContracts;
import com.javaclaw.extension.spi.OrchestratedToolEvidence;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VerificationEvidenceCoverageTest {
    @Test
    void loopExitCodeUsesLastSuccessfulMatchingToolEvidence() {
        BuiltinExtensionTestSupport support = new BuiltinExtensionTestSupport();
        LoopContracts.VerificationRule expectedOne = exitRule(1);
        List<OrchestratedToolEvidence> evidence = List.of(
                evidence(support, "other", true, Map.of("exitCode", 1)),
                evidence(support, "verify", false, Map.of("exitCode", 1)),
                evidence(support, "verify", true, Map.of("exitCode", 0)),
                evidence(support, "verify", true, Map.of("exitCode", 1)));

        LoopVerification.requireSatisfied(evidence, expectedOne, support.payloads);
        assertThrows(
                IllegalStateException.class,
                () -> LoopVerification.requireSatisfied(evidence, exitRule(0), support.payloads));
        assertThrows(
                IllegalStateException.class,
                () -> LoopVerification.requireSatisfied(
                        List.of(evidence(support, "verify", true, Map.of("exitCode", "zero"))),
                        exitRule(0),
                        support.payloads));
        assertThrows(
                IllegalStateException.class,
                () -> LoopVerification.requireSatisfied(List.of(), expectedOne, support.payloads));
    }

    @Test
    void loopFieldAssertionSupportsEscapedPointerAndOnlyScalarValues() {
        BuiltinExtensionTestSupport support = new BuiltinExtensionTestSupport();
        CanonicalPayload expectedTrue = support.payloads.encode(Map.of("value", true));
        LoopContracts.VerificationRule escaped = fieldRule("/a~1b/x~0y", expectedTrue);
        OrchestratedToolEvidence nested = evidence(support, "verify", true, Map.of("a/b", Map.of("x~y", true)));

        LoopVerification.requireSatisfied(List.of(nested), escaped, support.payloads);
        LoopVerification.requireSatisfied(
                List.of(evidence(support, "verify", true, Map.of("value", 3))),
                fieldRule("/value", support.payloads.encode(Map.of("value", 3))),
                support.payloads);
        LoopVerification.requireSatisfied(
                List.of(evidence(support, "verify", true, Map.of("value", "ok"))),
                fieldRule("/value", support.payloads.encode(Map.of("value", "ok"))),
                support.payloads);
        assertThrows(
                IllegalStateException.class,
                () -> LoopVerification.requireSatisfied(
                        List.of(evidence(support, "verify", true, Map.of("value", List.of("unsafe")))),
                        fieldRule("/value", support.payloads.encode(Map.of("value", "unsafe"))),
                        support.payloads));
        assertThrows(
                IllegalStateException.class,
                () -> LoopVerification.requireSatisfied(
                        List.of(evidence(support, "verify", true, Map.of("value", true))),
                        fieldRule("/missing", expectedTrue),
                        support.payloads));
        assertThrows(
                IllegalStateException.class,
                () -> LoopVerification.requireSatisfied(
                        List.of(evidence(support, "verify", true, Map.of("value", true))),
                        fieldRule("/value/nested", expectedTrue),
                        support.payloads));
        assertThrows(
                IllegalStateException.class,
                () -> LoopVerification.requireSatisfied(
                        List.of(evidence(support, "verify", true, Map.of("value", false))),
                        fieldRule("/value", expectedTrue),
                        support.payloads));
    }

    @Test
    void loopRejectsUserConfirmationAsToolEvidence() {
        BuiltinExtensionTestSupport support = new BuiltinExtensionTestSupport();
        LoopContracts.VerificationRule confirmation = new LoopContracts.VerificationRule(
                LoopContracts.VerificationKind.USER_CONFIRMATION,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());

        assertThrows(
                IllegalArgumentException.class,
                () -> LoopVerification.requireSatisfied(
                        List.of(evidence(support, "verify", true, Map.of("exitCode", 0))),
                        confirmation,
                        support.payloads));
    }

    @Test
    void sddExitCodeReturnsFalseForMissingFailedNonNumericOrMismatchedEvidence() {
        BuiltinExtensionTestSupport support = new BuiltinExtensionTestSupport();
        SddContracts.VerificationRule rule = sddExitRule(0);

        assertTrue(SddVerification.satisfied(
                List.of(evidence(support, "verify", true, Map.of("exitCode", 0))), rule, support.payloads));
        assertFalse(SddVerification.satisfied(List.of(), rule, support.payloads));
        assertFalse(SddVerification.satisfied(
                List.of(evidence(support, "other", true, Map.of("exitCode", 0))), rule, support.payloads));
        assertFalse(SddVerification.satisfied(
                List.of(evidence(support, "verify", false, Map.of("exitCode", 0))), rule, support.payloads));
        assertFalse(SddVerification.satisfied(
                List.of(evidence(support, "verify", true, Map.of("exitCode", "zero"))), rule, support.payloads));
        assertFalse(SddVerification.satisfied(
                List.of(evidence(support, "verify", true, Map.of("exitCode", 1))), rule, support.payloads));
    }

    @Test
    void sddFieldAssertionSupportsEscapedPointerAndFailsClosedOnMissingOrNull() {
        BuiltinExtensionTestSupport support = new BuiltinExtensionTestSupport();
        CanonicalPayload expected = support.payloads.encode(Map.of("value", true));
        SddContracts.VerificationRule rule = new SddContracts.VerificationRule(
                SddContracts.VerificationKind.TOOL_FIELD_ASSERTION,
                "verify",
                Optional.empty(),
                Optional.of("/a~1b/x~0y"),
                Optional.of(expected));

        assertTrue(SddVerification.satisfied(
                List.of(evidence(support, "verify", true, Map.of("a/b", Map.of("x~y", true)))),
                rule,
                support.payloads));
        assertFalse(SddVerification.satisfied(
                List.of(evidence(support, "verify", true, Map.of("a/b", Map.of("x~y", false)))),
                rule,
                support.payloads));
        assertFalse(SddVerification.satisfied(
                List.of(evidence(support, "verify", true, Map.of("a/b", Map.of()))), rule, support.payloads));
        assertFalse(SddVerification.satisfied(
                List.of(new OrchestratedToolEvidence("verify", true, new CanonicalPayload("{\"a/b\":{\"x~y\":null}}"))),
                rule,
                support.payloads));
        assertFalse(SddVerification.satisfied(
                List.of(evidence(support, "verify", true, Map.of("a/b", true))), rule, support.payloads));
    }

    private static LoopContracts.VerificationRule exitRule(int code) {
        return new LoopContracts.VerificationRule(
                LoopContracts.VerificationKind.TOOL_EXIT_CODE,
                Optional.of("verify"),
                Optional.of(code),
                Optional.empty(),
                Optional.empty());
    }

    private static LoopContracts.VerificationRule fieldRule(String pointer, CanonicalPayload expected) {
        return new LoopContracts.VerificationRule(
                LoopContracts.VerificationKind.TOOL_FIELD_ASSERTION,
                Optional.of("verify"),
                Optional.empty(),
                Optional.of(pointer),
                Optional.of(expected));
    }

    private static SddContracts.VerificationRule sddExitRule(int code) {
        return new SddContracts.VerificationRule(
                SddContracts.VerificationKind.TOOL_EXIT_CODE,
                "verify",
                Optional.of(code),
                Optional.empty(),
                Optional.empty());
    }

    private static OrchestratedToolEvidence evidence(
            BuiltinExtensionTestSupport support, String tool, boolean successful, Object output) {
        return new OrchestratedToolEvidence(tool, successful, support.payloads.encode(output));
    }
}
