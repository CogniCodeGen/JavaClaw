package com.javaclaw.application.knowledge;

import com.javaclaw.application.knowledge.KnowledgeApplicationService.Document;
import com.javaclaw.application.knowledge.KnowledgeApplicationService.Health;
import com.javaclaw.application.knowledge.KnowledgeApplicationService.HealthListener;
import com.javaclaw.application.knowledge.KnowledgeApplicationService.Scope;
import com.javaclaw.application.knowledge.KnowledgeApplicationService.SearchHit;

import java.nio.file.Path;
import java.util.List;

/** 知识对象存储、嵌入检索和文档偏好的工作区端口。 */
public interface KnowledgePort {
    boolean enabled();
    String initializationError();
    Health health();
    AutoCloseable observeHealth(HealthListener listener);
    List<Document> documents();
    List<SearchHit> search(String query, int limit);
    boolean importFile(Path file, Scope scope);
    boolean importText(String title, String text, Scope scope);
    void setDocumentEnabled(String name, boolean enabled);
    void setAllEnabled(boolean enabled, Scope scope);
    int deleteDocument(String name);
    int clear();
    int rebuildIndex();
}
