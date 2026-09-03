package com.javaclaw.server.persistence;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;

import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.CredentialRef;
import com.javaclaw.api.ProviderAuthentication;
import com.javaclaw.api.ProviderCapabilities;
import com.javaclaw.api.ProviderEndpoint;
import com.javaclaw.api.ProviderModelPurpose;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.api.ProviderVerificationResult;
import com.javaclaw.api.ProviderVerificationState;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.ProviderVerificationRpcContracts;
import com.javaclaw.runtime.ModelGateway;
import com.javaclaw.server.model.EmbeddingAdapterFactory;

/**
 * 显式计费 Provider round-trip 的幂等应用服务。
 *
 * <p>事务不变量：模型调用前先提交 RUNNING 意图；模型调用后才提交脱敏终态与全局幂等回执。若进程在两者之间退出，启动恢复会固定为 UNKNOWN_OUTCOME，任何重放都不会再次调用模型。
 */
public final class ProviderVerificationService implements AutoCloseable {
    private final H2Transactions transactions;
    private final ProviderVerificationRepository repository = new ProviderVerificationRepository();
    private final IdempotencyRepository idempotency = new IdempotencyRepository();
    private final ProviderService providers;
    private final CredentialAvailabilityPort credentials;
    private final ProviderVerificationHarness chatHarness;
    private final ProviderEmbeddingVerificationHarness embeddingHarness;
    private final CanonicalJson json;
    private final Clock clock;
    private final ConcurrentHashMap<String, ActiveCall> active = new ConcurrentHashMap<>();

    /**
     * 创建服务并把上次进程遗留的 RUNNING 意图恢复为 UNKNOWN_OUTCOME。
     *
     * @param database data-v5 数据库
     * @param providers Provider 权威版本服务
     * @param credentials Secret 实时可用性边界
     * @param models 生产环境中的 ProviderModelRegistry
     * @param embeddingAdapters 精确版本的临时 Embedding Adapter 构造边界
     * @param json 规范 JSON codec
     * @param clock 平台时钟
     */
    public ProviderVerificationService(
            H2Database database,
            ProviderService providers,
            CredentialAvailabilityPort credentials,
            ModelGateway models,
            EmbeddingAdapterFactory embeddingAdapters,
            CanonicalJson json,
            Clock clock) {
        transactions = new H2Transactions(Objects.requireNonNull(database, "database"));
        this.providers = Objects.requireNonNull(providers, "providers");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.json = Objects.requireNonNull(json, "json");
        this.clock = Objects.requireNonNull(clock, "clock");
        chatHarness = new ProviderVerificationHarness(Objects.requireNonNull(models, "models"), clock);
        embeddingHarness = new ProviderEmbeddingVerificationHarness(
                Objects.requireNonNull(embeddingAdapters, "embeddingAdapters"), clock);
        recoverInterrupted();
    }

    ProviderVerificationService(
            H2Database database,
            ProviderService providers,
            CredentialAvailabilityPort credentials,
            ProviderVerificationHarness chatHarness,
            ProviderEmbeddingVerificationHarness embeddingHarness,
            CanonicalJson json,
            Clock clock) {
        transactions = new H2Transactions(Objects.requireNonNull(database, "database"));
        this.providers = Objects.requireNonNull(providers, "providers");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.chatHarness = Objects.requireNonNull(chatHarness, "chatHarness");
        this.embeddingHarness = Objects.requireNonNull(embeddingHarness, "embeddingHarness");
        this.json = Objects.requireNonNull(json, "json");
        this.clock = Objects.requireNonNull(clock, "clock");
        recoverInterrupted();
    }

    /**
     * 验证精确 Provider 模型；同一幂等身份最多发起一次外部模型调用。
     *
     * @param identity 方法、幂等键、expected revision 与请求摘要
     * @param provider 精确 Provider 与模型版本
     * @param purpose 本次验证的精确模型用途
     * @param cancellation 协作式取消；取消结果同样会持久化，禁止自动重试
     * @return 不含 Prompt 或响应正文的脱敏终态
     */
    public ProviderVerificationResult verify(
            CommandIdentity identity,
            ProviderRef provider,
            ProviderModelPurpose purpose,
            CancellationToken cancellation) {
        CommandIdentity checkedIdentity = requireIdentity(identity, provider);
        ProviderRef checkedProvider = Objects.requireNonNull(provider, "provider");
        ProviderModelPurpose checkedPurpose = Objects.requireNonNull(purpose, "purpose");
        CancellationToken checkedCancellation = Objects.requireNonNull(cancellation, "cancellation");
        ActiveCall candidate = new ActiveCall(checkedIdentity.requestDigest());
        ActiveCall existing = active.putIfAbsent(checkedIdentity.idempotencyKey(), candidate);
        if (existing != null) {
            existing.requireSameRequest(checkedIdentity.requestDigest());
            return await(existing.result());
        }
        try {
            ProviderVerificationResult result =
                    verifyOwner(checkedIdentity, checkedProvider, checkedPurpose, checkedCancellation);
            candidate.result().complete(result);
            return result;
        } catch (RuntimeException failure) {
            candidate.result().completeExceptionally(failure);
            throw failure;
        } finally {
            active.remove(checkedIdentity.idempotencyKey(), candidate);
        }
    }

    /** 取消当前服务拥有的验证线程；已提交终态和 H2 意图不受影响。 */
    @Override
    public void close() {
        chatHarness.close();
        embeddingHarness.close();
    }

    private ProviderVerificationResult verifyOwner(
            CommandIdentity identity,
            ProviderRef provider,
            ProviderModelPurpose purpose,
            CancellationToken cancellation) {
        Optional<ProviderVerificationResult> replay = recover(identity);
        if (replay.isPresent()) {
            return replay.orElseThrow();
        }
        Start start = providers.coordinateSerialCommand(identity, () -> begin(identity, provider, purpose));
        if (start.replay().isPresent()) {
            return start.replay().orElseThrow();
        }
        ProviderVerificationResult result = execute(start.endpoint(), provider, purpose, cancellation);
        try {
            return finish(identity, result);
        } catch (RuntimeException persistenceFailure) {
            return unknown(start.endpoint(), provider, purpose, clock.instant());
        }
    }

    private ProviderVerificationResult execute(
            ProviderEndpoint endpoint,
            ProviderRef provider,
            ProviderModelPurpose purpose,
            CancellationToken cancellation) {
        return switch (purpose) {
            case CHAT -> chatHarness.execute(endpoint, provider, cancellation);
            case EMBEDDING -> embeddingHarness.execute(endpoint, provider, cancellation);
        };
    }

    private Start begin(CommandIdentity identity, ProviderRef provider, ProviderModelPurpose purpose) {
        Optional<ProviderVerificationResult> replay = recover(identity);
        if (replay.isPresent()) {
            return new Start(null, replay);
        }
        ProviderEndpoint endpoint = requireEligible(provider, purpose);
        requireCredentialAvailable(endpoint);
        Optional<ProviderVerificationResult> transactionalReplay = execute(connection -> {
            Optional<IdempotencyRepository.StoredCommand> stored =
                    idempotency.find(connection, identity.idempotencyKey());
            if (stored.isPresent()) {
                return Optional.of(recover(identity, stored.orElseThrow()));
            }
            Optional<ProviderVerificationRepository.StoredVerification> intent =
                    repository.find(connection, identity.idempotencyKey(), true);
            if (intent.isPresent()) {
                return Optional.of(recover(identity, intent.orElseThrow()));
            }
            repository.insertRunning(connection, identity, provider, purpose, clock.instant());
            return Optional.<ProviderVerificationResult>empty();
        });
        return transactionalReplay
                .map(result -> new Start(null, Optional.of(result)))
                .orElseGet(() -> new Start(endpoint, Optional.empty()));
    }

    private ProviderVerificationResult finish(CommandIdentity identity, ProviderVerificationResult result) {
        return execute(connection -> {
            Optional<IdempotencyRepository.StoredCommand> replay =
                    idempotency.find(connection, identity.idempotencyKey());
            if (replay.isPresent()) {
                return recover(identity, replay.orElseThrow());
            }
            ProviderVerificationRepository.StoredVerification stored = repository
                    .find(connection, identity.idempotencyKey(), true)
                    .orElseThrow(() -> new PersistenceException("Provider 验证意图不存在"));
            requireStoredIdentity(identity, stored);
            repository.complete(
                    connection,
                    identity.idempotencyKey(),
                    result.state().name(),
                    json.encode(result),
                    result.completedAt());
            idempotency.insert(connection, identity, json.encode(result), result.completedAt());
            return result;
        });
    }

    private Optional<ProviderVerificationResult> recover(CommandIdentity identity) {
        return execute(connection -> {
            Optional<IdempotencyRepository.StoredCommand> result =
                    idempotency.find(connection, identity.idempotencyKey());
            if (result.isPresent()) {
                return Optional.of(recover(identity, result.orElseThrow()));
            }
            return repository
                    .find(connection, identity.idempotencyKey(), false)
                    .map(stored -> recover(identity, stored));
        });
    }

    private ProviderVerificationResult recover(
            CommandIdentity identity, ProviderVerificationRepository.StoredVerification stored) {
        requireStoredIdentity(identity, stored);
        return stored.result()
                .map(payload -> json.decode(payload, ProviderVerificationResult.class))
                .orElseGet(() -> unknownForStored(stored));
    }

    private ProviderVerificationResult recover(CommandIdentity identity, IdempotencyRepository.StoredCommand stored) {
        if (!stored.method().equals(identity.method())
                || !stored.requestDigest().equals(identity.requestDigest())) {
            throw PersistenceException.idempotencyConflict("幂等键已被不同命令使用");
        }
        return json.decode(stored.response(), ProviderVerificationResult.class);
    }

    private ProviderEndpoint requireEligible(ProviderRef provider, ProviderModelPurpose purpose) {
        return providers.requireAvailable(provider, purpose);
    }

    private void requireCredentialAvailable(ProviderEndpoint endpoint) {
        if (endpoint.spec().authentication() == ProviderAuthentication.NONE) {
            return;
        }
        CredentialRef credential = endpoint.spec()
                .credential()
                .orElseThrow(() -> PersistenceException.invalidRequest("Provider 尚未绑定 CredentialRef"));
        if (!credentials.available(credential)) {
            throw PersistenceException.invalidRequest("Provider CredentialRef 当前不可用");
        }
    }

    private void recoverInterrupted() {
        execute(connection -> {
            for (ProviderVerificationRepository.StoredVerification stored : repository.listRunning(connection)) {
                ProviderEndpoint endpoint = providers.require(
                        stored.provider().endpointId(), stored.provider().endpointRevision());
                ProviderVerificationResult unknown =
                        unknown(endpoint, stored.provider(), stored.purpose(), clock.instant());
                CommandIdentity identity = new CommandIdentity(
                        stored.method(),
                        stored.idempotencyKey(),
                        stored.provider().endpointRevision(),
                        stored.requestDigest());
                repository.complete(
                        connection,
                        stored.idempotencyKey(),
                        unknown.state().name(),
                        json.encode(unknown),
                        unknown.completedAt());
                idempotency.insert(connection, identity, json.encode(unknown), unknown.completedAt());
            }
            return null;
        });
    }

    private ProviderVerificationResult unknownForStored(ProviderVerificationRepository.StoredVerification stored) {
        ProviderEndpoint endpoint = providers.require(
                stored.provider().endpointId(), stored.provider().endpointRevision());
        return unknown(endpoint, stored.provider(), stored.purpose(), stored.updatedAt());
    }

    private ProviderVerificationResult unknown(
            ProviderEndpoint endpoint, ProviderRef provider, ProviderModelPurpose purpose, Instant completedAt) {
        ProviderCapabilities capabilities =
                ProviderService.capabilities(endpoint.spec().adapter(), java.util.Set.of(purpose));
        return new ProviderVerificationResult(
                provider,
                purpose,
                ProviderVerificationState.UNKNOWN_OUTCOME,
                0,
                Optional.empty(),
                capabilities,
                Optional.of("UNKNOWN_OUTCOME"),
                completedAt);
    }

    private static CommandIdentity requireIdentity(CommandIdentity identity, ProviderRef provider) {
        CommandIdentity checked = Objects.requireNonNull(identity, "identity");
        ProviderRef reference = Objects.requireNonNull(provider, "provider");
        if (!ProviderVerificationRpcContracts.METHOD.equals(checked.method())) {
            throw PersistenceException.invalidRequest("Provider 验证方法身份错误");
        }
        if (checked.expectedRevision() != reference.endpointRevision()) {
            throw PersistenceException.revisionConflict("Provider expected revision 不匹配");
        }
        return checked;
    }

    private static void requireStoredIdentity(
            CommandIdentity identity, ProviderVerificationRepository.StoredVerification stored) {
        if (!stored.method().equals(identity.method())
                || !stored.requestDigest().equals(identity.requestDigest())) {
            throw PersistenceException.idempotencyConflict("幂等键已被不同 Provider 验证使用");
        }
    }

    private static ProviderVerificationResult await(CompletableFuture<ProviderVerificationResult> future) {
        try {
            return future.join();
        } catch (CompletionException failure) {
            if (failure.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw failure;
        }
    }

    private <T> T execute(H2Transactions.SqlWork<T> work) {
        try {
            return transactions.execute(work);
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new PersistenceException("Provider 验证事务失败", failure);
        }
    }

    private record Start(ProviderEndpoint endpoint, Optional<ProviderVerificationResult> replay) {
        private Start {
            replay = Objects.requireNonNull(replay, "replay");
            if ((endpoint == null) == replay.isEmpty()) {
                throw new IllegalArgumentException("start must contain endpoint or replay");
            }
        }
    }

    private record ActiveCall(String requestDigest, CompletableFuture<ProviderVerificationResult> result) {
        private ActiveCall(String requestDigest) {
            this(requestDigest, new CompletableFuture<>());
        }

        private ActiveCall {
            Objects.requireNonNull(requestDigest, "requestDigest");
            Objects.requireNonNull(result, "result");
        }

        private void requireSameRequest(String digest) {
            if (!requestDigest.equals(digest)) {
                throw PersistenceException.idempotencyConflict("幂等键已被不同 Provider 验证使用");
            }
        }
    }
}
