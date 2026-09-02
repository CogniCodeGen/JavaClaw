package com.javaclaw.server.extension;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.javaclaw.extension.spi.ContributionKind;
import com.javaclaw.extension.spi.ExtensionBundle;
import com.javaclaw.extension.spi.ExtensionContributions;
import com.javaclaw.extension.spi.ExtensionDescriptor;
import com.javaclaw.extension.spi.ExtensionHandler;
import com.javaclaw.extension.spi.ExtensionJobRegistration;
import com.javaclaw.extension.spi.ExtensionSchema;
import com.javaclaw.extension.spi.ViewSchema;

/** 已校验并建立调用索引的可信内置扩展。 */
record RegisteredExtension(
        ExtensionDescriptor descriptor,
        ExtensionBundle bundle,
        Map<String, ExtensionContributions.Tool> tools,
        Map<String, ExtensionHandler> queries,
        Map<String, ExtensionHandler> commands,
        Set<String> schedulableActions,
        List<ExtensionContributions.SchedulableAction> actionCatalog,
        List<ExtensionContributions.SchedulableDefinition> definitionCatalog,
        List<ViewSchema> views,
        List<ExtensionSchema> schemas,
        List<ExtensionJobRegistration> jobExecutors) {
    ExtensionHandler handler(ContributionKind kind, String operation) {
        Map<String, ExtensionHandler> handlers =
                switch (kind) {
                    case QUERY -> queries;
                    case COMMAND -> commands;
                    default -> throw new IllegalArgumentException("unsupported invocation kind: " + kind);
                };
        ExtensionHandler handler = handlers.get(operation);
        if (handler == null) {
            throw new IllegalArgumentException("extension operation does not exist: " + operation);
        }
        return handler;
    }
}
