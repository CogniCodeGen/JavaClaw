package com.javaclaw.framework.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.javaclaw.framework.api.*;
import com.javaclaw.framework.spi.AgentStudioRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;

/** H2 draft/history store and published-only resolver used by AgentCompiler. */
public final class JdbcAgentDefinitionStore implements AgentStudioRepository {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper json;
    private final Clock clock;

    public JdbcAgentDefinitionStore(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            ObjectMapper json,
            Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transactions = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager"));
        this.json = Objects.requireNonNull(json, "json");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public List<StudioDraft> listAgentDrafts(String workspaceId) {
        return listDrafts("agent_definitions", "agent", workspaceId);
    }

    @Override
    public List<StudioDraft> listProfileDrafts(String workspaceId) {
        return listDrafts("run_profiles", "profile", workspaceId);
    }

    private List<StudioDraft> listDrafts(String table, String kind, String workspaceId) {
        return jdbc.query("""
                SELECT id, name, draft_json, draft_revision, published_version, builtin, updated_at
                FROM %s
                WHERE workspace_id = ? AND archived = FALSE
                ORDER BY builtin DESC, name, id
                """.formatted(table), (row, index) -> new StudioDraft(
                row.getString("id"), row.getString("name"), kind,
                readTree(row.getString("draft_json")), row.getLong("draft_revision"),
                row.getLong("published_version"), row.getBoolean("builtin"),
                Instant.ofEpochMilli(row.getLong("updated_at"))), workspaceId);
    }

    @Override
    public StudioDraft saveAgentDraft(
            String workspaceId, AgentDefinitionDraft draft, boolean builtinWrite) {
        return saveDraft("agent_definitions", "agent", workspaceId, draft.id(), draft.name(),
                json.valueToTree(draft), builtinWrite);
    }

    @Override
    public StudioDraft saveProfileDraft(
            String workspaceId, RunProfileDraft draft, boolean builtinWrite) {
        return saveDraft("run_profiles", "profile", workspaceId, draft.id(), draft.name(),
                json.valueToTree(draft), builtinWrite);
    }

    public AgentDefinition installBuiltinAgent(String workspaceId, AgentDefinitionDraft draft) {
        saveAgentDraft(workspaceId, draft, true);
        StudioDraft stored = getAgentDraft(workspaceId, draft.id());
        if (stored.publishedVersion() > 0) {
            try {
                AgentDefinition current = resolveAgent(
                        workspaceId, new AgentDefinitionRef(draft.id(), stored.publishedVersion()));
                AgentDefinitionDraft publishedDraft = new AgentDefinitionDraft(
                        current.id(), current.name(), current.modelPolicyRef(), current.promptSections(),
                        current.capabilityBindings(), current.toolPolicy(), current.permissionPolicy(),
                        current.budgetPolicy(), current.outputContract(), current.compatibleExtensionRanges());
                if (canonical(draft).equals(canonical(publishedDraft))) return current;
            } catch (NoSuchElementException missingPublishedVersion) {
                // Repair a development-era partial row by publishing a new immutable version.
            }
        }
        return publishAgent(workspaceId, draft.id(), true);
    }

    public RunProfile installBuiltinProfile(String workspaceId, RunProfileDraft draft) {
        saveProfileDraft(workspaceId, draft, true);
        StudioDraft stored = getProfileDraft(workspaceId, draft.id());
        if (stored.publishedVersion() > 0) {
            try {
                RunProfile current = resolveProfile(
                        workspaceId, new RunProfileRef(draft.id(), stored.publishedVersion()));
                RunProfileDraft publishedDraft = new RunProfileDraft(
                        current.id(), current.name(), current.permissionCeiling(), current.budget(),
                        current.capabilityOverrides(), current.failurePolicy());
                if (canonical(draft).equals(canonical(publishedDraft))) return current;
            } catch (NoSuchElementException missingPublishedVersion) {
                // Repair a development-era partial row by publishing a new immutable version.
            }
        }
        return publishProfile(workspaceId, draft.id(), true);
    }

    /** Installs one complete built-in generation or commits none of it. */
    public AgentDefinition installBuiltinDefinitions(
            String workspaceId,
            List<RunProfileDraft> profiles,
            AgentDefinitionDraft agent) {
        String workspace = Objects.requireNonNull(workspaceId, "workspaceId");
        List<RunProfileDraft> checkedProfiles = List.copyOf(
                Objects.requireNonNull(profiles, "profiles"));
        AgentDefinitionDraft checkedAgent = Objects.requireNonNull(agent, "agent");
        return transactions.execute(status -> {
            for (RunProfileDraft profile : checkedProfiles) {
                installBuiltinProfile(workspace, profile);
            }
            return installBuiltinAgent(workspace, checkedAgent);
        });
    }

    @Override
    public StudioDraft getAgentDraft(String workspaceId, String id) {
        return getDraft("agent_definitions", "agent", workspaceId, id);
    }

    @Override
    public StudioDraft getProfileDraft(String workspaceId, String id) {
        return getDraft("run_profiles", "profile", workspaceId, id);
    }

    @Override
    public AgentDefinition publishAgent(String workspaceId, String id) {
        return publishAgent(workspaceId, id, false);
    }

    private AgentDefinition publishAgent(String workspaceId, String id, boolean builtinWrite) {
        return transactions.execute(status -> {
            StudioRow row = lockRow("agent_definitions", workspaceId, id);
            assertWritable(row, builtinWrite);
            AgentDefinitionDraft draft = read(row.document(), AgentDefinitionDraft.class);
            long version = row.publishedVersion() + 1;
            String checksum = checksum(json.valueToTree(draft), version);
            AgentDefinition definition = new AgentDefinition(
                    draft.id(), version, draft.name(), draft.modelPolicyRef(),
                    draft.promptSections(), draft.capabilityBindings(), draft.toolPolicy(),
                    draft.permissionPolicy(), draft.budgetPolicy(), draft.outputContract(),
                    draft.compatibleExtensionRanges(), checksum);
            long now = clock.millis();
            jdbc.update("""
                    INSERT INTO agent_definition_versions(
                        workspace_id, definition_id, version, definition_json, checksum, published_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, workspaceId, id, version, write(definition), checksum, now);
            jdbc.update("""
                    UPDATE agent_definitions SET published_version = ?, updated_at = ?
                    WHERE workspace_id = ? AND id = ?
                    """, version, now, workspaceId, id);
            return definition;
        });
    }

    @Override
    public RunProfile publishProfile(String workspaceId, String id) {
        return publishProfile(workspaceId, id, false);
    }

    private RunProfile publishProfile(String workspaceId, String id, boolean builtinWrite) {
        return transactions.execute(status -> {
            StudioRow row = lockRow("run_profiles", workspaceId, id);
            assertWritable(row, builtinWrite);
            RunProfileDraft draft = read(row.document(), RunProfileDraft.class);
            long version = row.publishedVersion() + 1;
            String checksum = checksum(json.valueToTree(draft), version);
            RunProfile profile = new RunProfile(
                    draft.id(), version, draft.name(), draft.permissionCeiling(), draft.budget(),
                    draft.capabilityOverrides(), draft.failurePolicy(), checksum);
            long now = clock.millis();
            jdbc.update("""
                    INSERT INTO run_profile_versions(
                        workspace_id, profile_id, version, profile_json, checksum, published_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, workspaceId, id, version, write(profile), checksum, now);
            jdbc.update("""
                    UPDATE run_profiles SET published_version = ?, updated_at = ?
                    WHERE workspace_id = ? AND id = ?
                    """, version, now, workspaceId, id);
            return profile;
        });
    }

    @Override
    public AgentDefinition resolveAgent(String workspaceId, AgentDefinitionRef reference) {
        String sql = reference.version() == null
                ? """
                  SELECT definition_json FROM agent_definition_versions
                  WHERE workspace_id = ? AND definition_id = ?
                  ORDER BY version DESC LIMIT 1
                  """
                : """
                  SELECT definition_json FROM agent_definition_versions
                  WHERE workspace_id = ? AND definition_id = ? AND version = ?
                  """;
        Object[] arguments = reference.version() == null
                ? new Object[]{workspaceId, reference.id()}
                : new Object[]{workspaceId, reference.id(), reference.version()};
        return jdbc.query(sql, (row, index) -> read(
                        row.getString("definition_json"), AgentDefinition.class), arguments)
                .stream().findFirst().orElseThrow(() -> new NoSuchElementException(
                        "published agent definition not found: " + reference));
    }

    @Override
    public RunProfile resolveProfile(String workspaceId, RunProfileRef reference) {
        String sql = reference.version() == null
                ? """
                  SELECT profile_json FROM run_profile_versions
                  WHERE workspace_id = ? AND profile_id = ?
                  ORDER BY version DESC LIMIT 1
                  """
                : """
                  SELECT profile_json FROM run_profile_versions
                  WHERE workspace_id = ? AND profile_id = ? AND version = ?
                  """;
        Object[] arguments = reference.version() == null
                ? new Object[]{workspaceId, reference.id()}
                : new Object[]{workspaceId, reference.id(), reference.version()};
        return jdbc.query(sql, (row, index) -> read(
                        row.getString("profile_json"), RunProfile.class), arguments)
                .stream().findFirst().orElseThrow(() -> new NoSuchElementException(
                        "published run profile not found: " + reference));
    }

    @Override
    public List<AgentDefinition> agentHistory(String workspaceId, String id) {
        return jdbc.query("""
                SELECT definition_json FROM agent_definition_versions
                WHERE workspace_id = ? AND definition_id = ? ORDER BY version DESC
                """, (row, index) -> read(row.getString(1), AgentDefinition.class), workspaceId, id);
    }

    @Override
    public List<RunProfile> profileHistory(String workspaceId, String id) {
        return jdbc.query("""
                SELECT profile_json FROM run_profile_versions
                WHERE workspace_id = ? AND profile_id = ? ORDER BY version DESC
                """, (row, index) -> read(row.getString(1), RunProfile.class), workspaceId, id);
    }

    @Override
    public AgentDefinition findAgentVersion(String workspaceId, String id, long version) {
        return resolveAgent(workspaceId, new AgentDefinitionRef(id, version));
    }

    @Override
    public RunProfile findProfileVersion(String workspaceId, String id, long version) {
        return resolveProfile(workspaceId, new RunProfileRef(id, version));
    }

    @Override
    public boolean archiveAgent(String workspaceId, String id) {
        Boolean archived = transactions.execute(status -> {
            List<ArchiveRow> rows = jdbc.query("""
                    SELECT builtin, archived FROM agent_definitions
                    WHERE workspace_id = ? AND id = ? FOR UPDATE
                    """, (row, index) -> new ArchiveRow(
                    row.getBoolean("builtin"), row.getBoolean("archived")), workspaceId, id);
            if (rows.isEmpty()) return false;
            ArchiveRow row = rows.getFirst();
            if (row.builtin()) {
                throw new IllegalStateException("built-in definitions are read-only");
            }
            if (row.archived()) return false;
            return jdbc.update("""
                    UPDATE agent_definitions SET archived = TRUE, updated_at = ?
                    WHERE workspace_id = ? AND id = ?
                    """, clock.millis(), workspaceId, id) == 1;
        });
        return Boolean.TRUE.equals(archived);
    }

    private StudioDraft saveDraft(
            String table,
            String kind,
            String workspaceId,
            String id,
            String name,
            JsonNode document,
            boolean builtinWrite) {
        return transactions.execute(status -> {
            List<StudioRow> rows = jdbc.query("SELECT * FROM " + table
                            + " WHERE workspace_id = ? AND id = ? FOR UPDATE",
                    (row, index) -> new StudioRow(
                            row.getString("draft_json"), row.getLong("draft_revision"),
                            row.getLong("published_version"), row.getBoolean("builtin"),
                            row.getLong("created_at"), row.getLong("updated_at")),
                    workspaceId, id);
            long now = clock.millis();
            if (rows.isEmpty()) {
                jdbc.update("INSERT INTO " + table + "(" +
                                "workspace_id,id,name,builtin,archived,draft_json,draft_revision," +
                                "published_version,created_at,updated_at) " +
                                "VALUES (?,?,?, ?,FALSE,?,1,0,?,?)",
                        workspaceId, id, name, builtinWrite, write(document), now, now);
            } else {
                StudioRow current = rows.getFirst();
                assertWritable(current, builtinWrite);
                jdbc.update("UPDATE " + table + " SET name=?, draft_json=?, "
                                + "draft_revision=draft_revision+1, updated_at=? "
                                + "WHERE workspace_id=? AND id=?",
                        name, write(document), now, workspaceId, id);
            }
            return getDraft(table, kind, workspaceId, id);
        });
    }

    private StudioDraft getDraft(String table, String kind, String workspaceId, String id) {
        return jdbc.query("SELECT * FROM " + table + " WHERE workspace_id = ? AND id = ?",
                (row, index) -> new StudioDraft(id, row.getString("name"), kind,
                        readTree(row.getString("draft_json")), row.getLong("draft_revision"),
                        row.getLong("published_version"), row.getBoolean("builtin"),
                        Instant.ofEpochMilli(row.getLong("updated_at"))), workspaceId, id)
                .stream().findFirst().orElseThrow(() ->
                        new NoSuchElementException(kind + " draft not found: " + id));
    }

    private StudioRow lockRow(String table, String workspaceId, String id) {
        return jdbc.query("SELECT * FROM " + table + " WHERE workspace_id = ? AND id = ? FOR UPDATE",
                (row, index) -> new StudioRow(
                        row.getString("draft_json"), row.getLong("draft_revision"),
                        row.getLong("published_version"), row.getBoolean("builtin"),
                        row.getLong("created_at"), row.getLong("updated_at")), workspaceId, id)
                .stream().findFirst().orElseThrow(() ->
                        new NoSuchElementException("draft not found: " + id));
    }

    private static void assertWritable(StudioRow row, boolean builtinWrite) {
        if (builtinWrite && !row.builtin()) {
            throw new IllegalStateException(
                    "built-in definition ID is occupied by a user draft");
        }
        if (row.builtin() && !builtinWrite) {
            throw new IllegalStateException("built-in definitions are read-only; clone before editing");
        }
        if (row.document() == null) throw new IllegalStateException("draft document is empty");
    }

    private String checksum(JsonNode document, long version) {
        return sha256(canonical(document) + "#" + version);
    }

    private String canonical(Object value) {
        try {
            return json.writer().with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                    .writeValueAsString(value);
        } catch (Exception failure) {
            throw new IllegalStateException("cannot serialize definition", failure);
        }
    }

    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalStateException("cannot serialize definition", e); }
    }

    private JsonNode readTree(String value) {
        try { return json.readTree(value); }
        catch (Exception e) { throw new IllegalStateException("cannot deserialize definition", e); }
    }

    private <T> T read(String value, Class<T> type) {
        try { return json.readValue(value, type); }
        catch (Exception e) { throw new IllegalStateException("cannot deserialize " + type.getSimpleName(), e); }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private record StudioRow(
            String document,
            long draftRevision,
            long publishedVersion,
            boolean builtin,
            long createdAt,
            long updatedAt) {}

    private record ArchiveRow(boolean builtin, boolean archived) {}
}
