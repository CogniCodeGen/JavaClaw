package com.javaclaw.application.knowledge;

import java.util.Set;

/** 当前工作区不参与检索文档集合的事务持久化端口。 */
public interface KnowledgeDocumentPreferencePort {
    Set<String> loadExcluded();
    void replaceExcluded(Set<String> documentNames);
}
