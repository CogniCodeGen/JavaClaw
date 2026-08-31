package com.javaclaw.server.persistence;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.javaclaw.agent.prompt.PromptCatalog;
import com.javaclaw.agent.prompt.PromptSnapshot;
import com.javaclaw.core.api.ThreadId;
import com.javaclaw.core.api.TurnId;

/** 内置模板的不可变运行档案与逐调用快照；H2 不成为第二个可编辑模板源。 */
public final class H2PromptArchive {
    private static final String SOURCE_COMMIT = "dc2ccc6843abb09c9d297862dc10b6bd12a3935d";
    private final H2Database database;
    private final ObjectMapper json = new ObjectMapper();

    /** 使用唯一 H2Database 并归档发行模板；同版本不同哈希拒绝启动，避免静默改写历史。 */
    public H2PromptArchive(H2Database database) {
        this.database = Objects.requireNonNull(database, "database");
        var templates = new PromptCatalog().list();
        database.transaction(connection -> {
            for (var template : templates) {
                try (var read = connection.prepareStatement(
                        "SELECT sha256 FROM prompt_template_archive WHERE template_id=? AND version=?")) {
                    read.setString(1, template.id());
                    read.setInt(2, template.version());
                    try (var row = read.executeQuery()) {
                        if (row.next()) {
                            if (!row.getString(1).equals(template.sha256())) {
                                throw new IllegalStateException("built-in prompt version collision: " + template.id());
                            }
                            continue;
                        }
                    }
                }
                try (var insert = connection.prepareStatement(
                        "INSERT INTO prompt_template_archive(template_id,version,sha256,content,source_commit) VALUES (?,?,?,?,?)")) {
                    insert.setString(1, template.id());
                    insert.setInt(2, template.version());
                    insert.setString(3, template.sha256());
                    insert.setString(4, template.content());
                    insert.setString(5, SOURCE_COMMIT);
                    insert.executeUpdate();
                }
            }
            return null;
        });
    }

    /** 在发起模型请求前保存摘要；锁定所属 Turn，禁止取消/终态后新增调用，不消耗持久事件 sequence。 */
    public void record(ThreadId threadId, TurnId turnId, PromptSnapshot snapshot, int ordinal) {
        if (ordinal < 1) {
            throw new IllegalArgumentException("model invocation ordinal must be positive");
        }
        String encoded;
        try {
            encoded = json.writeValueAsString(snapshot);
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("invalid prompt snapshot", failure);
        }
        database.transaction(connection -> {
            try (var query =
                    connection.prepareStatement("SELECT thread_id,status FROM turns WHERE turn_id=? FOR UPDATE")) {
                query.setString(1, turnId.value());
                try (var row = query.executeQuery()) {
                    if (!row.next()
                            || !threadId.value().equals(row.getString(1))
                            || !List.of("IN_PROGRESS", "WAITING_FOR_INPUT", "WAITING_FOR_APPROVAL")
                                    .contains(row.getString(2))) {
                        throw new IllegalStateException("model invocation requires an active matching Turn");
                    }
                }
            }
            try (var insert = connection.prepareStatement(
                    "INSERT INTO turn_prompt_snapshots(turn_id,invocation_ordinal,purpose,compiled_sha256,snapshot_json,created_at) VALUES (?,?,?,?,?,?)")) {
                insert.setString(1, turnId.value());
                insert.setInt(2, ordinal);
                insert.setString(3, snapshot.purpose().name());
                insert.setString(4, snapshot.compiledSha256());
                insert.setString(5, encoded);
                insert.setLong(6, System.currentTimeMillis());
                insert.executeUpdate();
            }
            return null;
        });
    }

    /** 返回有序的脱敏模型调用快照，不读取或返回 Profile、AGENTS.md 及资料正文。 */
    public List<PromptSnapshot> list(TurnId turnId) {
        return database.query(connection -> {
            List<PromptSnapshot> result = new ArrayList<>();
            try (var query = connection.prepareStatement(
                    "SELECT snapshot_json FROM turn_prompt_snapshots WHERE turn_id=? ORDER BY invocation_ordinal")) {
                query.setString(1, turnId.value());
                try (var rows = query.executeQuery()) {
                    while (rows.next()) {
                        try {
                            result.add(json.readValue(rows.getString(1), PromptSnapshot.class));
                        } catch (JsonProcessingException failure) {
                            throw new IllegalStateException("corrupt prompt archive", failure);
                        }
                    }
                }
            }
            return List.copyOf(result);
        });
    }
}
