package com.javaclaw.builtin.extensions;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.javaclaw.builtin.contracts.MemoryContracts;
import com.javaclaw.extension.spi.ExtensionExecutionContext;

/** Memory 自动学习的保守、可审计规则；任一疑点都会降级为人工 Proposal。 */
final class MemoryLearningRules {
    private static final List<String> SENSITIVE =
            List.of("password", "api key", "secret", "token", "密码", "密钥", "身份证", "银行卡");
    private static final List<String> SPECULATIVE =
            List.of("maybe", "probably", "possibly", "可能", "也许", "大概", "推测", "猜测");

    private MemoryLearningRules() {}

    static Set<MemoryContracts.ProposalConcern> classify(
            MemoryContracts.LearningProposalRequest request,
            List<MemoryContracts.Memory> current,
            ExtensionExecutionContext context) {
        EnumSet<MemoryContracts.ProposalConcern> concerns = EnumSet.noneOf(MemoryContracts.ProposalConcern.class);
        if (request.kind() == MemoryContracts.MemoryKind.PERSONA) {
            concerns.add(MemoryContracts.ProposalConcern.PERSONA);
        }
        if (context.evidence()
                .isUncertainOutcome(
                        context.workspaceId(),
                        request.source().threadId(),
                        request.source().itemId())) {
            concerns.add(MemoryContracts.ProposalConcern.UNKNOWN_OUTCOME);
        }
        String normalized = request.content().toLowerCase(Locale.ROOT);
        if (SENSITIVE.stream().anyMatch(normalized::contains)) {
            concerns.add(MemoryContracts.ProposalConcern.SENSITIVE);
        }
        if (SPECULATIVE.stream().anyMatch(normalized::contains)) {
            concerns.add(MemoryContracts.ProposalConcern.SPECULATIVE);
        }
        if (!verifiable(request, context)) {
            concerns.add(MemoryContracts.ProposalConcern.UNCERTAIN_EVIDENCE);
        }
        if (conflicts(request, current)) {
            concerns.add(MemoryContracts.ProposalConcern.CONFLICT);
        } else if (current.stream()
                .anyMatch(memory -> memory.scope().equals(request.scope())
                        && !memory.content().equals(request.content()))) {
            concerns.add(MemoryContracts.ProposalConcern.UNCERTAIN_EVIDENCE);
        }
        return Set.copyOf(concerns);
    }

    private static boolean verifiable(
            MemoryContracts.LearningProposalRequest request, ExtensionExecutionContext context) {
        MemoryContracts.Source source = request.source();
        return source.workspaceId().equals(context.workspaceId())
                && request.content().equals(source.verbatim())
                && context.evidence().isUserText(context.workspaceId(), source.threadId(), source.itemId())
                && context.evidence()
                        .containsVerbatim(context.workspaceId(), source.threadId(), source.itemId(), source.verbatim());
    }

    private static boolean conflicts(
            MemoryContracts.LearningProposalRequest request, List<MemoryContracts.Memory> current) {
        return current.stream()
                .filter(memory -> memory.scope().equals(request.scope()))
                .filter(memory -> !java.util.Collections.disjoint(memory.tags(), request.tags()))
                .findAny()
                .isPresent();
    }
}
