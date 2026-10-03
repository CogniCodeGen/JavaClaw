package com.javaclaw.skill.curation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillCuratorStructuredOutputTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String VALID = """
            {"worthLearning":true,"action":"create","skillName":"review-code",
             "reason":"Reusable review steps","description":"Review Java projects",
             "category":"coding","tags":["review"],"content":"## Steps\\nInspect the code"}
            """;

    @Test
    void acceptsOneCompleteSchemaValidObject() {
        SkillCurationDraft draft = SkillCurator.parseDraft(JSON, VALID);
        assertTrue(draft.worthLearning);
        assertEquals("create", draft.action);
        assertEquals("review-code", draft.skillName);
        assertEquals("## Steps\nInspect the code", draft.content);
    }

    @Test
    void rejectsMarkdownProseExtraValuesAndMalformedFields() {
        assertThrows(IllegalArgumentException.class,
                () -> SkillCurator.parseDraft(JSON, "```json\n" + VALID + "\n```"));
        assertThrows(IllegalArgumentException.class,
                () -> SkillCurator.parseDraft(JSON, "Done. " + VALID));
        assertThrows(IllegalArgumentException.class,
                () -> SkillCurator.parseDraft(JSON, VALID + "\n{}"));
        assertThrows(IllegalArgumentException.class,
                () -> SkillCurator.parseDraft(JSON, VALID.replace("\"action\":\"create\"",
                        "\"action\":\"none\",\"action\":\"create\"")));
        assertThrows(IllegalArgumentException.class,
                () -> SkillCurator.parseDraft(JSON, VALID.replace("\"action\":\"create\"",
                        "\"action\":\"done\"")));
        assertThrows(IllegalArgumentException.class,
                () -> SkillCurator.parseDraft(JSON, VALID.replace("\"worthLearning\":true",
                        "\"worthLearning\":\"true\"")));
        assertThrows(IllegalArgumentException.class,
                () -> SkillCurator.parseDraft(JSON, VALID.replace("\"skillName\":\"review-code\",", "")));
    }
}
