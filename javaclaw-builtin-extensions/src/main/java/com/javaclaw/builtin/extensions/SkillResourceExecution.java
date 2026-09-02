package com.javaclaw.builtin.extensions;

import java.util.List;
import java.util.Objects;

import com.javaclaw.api.TurnCancelledException;
import com.javaclaw.builtin.contracts.BuiltinExtensionIds;
import com.javaclaw.builtin.contracts.SkillContracts;
import com.javaclaw.extension.spi.ExtensionExecutionContext;
import com.javaclaw.extension.spi.ExtensionId;
import com.javaclaw.extension.spi.ExtensionPayloadCodec;
import com.javaclaw.extension.spi.ExtensionRequest;
import com.javaclaw.extension.spi.ExtensionResponse;
import com.javaclaw.extension.spi.IsolatedServiceInvocation;

/** 将 Skill 冻结目录校验与进程外资源执行组合在一个窄边界内。 */
final class SkillResourceExecution {
    private static final ExtensionId SKILL = new ExtensionId(BuiltinExtensionIds.SKILL);

    private final ExtensionPayloadCodec payloads;
    private final SkillCatalogCoordinator catalogs;

    SkillResourceExecution(ExtensionPayloadCodec payloads, SkillCatalogCoordinator catalogs) {
        this.payloads = Objects.requireNonNull(payloads, "payloads");
        this.catalogs = Objects.requireNonNull(catalogs, "catalogs");
    }

    ExtensionResponse availability(ExtensionRequest request, ExtensionExecutionContext context) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(context, "context");
        context.cancellation().throwIfCancelled();
        SkillContracts.ResourceExecutionInvocation invocation = new SkillContracts.ResourceExecutionInvocation(
                SkillContracts.ResourceExecutionOperation.STATUS, java.util.Optional.empty(), List.of());
        try {
            var response = context.services().invoke(serviceInvocation(context, invocation));
            SkillContracts.ResourceExecutionAvailability availability =
                    payloads.decode(response, SkillContracts.ResourceExecutionAvailability.class);
            return new ExtensionResponse(payloads.encode(availability), 0);
        } catch (TurnCancelledException cancelled) {
            throw cancelled;
        } catch (Exception unavailable) {
            var availability =
                    new SkillContracts.ResourceExecutionAvailability(false, "当前发行镜像或 Native Sandbox 不支持 Skill 资源执行");
            return new ExtensionResponse(payloads.encode(availability), 0);
        }
    }

    ExtensionResponse execute(
            ExtensionRequest request,
            ExtensionExecutionContext context,
            String publishedCollection,
            String catalogCollection)
            throws Exception {
        if (request.turnId().isEmpty()) {
            throw new IllegalArgumentException("Skill resource execution requires a Turn");
        }
        SkillContracts.ResourceExecutionRequest input =
                payloads.decode(request.payload(), SkillContracts.ResourceExecutionRequest.class);
        SkillContracts.PublishedSkill published = context.managedStore()
                .inTransaction(
                        SKILL,
                        transaction -> catalogs.requireFrozenPublished(
                                request, transaction, input.skill(), publishedCollection, catalogCollection));
        SkillContracts.Resource resource = published.resources().stream()
                .filter(value -> value.id().equals(input.resourceId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Published Skill resource does not exist"));
        if (!resource.executable()) {
            throw new IllegalArgumentException("Published Skill resource is not executable");
        }
        SkillContracts.ResourceExecutionInvocation invocation = new SkillContracts.ResourceExecutionInvocation(
                SkillContracts.ResourceExecutionOperation.EXECUTE, java.util.Optional.of(resource), input.arguments());
        return new ExtensionResponse(context.services().invoke(serviceInvocation(context, invocation)), 0);
    }

    private IsolatedServiceInvocation serviceInvocation(
            ExtensionExecutionContext context, SkillContracts.ResourceExecutionInvocation request) {
        return new IsolatedServiceInvocation(
                SKILL,
                context.workspaceId(),
                context.effectivePermissions(),
                SkillContracts.RESOURCE_EXECUTION_SERVICE,
                payloads.encode(request),
                context.cancellation());
    }
}
