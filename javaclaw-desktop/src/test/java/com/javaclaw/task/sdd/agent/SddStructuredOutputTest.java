package com.javaclaw.task.sdd.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SddStructuredOutputTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void acceptsExactlyOneSchemaConformingObject() {
        var output = new SddStructuredOutput<>(SddDrafts.ProposalDraft.class, json);
        SddDrafts.ProposalDraft draft = output.parse("""
                {"why":"原因","whatChanges":"改动","outOfScope":""}
                """);

        assertEquals("改动", draft.whatChanges);
        assertTrue(output.formatInstructions().contains("additionalProperties"));
    }

    @Test
    void rejectsUnknownFieldsAtRootAndNestedDepth() {
        var proposal = new SddStructuredOutput<>(SddDrafts.ProposalDraft.class, json);
        assertThrows(IllegalArgumentException.class, () -> proposal.parse("""
                {"why":"原因","whatChanges":"改动","outOfScope":"","decision":"DONE"}
                """));

        var spec = new SddStructuredOutput<>(SddDrafts.SpecDraft.class, json);
        assertThrows(IllegalArgumentException.class, () -> spec.parse(validSpec()
                .replace("\"criterionPredicate\":\"检查\"",
                        "\"criterionPredicate\":\"检查\",\"verified\":true")));
    }

    @Test
    void rejectsInvalidEnumMissingRequiredAndWrongArrayItem() {
        var spec = new SddStructuredOutput<>(SddDrafts.SpecDraft.class, json);
        assertThrows(IllegalArgumentException.class, () -> spec.parse(validSpec()
                .replace("\"FREEFORM\"", "\"OUTPUT_CONTAINS\"")));
        assertThrows(IllegalArgumentException.class, () -> spec.parse(validSpec()
                .replace(",\"criterionPredicate\":\"检查\"", "")));
        assertThrows(IllegalArgumentException.class, () -> spec.parse("""
                {"capabilities":["not an object"]}
                """));
    }

    @Test
    void rejectsMarkdownTrailingJsonAndDuplicateKeys() {
        var output = new SddStructuredOutput<>(SddDrafts.ProposalDraft.class, json);
        String valid = "{\"why\":\"原因\",\"whatChanges\":\"改动\",\"outOfScope\":\"\"}";
        assertThrows(IllegalArgumentException.class,
                () -> output.parse("```json\n" + valid + "\n```"));
        assertThrows(IllegalArgumentException.class,
                () -> output.parse(valid + " " + valid));
        assertThrows(IllegalArgumentException.class,
                () -> output.parse("{\"why\":\"A\",\"why\":\"B\","
                        + "\"whatChanges\":\"改动\",\"outOfScope\":\"\"}"));
    }

    private static String validSpec() {
        return """
                {"capabilities":[{"name":"功能","requirements":[{"title":"需求","scenarios":[
                {"title":"场景","given":"","when":"","then":"",
                 "criterionType":"FREEFORM","criterionPredicate":"检查"}]}]}]}
                """;
    }
}
