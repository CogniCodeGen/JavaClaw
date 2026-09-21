package com.javaclaw.desktop.settings;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.CredentialMetadata;
import com.javaclaw.api.CredentialRef;
import com.javaclaw.api.ProviderConfiguration;
import com.javaclaw.api.ProviderConfigurationResult;
import com.javaclaw.api.ProviderCredentialChange;
import com.javaclaw.api.ProviderEndpoint;
import com.javaclaw.api.ProviderModelDiscoveryCandidate;
import com.javaclaw.api.ProviderModelPreviewRequest;
import com.javaclaw.api.ProviderModelPreviewResult;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.client.CommandOptions;
import com.javaclaw.client.facade.PreparedProviderConfiguration;
import com.javaclaw.desktop.DesktopNotificationSubscription;
import com.javaclaw.protocol.ProviderConfigurationRpcContracts;
import com.javaclaw.protocol.SealedSecret;

/** 固定数据配置替身；记录原子边界，不连接模型或真实凭据库。 */
class ProviderConfigurationTestGateway extends TestCoreSettingsGateway {
    final List<ProviderModelPreviewRequest> previews = new ArrayList<>();
    final List<CancellationToken> cancellations = new ArrayList<>();
    final List<PreparedProviderConfiguration> saved = new ArrayList<>();
    final List<PreparedProviderConfiguration> queried = new ArrayList<>();
    final Queue<CompletionStage<ProviderModelPreviewResult>> previewResponses = new ArrayDeque<>();
    final Queue<CompletionStage<ProviderConfigurationResult>> saveResponses = new ArrayDeque<>();
    final Queue<CompletionStage<Optional<ProviderConfigurationResult>>> receiptResponses = new ArrayDeque<>();
    CompletionStage<Boolean> supported = CompletableFuture.completedFuture(true);
    List<ProviderModelDiscoveryCandidate> candidates = List.of();
    ProviderConfiguration configuration;
    ProviderConfigurationResult committed;
    char[] submittedSecret;
    char[] previewSecret;
    int preparations;
    int uses;
    Runnable invalidated = () -> {};
    boolean subscriptionClosed;
    CompletionStage<PreparedProviderConfiguration> preparationResponse;

    @Override
    public DesktopNotificationSubscription onProviderConfigurationSessionInvalidated(Runnable listener) {
        invalidated = listener;
        return () -> subscriptionClosed = true;
    }

    @Override
    public CompletionStage<Boolean> providerConfigurationSupported() {
        return supported;
    }

    @Override
    public CompletionStage<ProviderModelPreviewResult> previewProviderModels(
            ProviderModelPreviewRequest request, char[] secret, CancellationToken cancellation) {
        previews.add(request);
        cancellations.add(cancellation);
        previewSecret = secret;
        Arrays.fill(secret, '\0');
        return previewResponses.isEmpty()
                ? CompletableFuture.completedFuture(previewResult(request))
                : previewResponses.remove();
    }

    ProviderModelPreviewResult previewResult(ProviderModelPreviewRequest request) {
        return new ProviderModelPreviewResult(
                request.draftId(), request.generation(), candidates, false, Instant.EPOCH);
    }

    @Override
    public CompletionStage<PreparedProviderConfiguration> prepareProviderConfiguration(
            ProviderConfiguration request, char[] secret, CommandOptions options) {
        configuration = request;
        preparations++;
        submittedSecret = secret;
        Arrays.fill(secret, '\0');
        Optional<SealedSecret> sealed = request.credentialChange() == ProviderCredentialChange.REPLACE
                ? Optional.of(new SealedSecret(
                        "fixture-key", ProviderConfigurationRpcContracts.SAVE_PURPOSE, "YWJj", "YWJj", "YWJj"))
                : Optional.empty();
        if (preparationResponse != null) {
            return preparationResponse;
        }
        return CompletableFuture.completedFuture(new PreparedProviderConfiguration(
                new ProviderConfigurationRpcContracts.SavePayload(request, sealed), options));
    }

    @Override
    public CompletionStage<ProviderConfigurationResult> saveProviderConfiguration(
            PreparedProviderConfiguration prepared) {
        saved.add(prepared);
        if (!saveResponses.isEmpty()) {
            return saveResponses.remove();
        }
        committed = result(prepared.payload().configuration());
        providers.removeIf(value -> value.id().equals(committed.provider().id()));
        providers.add(committed.provider());
        return CompletableFuture.completedFuture(committed);
    }

    ProviderConfigurationResult result(ProviderConfiguration request) {
        Optional<CredentialRef> credential =
                switch (request.credentialChange()) {
                    case REPLACE -> Optional.of(new CredentialRef("provider", "fixture-secret"));
                    case CLEAR -> Optional.empty();
                    case KEEP ->
                        providers.stream()
                                .filter(value -> value.id().equals(request.providerId()))
                                .findFirst()
                                .flatMap(value -> value.spec().credential());
                };
        ProviderEndpoint endpoint = new ProviderEndpoint(
                request.providerId(),
                request.expectedRevision() + 1,
                request.lifecycle(),
                request.connection().toEndpointSpec(request.models(), credential),
                Instant.EPOCH,
                Instant.EPOCH);
        return new ProviderConfigurationResult(
                endpoint,
                credential.map(reference -> new CredentialMetadata(
                        reference, Math.max(1, request.credentialExpectedRevision() + 1), Instant.EPOCH)));
    }

    @Override
    public CompletionStage<Optional<ProviderConfigurationResult>> providerConfigurationResult(
            PreparedProviderConfiguration prepared) {
        queried.add(prepared);
        return receiptResponses.isEmpty()
                ? CompletableFuture.completedFuture(Optional.ofNullable(committed))
                : receiptResponses.remove();
    }

    @Override
    public CompletionStage<Optional<CredentialMetadata>> credential(CredentialRef reference) {
        if (committed != null
                && committed
                        .credential()
                        .map(value -> value.reference().equals(reference))
                        .orElse(false)) {
            return CompletableFuture.completedFuture(committed.credential());
        }
        return super.credential(reference);
    }

    @Override
    public CompletionStage<Void> useModel(
            Optional<WorkspaceId> workspace, Optional<ThreadId> thread, ProviderRef model) {
        uses++;
        return CompletableFuture.completedFuture(null);
    }
}
