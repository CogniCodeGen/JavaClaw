package com.javaclaw.skill;

import com.javaclaw.framework.api.PermissionSet;
import com.javaclaw.framework.api.RunRequest;
import com.javaclaw.framework.spi.DeferredContextCandidate;
import com.javaclaw.framework.spi.DeferredContextDigest;
import com.javaclaw.framework.spi.DeferredContextSource;
import com.javaclaw.framework.spi.DeferredContextUse;
import com.javaclaw.util.SensitiveDataRedactor;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Skill metadata is searchable; a selected instruction body is read by ID and checked for drift. */
public final class SkillDeferredContextSource implements DeferredContextSource {
    private static final String QUALIFIED_PREFIX = "@skill/v2/";
    private static final String BUNDLE_PREFIX = QUALIFIED_PREFIX + "b/";
    private final SkillManager skills;

    public SkillDeferredContextSource(SkillManager skills) {
        this.skills = Objects.requireNonNull(skills, "skills");
    }

    @Override public String id() { return "skills"; }
    @Override public String description() { return "Enabled skill instructions and workflows"; }
    @Override public String group() { return "skill"; }
    @Override public PermissionSet requiredPermissions() { return PermissionSet.of("tool.read"); }

    @Override
    public List<DeferredContextCandidate> search(RunRequest request, String query, int limit) {
        if (limit < 1) return List.of();
        List<Ranked> ranked = new java.util.ArrayList<>();
        for (SkillManager.DeferredSkill entry : skills.activeDeferredSkills()) {
            if (visible(entry)) {
                double score = relevance(query, entry.skill().getName(),
                        entry.skill().getDescription(), entry.skill().getTags());
                if (score > 0) ranked.add(new Ranked(entry, null, null, score));
            }
        }
        for (SkillBundle bundle : skills.activeDeferredBundles()) {
            String version = bundleVersion(bundle);
            if (version == null) continue;
            double score = relevance(query, bundle.name, bundle.description, bundle.skills);
            if (score > 0) ranked.add(new Ranked(null, bundle, version, score));
        }
        return ranked.stream()
                .sorted(Comparator.comparingDouble(Ranked::score).reversed()
                        .thenComparing(hit -> hit.bundle() == null
                                ? qualifiedId(hit.entry()) : bundleId(hit.bundle())))
                .limit(Math.min(limit, 256))
                .map(hit -> hit.bundle() == null
                        ? candidate(hit.entry()) : bundleCandidate(hit.bundle(), hit.version()))
                .toList();
    }

    @Override
    public String fetch(RunRequest request, String candidateId, String version) {
        Objects.requireNonNull(candidateId, "candidateId");
        Objects.requireNonNull(version, "version");
        if (candidateId.startsWith(BUNDLE_PREFIX)) {
            return fetchBundle(candidateId, version);
        }
        List<SkillManager.DeferredSkill> matches = skills.activeDeferredSkills().stream()
                .filter(this::visible)
                .filter(entry -> candidateId.equals(qualifiedId(entry)))
                .toList();
        if (matches.size() != 1) {
            throw new IllegalStateException("skill context no longer exists: " + candidateId);
        }
        SkillManager.DeferredSkill entry = matches.getFirst();
        String body = skills.renderDeferredSkill(entry.skill());
        if (body == null || !version(entry, body).equals(version)) {
            throw new IllegalStateException("skill context version changed: " + candidateId);
        }
        return body;
    }

    private String fetchBundle(String candidateId, String version) {
        List<SkillBundle> matches = skills.activeDeferredBundles().stream()
                .filter(bundle -> candidateId.equals(bundleId(bundle)))
                .toList();
        if (matches.size() != 1) {
            throw new IllegalStateException("skill bundle context no longer exists: " + candidateId);
        }
        SkillBundle bundle = matches.getFirst();
        String current = bundleVersion(bundle);
        if (!version.equals(current)) {
            throw new IllegalStateException("skill bundle context version changed: " + candidateId);
        }
        for (Skill skill : bundleMembers(bundle)) {
            String body = skills.renderDeferredSkill(skill);
            if (body == null || !DeferredContextDigest.sha256(body)
                    .equals(skills.deferredDetailDigest(skill))) {
                throw new IllegalStateException("skill bundle context version changed: " + candidateId);
            }
        }
        String body = skills.buildBundlePrompt(bundle.name);
        if (body.isEmpty()) {
            throw new IllegalStateException("skill bundle context no longer exists: " + candidateId);
        }
        return body;
    }

    private DeferredContextCandidate candidate(SkillManager.DeferredSkill entry) {
        Skill skill = entry.skill();
        String digest = skills.deferredDetailDigest(skill);
        if (digest == null) throw new IllegalStateException("skill context version is unavailable: " + skill.getId());
        String summary = skill.getName() + ": " + Objects.requireNonNullElse(skill.getDescription(), "");
        if (summary.length() > 240) summary = summary.substring(0, 240) + "…";
        DeferredContextUse use = entry.workspace()
                ? DeferredContextUse.USER_WORKFLOW : DeferredContextUse.REFERENCE;
        String origin = use == DeferredContextUse.USER_WORKFLOW ? "workspace:" : "dynamic:";
        return new DeferredContextCandidate(qualifiedId(entry),
                origin + skill.getVersion() + ":" + digest, summary,
                PermissionSet.of("tool.read"), use);
    }

    private DeferredContextCandidate bundleCandidate(SkillBundle bundle, String version) {
        String description = SensitiveDataRedactor.containsLikelyCredential(bundle.description)
                ? "[描述已隐藏]" : Objects.requireNonNullElse(bundle.description, "");
        String members = bundle.skills.stream()
                .map(name -> SensitiveDataRedactor.containsLikelyCredential(name)
                        ? "[已隐藏]" : Objects.requireNonNullElse(name, ""))
                .reduce((left, right) -> left + "、" + right).orElse("");
        String summary = bundle.name + ": " + description + "（含：" + members + "）";
        if (summary.length() > 240) summary = summary.substring(0, 240) + "…";
        return new DeferredContextCandidate(bundleId(bundle), version, summary,
                PermissionSet.of("tool.read"), DeferredContextUse.USER_WORKFLOW);
    }

    private String bundleVersion(SkillBundle bundle) {
        if (bundle == null || bundle.name == null || bundle.name.isBlank()
                || SensitiveDataRedactor.containsLikelyCredential(bundle.name)
                || bundle.skills == null || bundle.skills.isEmpty()) return null;
        StringBuilder state = new StringBuilder();
        appendField(state, bundle.name);
        appendField(state, bundle.description);
        appendField(state, bundle.extraInstructions);
        int available = 0;
        for (String name : bundle.skills) {
            appendField(state, name);
            Skill selected = skills.getEnabledSkills().stream()
                    .filter(skill -> Objects.equals(skill.getName(), name))
                    .findFirst().orElse(null);
            String digest = selected == null ? null : skills.deferredDetailDigest(selected);
            appendField(state, selected == null ? null : selected.getId());
            appendField(state, selected == null ? null : selected.getVersion());
            appendField(state, digest);
            if (digest != null) available++;
        }
        return available == 0 ? null : "bundle:" + DeferredContextDigest.sha256(state.toString());
    }

    private List<Skill> bundleMembers(SkillBundle bundle) {
        return bundle.skills.stream()
                .map(name -> skills.getEnabledSkills().stream()
                        .filter(skill -> Objects.equals(skill.getName(), name))
                        .findFirst().orElse(null))
                .filter(Objects::nonNull)
                .filter(skill -> skills.deferredDetailDigest(skill) != null)
                .toList();
    }

    private static void appendField(StringBuilder state, String value) {
        if (value == null) state.append("-1:");
        else state.append(value.length()).append(':').append(value);
    }

    private static String bundleId(SkillBundle bundle) {
        return BUNDLE_PREFIX + encode(bundle.name);
    }

    private boolean visible(SkillManager.DeferredSkill entry) {
        Skill skill = entry.skill();
        return skill.getId() != null && !skill.getId().isBlank()
                && skill.getName() != null && !skill.getName().isBlank()
                && skills.deferredDetailDigest(skill) != null;
    }

    private static String version(SkillManager.DeferredSkill entry, String body) {
        return (entry.workspace() ? "workspace:" : "dynamic:")
                + entry.skill().getVersion() + ":" + DeferredContextDigest.sha256(body);
    }

    private static String qualifiedId(SkillManager.DeferredSkill entry) {
        if (entry.workspace()) {
            return QUALIFIED_PREFIX + "w/" + encode(entry.skill().getId());
        }
        return QUALIFIED_PREFIX + "d/" + encode(entry.ownerId()) + "/"
                + encode(entry.skill().getId()) + "/" + entry.ordinal();
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static double relevance(String query, String name, String description, List<String> tags) {
        if (query == null || query.isBlank()) return .01;
        String target = query.strip().toLowerCase(Locale.ROOT);
        String haystack = (Objects.requireNonNullElse(name, "") + " "
                + Objects.requireNonNullElse(description, "") + " "
                + String.join(" ", tags)).toLowerCase(Locale.ROOT);
        if (haystack.contains(target)) return 1;
        int hits = 0;
        String[] terms = target.split("\\s+");
        for (String term : terms) if (!term.isBlank() && haystack.contains(term)) hits++;
        return hits == 0 ? 0 : hits / (double) terms.length;
    }

    private record Ranked(SkillManager.DeferredSkill entry, SkillBundle bundle,
                          String version, double score) {}
}
