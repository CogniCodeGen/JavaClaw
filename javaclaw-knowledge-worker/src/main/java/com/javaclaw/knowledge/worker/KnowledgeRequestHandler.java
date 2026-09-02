package com.javaclaw.knowledge.worker;

import java.util.Arrays;
import java.util.Objects;

import com.javaclaw.builtin.contracts.KnowledgeWorkerProtocol;

/** 把一个已完成 framing 校验的请求映射为无外部副作用的知识解析。 */
final class KnowledgeRequestHandler {
    private final KnowledgeExtractor extractor;

    KnowledgeRequestHandler(KnowledgeExtractor extractor) {
        this.extractor = Objects.requireNonNull(extractor, "extractor");
    }

    KnowledgeWorkerProtocol.Response handle(KnowledgeWorkerProtocol.Request request, byte[] content) {
        byte[] owned = Objects.requireNonNull(content, "content").clone();
        try {
            return KnowledgeWorkerProtocol.Response.success(extractor.extract(request, owned));
        } catch (Exception failure) {
            return KnowledgeWorkerProtocol.Response.failure("KNOWLEDGE_EXTRACTION_FAILED");
        } finally {
            Arrays.fill(owned, (byte) 0);
        }
    }
}
