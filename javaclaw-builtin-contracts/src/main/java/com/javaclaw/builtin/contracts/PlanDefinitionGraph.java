package com.javaclaw.builtin.contracts;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Plan Definition 的唯一性、引用与无环校验。 */
final class PlanDefinitionGraph {
    private PlanDefinitionGraph() {}

    static void validate(List<PlanContracts.OpenQuestion> questions, List<PlanContracts.Step> steps) {
        if (steps.isEmpty()) {
            throw new IllegalArgumentException("steps must not be empty");
        }
        requireUnique(questions.stream().map(PlanContracts.OpenQuestion::id).toList(), "question");
        Map<String, PlanContracts.Step> byId = index(steps);
        for (PlanContracts.Step step : steps) {
            requireKnownDependencies(step, byId.keySet());
        }
        requireAcyclic(byId);
    }

    private static Map<String, PlanContracts.Step> index(List<PlanContracts.Step> steps) {
        Map<String, PlanContracts.Step> byId = new HashMap<>();
        for (PlanContracts.Step step : steps) {
            if (byId.putIfAbsent(step.id(), step) != null) {
                throw new IllegalArgumentException("duplicate step id: " + step.id());
            }
        }
        return byId;
    }

    private static void requireUnique(List<String> ids, String kind) {
        if (new HashSet<>(ids).size() != ids.size()) {
            throw new IllegalArgumentException("duplicate " + kind + " id");
        }
    }

    private static void requireKnownDependencies(PlanContracts.Step step, Set<String> ids) {
        if (new HashSet<>(step.dependencies()).size() != step.dependencies().size()) {
            throw new IllegalArgumentException("duplicate dependency in step: " + step.id());
        }
        for (String dependency : step.dependencies()) {
            if (dependency.equals(step.id()) || !ids.contains(dependency)) {
                throw new IllegalArgumentException("unknown or self dependency: " + dependency);
            }
        }
    }

    private static void requireAcyclic(Map<String, PlanContracts.Step> steps) {
        Set<String> complete = new HashSet<>();
        for (String id : steps.keySet()) {
            visit(id, steps, complete, new HashSet<>());
        }
    }

    private static void visit(
            String id, Map<String, PlanContracts.Step> steps, Set<String> complete, Set<String> visiting) {
        if (complete.contains(id)) {
            return;
        }
        if (!visiting.add(id)) {
            throw new IllegalArgumentException("plan step graph contains a cycle");
        }
        for (String dependency : steps.get(id).dependencies()) {
            visit(dependency, steps, complete, visiting);
        }
        visiting.remove(id);
        complete.add(id);
    }
}
