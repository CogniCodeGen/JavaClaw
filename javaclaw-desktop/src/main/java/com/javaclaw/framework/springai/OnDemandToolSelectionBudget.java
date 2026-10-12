package com.javaclaw.framework.springai;

import com.javaclaw.framework.core.OnDemandContextPolicy;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static com.javaclaw.framework.springai.OnDemandContextSession.pause;

/** 修复与可选桌面提示的工具选择预算，不扩大候选或 schema 上限。 */
final class OnDemandToolSelectionBudget {
    private final ToolCatalogSession catalog;
    private final OnDemandContextPolicy policy;

    OnDemandToolSelectionBudget(ToolCatalogSession catalog, OnDemandContextPolicy policy) {
        this.catalog = catalog;
        this.policy = policy;
    }

    List<String> retainPreviouslyOfferedTools(Set<String> required,
            List<String> previous, List<String> activated, Set<String> authorized,
            int providerToolLimit, String prerequisite) {
        List<String> selected = new ArrayList<>(required);
        Set<String> exposed = new LinkedHashSet<>(activated);
        exposed.addAll(selected);
        // 先为已证明的下一步保留工具槽位与 schema，避免可选保留挤掉修复必需工具。
        boolean reservePrerequisite = prerequisite != null && authorized.contains(prerequisite);
        if (reservePrerequisite) exposed.add(prerequisite);
        for (String name : previous) {
            // framework_tool_catalog 是发现模式，不是业务候选；历史名称不能赋予当前授权。
            if (!authorized.contains(name) || exposed.contains(name)
                    || exposed.size() >= providerToolLimit
                    || selected.size() >= policy.candidates()) continue;
            List<String> proposed = new ArrayList<>(selected);
            proposed.add(name);
            List<String> checked = new ArrayList<>(proposed);
            if (reservePrerequisite && !checked.contains(prerequisite)) checked.add(prerequisite);
            if (checked.size() > policy.candidates()) continue;
            try {
                catalog.projectPlanned(checked, policy.selectedTools(),
                        ToolCatalogSession.CatalogMode.NONE);
            } catch (IllegalStateException invalid) {
                if (invalid instanceof ToolSchemaBudgetExceededException) {
                    continue;
                }
                throw pause("invalid or over-budget repaired tool selection: "
                        + invalid.getMessage(), invalid);
            }
            selected = proposed;
            exposed.add(name);
        }
        return selected;
    }

    /** 可选能力提示与显式选择共用工具数量和 schema 预算。 */
    List<String> optionalDesktopClickTools(List<String> planned,
            List<String> activated, int providerLimit) {
        List<String> withClick = new ArrayList<>(planned);
        withClick.add("desktop_session_click");
        List<List<String>> options = new ArrayList<>();
        options.add(withClick);
        if (!activated.contains("desktop_session_observe")
                && planned.contains("desktop_session_observe")) {
            options.add(List.of("desktop_session_click"));
        }
        for (List<String> option : options) {
            Set<String> exposed = new LinkedHashSet<>(activated);
            exposed.addAll(option);
            if (exposed.size() > providerLimit || option.size() > policy.candidates()) continue;
            try {
                catalog.projectPlanned(option, policy.selectedTools(), ToolCatalogSession.CatalogMode.NONE);
                return option;
            } catch (IllegalStateException invalid) {
                if (!(invalid instanceof ToolSchemaBudgetExceededException)) {
                    throw pause("invalid planned desktop capability: " + invalid.getMessage(), invalid);
                }
            }
        }
        return planned;
    }

}
