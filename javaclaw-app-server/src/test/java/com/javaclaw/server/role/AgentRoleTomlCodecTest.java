package com.javaclaw.server.role;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.AgentRole;
import com.javaclaw.api.AgentRoleFileFormat;
import com.javaclaw.api.PermissionConstraint;
import com.javaclaw.api.ReasoningPreference;
import com.javaclaw.api.RoleLifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentRoleTomlCodecTest {
    private final AgentRoleTomlCodec codec = new AgentRoleTomlCodec();

    @Test
    void explicitFormatAndNonblankModelAreRequired() {
        assertThrows(NullPointerException.class, () -> new AgentRoleTomlCodec().parse("name = 'Role'", null));
        assertThrows(
                IllegalArgumentException.class,
                () -> new AgentRoleTomlCodec()
                        .parse("name = 'Role'\nmodel = '  '\n", AgentRoleFileFormat.CODEX_PORTABLE));
    }

    @Test
    void portableModeUnderstandsMultilineInstructionsAndLeavesModelSelectionExplicit() {
        AgentRoleTomlCodec.ParsedRole parsed = codec.parse("""
                name = 'reader'
                description = "Read evidence"
                developer_instructions = '''
                First line.
                Second line.
                '''
                model = "shared-model"
                model_reasoning_effort = "high"
                """, AgentRoleFileFormat.CODEX_PORTABLE);
        assertEquals("First line.\nSecond line.", parsed.spec().developerInstructions());
        assertEquals("shared-model", parsed.unresolvedModel().orElseThrow());
        assertTrue(parsed.spec().model().isEmpty());
        assertEquals(ReasoningPreference.HIGH, parsed.spec().reasoning().orElseThrow());
        assertEquals(PermissionConstraint.INHERIT, parsed.spec().permissionConstraint());
    }

    @Test
    void losslessRoundTripPreservesNarrowingPreciseProviderAndUnknownNamespaceValues() {
        String source = """
                name = "custom"
                developer_instructions = "Text with \\"quotes\\" and newline\\nHere"
                model = "model-a"
                [javaclaw]
                schema_version = 1
                id = "custom"
                revision = 4
                provider_id = "provider-a"
                provider_revision = 3
                permission_constraint = "READ_ONLY"
                capabilities = ["read_file"]
                skills = []
                [extensions.vendor]
                label = "retained"
                nested = { enabled = true, numbers = [1, 2] }
                moment = 2026-09-07T10:00:00Z
                time = 10:00:00
                unlimited = inf
                missing = nan
                [extensions."org.example"]
                flag = false
                """;
        AgentRoleTomlCodec.ParsedRole parsed = codec.parse(source, AgentRoleFileFormat.JAVACLAW_LOSSLESS);
        AgentRole role =
                new AgentRole("custom", 4, RoleLifecycle.ACTIVE, parsed.spec(), false, Instant.EPOCH, Instant.EPOCH);
        String exported = codec.export(role, AgentRoleFileFormat.JAVACLAW_LOSSLESS);
        AgentRoleTomlCodec.ParsedRole roundTrip = codec.parse(exported, AgentRoleFileFormat.JAVACLAW_LOSSLESS);
        assertEquals(parsed.spec(), roundTrip.spec());
        assertEquals(PermissionConstraint.READ_ONLY, roundTrip.spec().permissionConstraint());
        assertTrue(roundTrip.spec().narrowing().skills().orElseThrow().isEmpty());
        assertEquals(3, roundTrip.spec().model().orElseThrow().provider().endpointRevision());
        assertTrue(exported.contains("[extensions.vendor]"));
        assertFalse(codec.export(role, AgentRoleFileFormat.CODEX_PORTABLE).contains("[javaclaw]"));
    }

    @Test
    void filesCannotSmugglePermissionsCredentialsPathsOrUnknownCoreFields() {
        for (String field : java.util.List.of(
                "permission_profile = 'admin'",
                "secret = 'hidden'",
                "command = '/bin/sh'",
                "unknown = true",
                "name = 'duplicate'")) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> codec.parse("name = 'custom'\n" + field, AgentRoleFileFormat.CODEX_PORTABLE));
        }
        for (String field : java.util.List.of(
                "credential_ref = 'ref'",
                "api_key = 'key'",
                "permission = 'all'",
                "token = 'opaque'",
                "binary = '/bin/sh'",
                "binary = 'C:\\\\Windows\\\\run.exe'",
                "value = 'sk-example'")) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> codec.parse("""
                    name = 'custom'
                    [javaclaw]
                    schema_version = 1
                    [extensions.vendor]
                    """ + field, AgentRoleFileFormat.JAVACLAW_LOSSLESS));
        }
    }

    @Test
    void parserRejectsUnsupportedVersionsMissingProviderPartsAndMalformedUtf8() {
        assertThrows(
                IllegalArgumentException.class, () -> codec.parse("name = 'x'", AgentRoleFileFormat.JAVACLAW_LOSSLESS));
        assertThrows(
                IllegalArgumentException.class,
                () -> codec.parse("name = 'x'\n[javaclaw]\nschema_version = 2", AgentRoleFileFormat.JAVACLAW_LOSSLESS));
        assertThrows(
                IllegalArgumentException.class,
                () -> codec.parse(
                        "name = 'x'\n[javaclaw]\nschema_version = 1\nprovider_id = 'p'",
                        AgentRoleFileFormat.JAVACLAW_LOSSLESS));
        assertThrows(
                IllegalArgumentException.class,
                () -> codec.parse("name = 'x'\n#" + "a".repeat(1_048_576), AgentRoleFileFormat.CODEX_PORTABLE));
        assertThrows(
                IllegalArgumentException.class,
                () -> codec.parse("name = '\uD800'", AgentRoleFileFormat.CODEX_PORTABLE));
        assertEquals(
                AgentRoleTomlCodec.digest("name = 'x'\n"),
                codec.parse("\uFEFFname = 'x'\r\n", AgentRoleFileFormat.CODEX_PORTABLE)
                        .contentDigest());
    }
}
