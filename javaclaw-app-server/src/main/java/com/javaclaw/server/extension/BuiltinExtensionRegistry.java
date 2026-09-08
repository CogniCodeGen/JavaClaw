package com.javaclaw.server.extension;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import com.javaclaw.extension.spi.ExtensionBundle;
import com.javaclaw.extension.spi.ExtensionContext;
import com.javaclaw.extension.spi.ExtensionContribution;
import com.javaclaw.extension.spi.ExtensionDescriptor;
import com.javaclaw.extension.spi.ExtensionJobRegistration;
import com.javaclaw.extension.spi.ExtensionJobRuntimeContext;
import com.javaclaw.extension.spi.ExtensionTrust;

/** 负责可信内置扩展的启动、索引与逆序关闭。 */
final class BuiltinExtensionRegistry {
    private BuiltinExtensionRegistry() {}

    static RegisteredExtension register(ExtensionBundle bundle, BuiltinExtensionRuntimePorts ports) throws Exception {
        ExtensionDescriptor descriptor =
                Objects.requireNonNull(bundle, "bundle").descriptor();
        if (descriptor.requirements().trust() != ExtensionTrust.BUILT_IN) {
            throw new IllegalArgumentException("third-party extension cannot run in-process");
        }
        try {
            List<ExtensionContribution> contributions =
                    List.copyOf(bundle.start(new ExtensionContext(ports.clock(), ports.payloads())));
            ExtensionContributionIndex index =
                    ExtensionContributionIndex.create(descriptor, contributions, ports.payloads());
            if (!index.kinds().equals(descriptor.contributionKinds())) {
                throw new IllegalArgumentException("extension contribution declaration differs from runtime: "
                        + descriptor.id().value());
            }
            List<ExtensionJobRegistration> jobExecutors =
                    List.copyOf(bundle.jobExecutors(new ExtensionJobRuntimeContext(
                            ports.clock(),
                            ports.payloads(),
                            ports.turns(),
                            ports.managedStore(),
                            ports.services(),
                            ports.embeddings(),
                            ports.automationSteps(),
                            ports.scheduledCommands(),
                            ports.scheduleLifecycle(),
                            ports.conversationEvidence(),
                            ports.scheduleBindings().apply(descriptor.id()))));
            requireUniqueJobTypes(jobExecutors);
            return new RegisteredExtension(
                    descriptor,
                    bundle,
                    index.tools(),
                    index.queries(),
                    index.commands(),
                    index.schedulableActions(),
                    index.actionCatalog(),
                    index.definitionCatalog(),
                    index.views(),
                    List.copyOf(bundle.schemas()),
                    jobExecutors);
        } catch (Exception failure) {
            closeFailedRegistration(bundle, failure);
            throw failure;
        }
    }

    private static void requireUniqueJobTypes(List<ExtensionJobRegistration> registrations) {
        Set<String> types = new HashSet<>();
        for (ExtensionJobRegistration registration : registrations) {
            if (!types.add(
                    Objects.requireNonNull(registration, "job registration").jobType())) {
                throw new IllegalArgumentException("duplicate extension Job type");
            }
        }
    }

    static void validateGlobalTools(Collection<RegisteredExtension> extensions) {
        Set<String> names = new HashSet<>();
        for (RegisteredExtension extension : extensions) {
            for (String name : extension.tools().keySet()) {
                if (!names.add(name)) {
                    throw new IllegalArgumentException("duplicate global tool name: " + name);
                }
            }
        }
    }

    static void closeReverse(List<RegisteredExtension> values, Exception original) throws Exception {
        Exception failure = original;
        for (int index = values.size() - 1; index >= 0; index--) {
            try {
                values.get(index).bundle().close();
            } catch (Exception closeFailure) {
                failure = append(failure, closeFailure);
            }
        }
        if (original == null && failure != null) {
            throw failure;
        }
    }

    private static void closeFailedRegistration(ExtensionBundle bundle, Exception original) {
        try {
            bundle.close();
        } catch (Exception closeFailure) {
            original.addSuppressed(closeFailure);
        }
    }

    private static Exception append(Exception failure, Exception closeFailure) {
        if (failure == null) {
            return closeFailure;
        }
        failure.addSuppressed(closeFailure);
        return failure;
    }
}
