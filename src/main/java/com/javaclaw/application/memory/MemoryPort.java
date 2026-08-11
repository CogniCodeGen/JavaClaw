package com.javaclaw.application.memory;

import com.javaclaw.application.memory.MemoryApplicationService.PersonaDraft;
import com.javaclaw.application.memory.MemoryApplicationService.Snapshot;
import com.javaclaw.memory.graph.MemoryGraph;

import java.nio.file.Path;
import java.util.List;

/** 记忆对象存储、知识库索引和原子文件写入的工作区端口。 */
public interface MemoryPort {
    Snapshot load();
    String probeEmbedding();
    int promoteAllPending();
    MemoryGraph graph();
    void addFact(String section, String text);
    void editFact(String id, String text);
    void toggleFactPin(String id);
    void restoreFact(String id);
    int deleteFacts(List<String> ids);
    int reindexDocument(String name);
    int deleteDocument(String name);
    void savePersona(PersonaDraft persona);
    String personaMarkdown(PersonaDraft persona);
    void exportPersona(Path target, String markdown);
    void revokeCorrection(String id);
    void deleteCorrection(String id);
}
