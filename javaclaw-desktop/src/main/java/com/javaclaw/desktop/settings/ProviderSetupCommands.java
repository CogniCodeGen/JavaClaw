package com.javaclaw.desktop.settings;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import com.javaclaw.api.ProviderEndpointSpec;
import com.javaclaw.api.ProviderLifecycle;
import com.javaclaw.api.ProviderModelSpec;
import com.javaclaw.client.CommandOptions;

/** 为 Provider 首次配置的可恢复步骤生成不含 Secret 的确定性命令。 */
final class ProviderSetupCommands {
    private ProviderSetupCommands() {}

    static ProviderEndpointSpec connectionShell(ProviderEndpointSpec requested) {
        ProviderEndpointSpec checked = Objects.requireNonNull(requested, "requested");
        return new ProviderEndpointSpec(
                checked.displayName(),
                checked.adapter(),
                checked.baseUri(),
                checked.authentication(),
                List.of(),
                Optional.empty(),
                checked.timeout(),
                checked.maximumRetries(),
                checked.options());
    }

    static CommandOptions createShell(String providerId, ProviderEndpointSpec shell) {
        return options(providerId, "connection", 0, material(shell, ProviderLifecycle.DISABLED));
    }

    static CommandOptions configureCredential(String providerId, long providerRevision, long credentialRevision) {
        return options(providerId, "credential", providerRevision, providerRevision + "\n" + credentialRevision);
    }

    static CommandOptions finish(
            String providerId, long expectedRevision, ProviderEndpointSpec spec, ProviderLifecycle lifecycle) {
        return options(providerId, "models-enable", expectedRevision, material(spec, lifecycle));
    }

    private static CommandOptions options(String providerId, String operation, long expectedRevision, String value) {
        String material = Objects.requireNonNull(providerId, "providerId")
                + '\n'
                + Objects.requireNonNull(operation, "operation")
                + '\n'
                + Objects.requireNonNull(value, "value");
        return new CommandOptions("provider-setup." + operation + '.' + digest(material), expectedRevision);
    }

    private static String material(ProviderEndpointSpec spec, ProviderLifecycle lifecycle) {
        return spec.displayName()
                + '\n'
                + spec.adapter()
                + '\n'
                + spec.baseUri().map(Object::toString).orElse("")
                + '\n'
                + spec.authentication()
                + '\n'
                + models(spec.models())
                + '\n'
                + spec.credential().map(Object::toString).orElse("")
                + '\n'
                + spec.timeout()
                + '\n'
                + spec.maximumRetries()
                + '\n'
                + spec.options()
                + '\n'
                + Objects.requireNonNull(lifecycle, "lifecycle");
    }

    private static String models(List<ProviderModelSpec> models) {
        return models.stream()
                .sorted(Comparator.comparing(ProviderModelSpec::modelId))
                .map(model -> model.modelId()
                        + '|'
                        + model.displayName()
                        + '|'
                        + model.purposes().stream().sorted().map(Enum::name).collect(Collectors.joining(","))
                        + '|'
                        + (model.embeddingDimensions().isPresent()
                                ? model.embeddingDimensions().getAsInt()
                                : ""))
                .collect(Collectors.joining("\n"));
    }

    private static String digest(String material) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 不可用", impossible);
        }
    }
}
