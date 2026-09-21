package com.javaclaw.memory;

import com.javaclaw.memory.store.MemoryStore;
import com.javaclaw.memory.embed.EmbeddingGateway;
import com.javaclaw.memory.embed.EmbeddingPurpose;
import java.util.List;

/** User-authored fact and persona edits on one already selected graph. */
final class MemoryFactEditor {
    private MemoryFactEditor() {}
    static void editFact(MemoryStore store, EmbeddingGateway gate, com.javaclaw.memory.model.Fact f, String newText) {
        if (store == null) return;
        float[] vec = gate.embed(newText, EmbeddingPurpose.BACKGROUND_INDEX);
        if (f.pending) {
            if (vec != null) {
                // 嵌入恢复：迁入正式索引
                f.text = newText;
                f.embedding = vec;
                f.userEdited = true;
                f.userAsserted = true;
                f.sourceKind = "USER_MANUAL";
                f.superseded = false; // 用户显式编辑 = 断言现行有效，复活被取代的事实
                f.contested = false;
                f.pending = false;
                store.removePendingFact(f, "user");
                store.addFact(f, "user");
            } else {
                store.updatePendingFact(f, x -> {
                    x.text = newText;
                    x.userEdited = true;
                    x.userAsserted = true;
                    x.sourceKind = "USER_MANUAL";
                    x.superseded = false;
                    x.contested = false;
                }, "user");
            }
            return;
        }
        store.updateFact(f, x -> {
            x.text = newText;
            if (vec != null) x.embedding = vec;
            x.userEdited = true;
            x.userAsserted = true;
            x.sourceKind = "USER_MANUAL";
            x.superseded = false; // 用户显式编辑 = 断言现行有效，复活被取代的事实（userEdited 保护契约优先于软删除）
            x.contested = false;
        }, "user");
    }

    static void addFact(MemoryStore store, EmbeddingGateway gate, String section, String text) {
        if (store == null || text == null || text.isBlank()) return;
        float[] vec = gate.embed(text, EmbeddingPurpose.BACKGROUND_INDEX);
        com.javaclaw.memory.model.Fact f = new com.javaclaw.memory.model.Fact(
                section == null || section.isBlank() ? "其它" : section.trim(), text.trim(), vec);
        f.userEdited = true; // 手动新增等同用户保护，蒸馏不得静默覆盖
        f.userAsserted = true;
        f.sourceKind = "USER_MANUAL";
        if (vec != null) store.addFact(f, "user");
        else store.addPendingFact(f, "user");
    }

    static void setPersonaStructured(MemoryStore store, String identity, String tone,
                                     List<String> preferences, List<String> taboos) {
        if (store == null) return;
        List<String> prefs = preferences == null ? List.of() : preferences;
        List<String> tabs = taboos == null ? List.of() : taboos;
        String content = assemblePersona(identity, tone, prefs, tabs);
        store.updatePersona(p -> {
            p.structured = true;
            p.identity = identity;
            p.tone = tone;
            p.preferences = new java.util.ArrayList<>(prefs);
            p.taboos = new java.util.ArrayList<>(tabs);
            p.content = content;
        }, "user");
    }

    static String assemblePersona(String identity, String tone,
                                         List<String> preferences, List<String> taboos) {
        StringBuilder sb = new StringBuilder("# 人格\n");
        if (identity != null && !identity.isBlank()) {
            sb.append("\n## 身份\n").append(identity.strip()).append('\n');
        }
        if (tone != null && !tone.isBlank()) {
            sb.append("\n## 语气\n").append(tone.strip()).append('\n');
        }
        if (preferences != null && !preferences.isEmpty()) {
            sb.append("\n## 偏好\n");
            for (String p : preferences) {
                if (p != null && !p.isBlank()) sb.append("- ").append(p.strip()).append('\n');
            }
        }
        if (taboos != null && !taboos.isEmpty()) {
            sb.append("\n## 禁忌\n");
            for (String t : taboos) {
                if (t != null && !t.isBlank()) sb.append("- ").append(t.strip()).append('\n');
            }
        }
        return sb.toString();
    }
}
