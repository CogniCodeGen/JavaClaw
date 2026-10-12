package com.javaclaw.framework.core;

import java.util.List;

/** 合同规划的领域说明；宿主结构化校验独立执行，不因提示文字而放宽。 */
final class TaskContractPlanningInstructions {
    private TaskContractPlanningInstructions() { }

    static final String DESKTOP_SUBJECT_INSTRUCTION =
            "desktop.launch and desktop.open prove only launch admission or session establishment; "
                    + "desktop.snapshot proves only this owned frame was captured and saved, never "
                    + "its logical window content. All three capabilities require an empty "
                    + "requiredSubject. Put requested window content "
                    + "in a separate desktop.observe criterion for the same application with a "
                    + "nonblank requiredSubject. When a later operation depends on values that must first "
                    + "be read from a page or application, those values and the resulting number are "
                    + "not known at planning time. Never fill in remembered, inferred, or guessed "
                    + "operands, identifiers, or final results. Preserve the complete requested dependency "
                    + "and operation in the downstream description and logical requiredSubject: the result "
                    + "of the requested operation on the values actually read from the named sources. "
                    + "Do not delete that dependency, omit the calculation, or replace it with a generic "
                    + "subject such as calculation result. A semantic condition states the goal, not proof "
                    + "that the source values or their computed relationship have been verified. Existing "
                    + "host observation rules still apply; if the available evidence cannot establish "
                    + "the full dependency, execution must remain unverified rather than accept a weaker "
                    + "condition. Clarify an actual missing human choice when needed, but do not classify "
                    + "values discoverable by observation as missing human intent. ";
    static final String BROWSER_OUTCOME_INSTRUCTION =
            "For browser tasks, distinguish the requested result from a chosen interaction path. "
                    + "Opening a search site to look up information does not require visiting its "
                    + "homepage and typing into a field as separate acceptance criteria. A direct "
                    + "result-page navigation is an equally valid path unless the human explicitly "
                    + "requires those intermediate steps. Preserve explicitly requested actions and "
                    + "their order; never remove them just because a result page is already visible. "
                    + "For a requested lookup, include browser.observe conditions for the actual "
                    + "requested result, not merely admission of navigation or entry of the search "
                    + "query. If only the website is specified and the eventual result URL is not "
                    + "known, use its exact bare host as the observation target (for example "
                    + "www.example.com), without guessing a result path or query parameters. An "
                    + "explicitly requested full URL remains an exact URL target; preserve its query. "
                    + "For newly model-planned existing-page input actions (click, double_click, fill, select, check, "
                    + "upload, type, press_key, drag, hover and scroll), target is the page URL "
                    + "immediately before the input is dispatched, even when that input navigates. "
                    + "Never use an assumed post-input result URL for that input criterion. Keep "
                    + "the human-requested resulting page or content in a separate browser.observe "
                    + "criterion. browser.navigate instead targets its requested destination URL. "
                    + "Previously frozen contracts and explicit definitions retain their declared "
                    + "target phase; a missing phase keeps the returned-page meaning. "
                    + "Every browser input criterion must declare planning-only intentBasis as "
                    + "REQUESTED_ACTION or IMPLEMENTATION_CHOICE. REQUESTED_ACTION requires humanQuote: "
                    + "the shortest exact human wording that directly requests that action. A general "
                    + "lookup goal or a quote of the entire task cannot ground inferred fill, click "
                    + "or other interaction steps. IMPLEMENTATION_CHOICE requires humanQuote=\"\"; "
                    + "the host omits it before freezing acceptance only when a separate "
                    + "browser.observe criterion carries the requested result. Prefer omitting "
                    + "optional steps altogether. Never mark an explicitly requested action optional. ";
    static final String BROWSER_SUBJECT_INSTRUCTION =
            "Only browser.observe can prove observed page content. All other browser capabilities "
                    + "require an empty requiredSubject; their receipts prove input admission only. "
                    + "A browser.observe requiredSubject is at most 128 characters and must be a "
                    + "short literal text fragment supported by the host observation, not an "
                    + "abstract success claim or a guessed result value. For multiple independently "
                    + "requested text fragments, use requiredTextFragments and requiredSubject=\"\". "
                    + "Do not join separated words into a made-up contiguous phrase. All fragments "
                    + "must appear in the same observed text; at most 8 fragments, 128 characters "
                    + "each, 256 characters total. Copy each fragment exactly from human input; "
                    + "never translate, concatenate or guess future result values or date formatting. "
                    + "A single nonempty requiredSubject must likewise be an exact human text fragment. "
                    + "If the task asks to read a value, identifier, document number, or other content "
                    + "not supplied by the human, do not put its anticipated value into requiredSubject "
                    + "or requiredTextFragments, even when you remember the page. Use only the human's "
                    + "exact visible source anchors, labels, or headings as the literal fragments; "
                    + "keep the requirement to read their actual associated values in the description. "
                    + "Do not invent numeric fragments or a downstream numeric answer before any observation. "
                    + "The fragments prove only literal text, never the complete business meaning. "
                    + "Preserve the complete "
                    + "requested outcome in their descriptions. Search-box values, a search-page "
                    + "title, query text echoed by links, and the URL alone do not establish that "
                    + "the requested answer or result content was read. Never replace the requested "
                    + "result with those weaker conditions merely to obtain a matching receipt. "
                    + "Use the supplied browserObservation date and zone to resolve relative dates "
                    + "without inventing a page's date format or forecast values. ";
    static final String FILE_TARGET_INSTRUCTION =
            "For FILE criteria, filePaths is the trusted host path context. Relative targets "
                    + "resolve against filePaths.workingDirectory, not projectRoot. Prefer the "
                    + "exact requested filename relative to that directory or its correctly "
                    + "resolved absolute path. Copy every directory, date digit and filename "
                    + "without rewriting or guessing. A target outside an explicit workingDirectory "
                    + "must be an exact file path supplied by human input; projectRoot containment "
                    + "alone does not ground it in the requested task. Copy requested content in "
                    + "requiredSubject with its actual characters and line breaks; JSON-escape once, "
                    + "so parsing restores real line breaks rather than literal backslash+n, unless "
                    + "the human explicitly requested those literal characters. ";
    static final String KNOWLEDGE_ANSWER_INSTRUCTION =
            "The acceptance capability catalog lists receipt-verifiable outcomes, not every executable tool. "
                    + "The exact host knowledge_search and knowledge_list identities declare idempotent "
                    + "tool.read contracts for answer material. If the goal is only to read existing knowledge "
                    + "and return an answer, including explicitly choosing either tool, return applicable=false, "
                    + "intentStatus=RESOLVED, criteria=[], reasonCodes=[], and unresolvedInputs=[]. Their names "
                    + "are not capability IDs. Do not invent a knowledge receipt criterion or a missing-capability "
                    + "diagnostic merely because those names are absent from this acceptance catalog. Current "
                    + "Run authorization, tool availability, document scope and retrieved facts remain unobserved; "
                    + "runtime tool discovery and execution must check them and report actual failures. This "
                    + "rule does not cover knowledge import, modification, deletion or clearing, real file "
                    + "operations, external actions or live application observations. Those requested outcomes "
                    + "still require applicable=true and the supplied supported acceptance criteria, even when "
                    + "combined with a knowledge question. Answer prose cannot prove those outcomes. ";

    /** 仅说明宿主登记身份，不代表当前 Run 已授权或工具可用。 */
    static String knowledgeAnswerInstruction() {
        boolean declaredReads = List.of("knowledge_search", "knowledge_list").stream()
                .allMatch(name -> com.javaclaw.agent.ToolRiskRegistry.matchesHostImplementation(
                        name, com.javaclaw.agent.expert.KnowledgeExpert.class)
                        && com.javaclaw.agent.ToolRiskRegistry.isKnownHostReadOnly(name));
        return declaredReads ? KNOWLEDGE_ANSWER_INSTRUCTION : "";
    }

}
