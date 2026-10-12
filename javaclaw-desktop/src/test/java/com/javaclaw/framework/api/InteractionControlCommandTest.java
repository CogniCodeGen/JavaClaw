package com.javaclaw.framework.api;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class InteractionControlCommandTest {
    @Test
    void fixedProtocolRoundTripsEveryExplicitCommand() {
        for (var command : List.of(
                new InteractionControlCommand("answer", 1, InteractionControlCommand.Type.ANSWER, "确认", 42),
                new InteractionControlCommand("amend", Long.MAX_VALUE, InteractionControlCommand.Type.AMEND, "修订目标"),
                new InteractionControlCommand("cancel", 2, InteractionControlCommand.Type.CANCEL, ""))) {
            assertEquals(command, InteractionControlCommand.fromJson(command.toJson()));
            assertEquals(5, command.toJson().size());
        }
    }

    @Test
    void malformedFieldsAreNotCoercedIntoControlAuthority() {
        var valid = new InteractionControlCommand("answer", 1,
                InteractionControlCommand.Type.ANSWER, "确认", 42).toJson();
        var unknown = valid.deepCopy().put("permission", "all");
        var revisionText = valid.deepCopy().put("expectedRevision", "1");
        var revisionFraction = valid.deepCopy().put("expectedRevision", 1.5);
        var overflow = valid.deepCopy().put("expectedRevision", BigInteger.ONE.shiftLeft(65));
        var negativeRevision = valid.deepCopy().put("expectedRevision", 0);
        var missingIdentity = valid.deepCopy();
        missingIdentity.remove("commandId");
        var blankIdentity = valid.deepCopy().put("commandId", " ");
        var unknownType = valid.deepCopy().put("type", "CLAIM_DONE");
        var objectText = valid.deepCopy();
        objectText.putObject("text");
        var negativeSequence = valid.deepCopy().put("childEventSequence", -1);
        var nonIntegralSequence = valid.deepCopy().put("childEventSequence", "42");
        var invalidAmendment = valid.deepCopy().put("type", "AMEND");
        var emptyAnswer = valid.deepCopy().put("text", " ");
        var hugeText = valid.deepCopy().put("text", "字".repeat(8001));
        for (var value : List.of(unknown, revisionText, revisionFraction, overflow, negativeRevision,
                missingIdentity, blankIdentity, unknownType, objectText, negativeSequence,
                nonIntegralSequence, invalidAmendment, emptyAnswer, hugeText)) {
            assertThrows(IllegalArgumentException.class, () -> InteractionControlCommand.fromJson(value), value.toString());
        }
        assertThrows(IllegalArgumentException.class, () -> InteractionControlCommand.fromJson(null));
        assertThrows(IllegalArgumentException.class, () -> InteractionControlCommand.fromJson(JsonNodeFactory.instance.arrayNode()));
    }

    @Test
    void omittedChallengeIsZeroAndNeverSynthesizedFromText() {
        var legacy = new InteractionControlCommand("answer", 1,
                InteractionControlCommand.Type.ANSWER, "challenge:42", 42).toJson();
        legacy.remove("childEventSequence");
        assertEquals(0, InteractionControlCommand.fromJson(legacy).childEventSequence());
        var cancel = legacy.put("type", "CANCEL");
        cancel.remove("text");
        assertEquals("", InteractionControlCommand.fromJson(cancel).text());
    }
}
