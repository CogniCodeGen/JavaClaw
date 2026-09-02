package com.javaclaw.server;

import java.net.URI;
import java.util.Comparator;
import java.util.List;
import java.util.stream.IntStream;

import com.javaclaw.builtin.contracts.PlanContracts;
import com.javaclaw.builtin.contracts.SiteContracts;
import com.javaclaw.builtin.contracts.SiteManagementContracts;

/** 把服务端权威 Definition 转为管理中心允许提交的强类型测试输入。 */
public final class BuiltinManagementFixtures {
    private BuiltinManagementFixtures() {}

    /**
     * 去除服务端生成的 revision、摘要与时间，构造 Plan 管理输入。
     *
     * @param definition 权威 Plan Definition
     * @return 不含服务端字段的保存请求
     */
    public static PlanContracts.ManagementSaveRequest plan(PlanContracts.Definition definition) {
        List<PlanContracts.ManagementRisk> risks = IntStream.range(
                        0, definition.risks().size())
                .mapToObj(index -> new PlanContracts.ManagementRisk(
                        "risk-" + index, definition.risks().get(index)))
                .toList();
        List<PlanContracts.ManagementOpenQuestion> questions = definition.openQuestions().stream()
                .map(value -> new PlanContracts.ManagementOpenQuestion(
                        "question-" + value.id(),
                        value.id(),
                        value.prompt(),
                        value.decision().map(PlanContracts.Decision::answer)))
                .toList();
        List<PlanContracts.ManagementStep> steps = definition.steps().stream()
                .map(value -> new PlanContracts.ManagementStep(
                        "step-" + value.id(),
                        value.id(),
                        value.title(),
                        value.instruction(),
                        value.acceptanceCriteria(),
                        value.dependencies()))
                .toList();
        return new PlanContracts.ManagementSaveRequest(
                definition.id(),
                definition.title(),
                definition.objective(),
                definition.scope(),
                risks,
                questions,
                steps);
    }

    /**
     * 去除 Site 的 Secret、授权、revision 与时间，构造权限表面管理输入。
     *
     * @param site 权威 Site
     * @return 不含服务端字段的保存请求
     */
    public static SiteManagementContracts.SaveRequest site(SiteContracts.Site site) {
        List<URI> sortedOrigins = site.allowedOrigins().stream()
                .sorted(Comparator.comparing(URI::toASCIIString))
                .toList();
        List<SiteManagementContracts.AllowedOrigin> origins = IntStream.range(0, sortedOrigins.size())
                .mapToObj(
                        index -> new SiteManagementContracts.AllowedOrigin("origin-" + index, sortedOrigins.get(index)))
                .toList();
        return new SiteManagementContracts.SaveRequest(site.id(), site.name(), site.origin(), origins, site.enabled());
    }
}
