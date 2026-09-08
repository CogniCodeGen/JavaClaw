package com.javaclaw.desktop.document;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionStage;

import com.javaclaw.api.DocumentPreview;
import com.javaclaw.api.DocumentReference;

/** 从可信聊天引用逐段重放相对导航；重读时每个 href 都重新经过服务端校验。 */
record DocumentPreviewRoute(DocumentReference source, List<String> resources) {
    DocumentPreviewRoute {
        resources = List.copyOf(resources);
        if (resources.size() > 16) {
            throw new IllegalArgumentException("文档相对导航最多 16 层，请从聊天引用重新打开");
        }
    }

    DocumentPreviewRoute append(String href) {
        var path = new ArrayList<>(resources);
        path.add(href);
        return new DocumentPreviewRoute(source, path);
    }

    CompletionStage<DocumentPreview> resolve(DocumentPreviewGateway gateway) {
        CompletionStage<DocumentPreview> result = gateway.resolve(source);
        for (String href : resources) {
            result = result.thenCompose(parent -> gateway.resource(parent.handleId(), href)
                    .whenComplete((child, failure) -> gateway.close(parent.handleId())));
        }
        return result;
    }
}
