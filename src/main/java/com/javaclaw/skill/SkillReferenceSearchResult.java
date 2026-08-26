package com.javaclaw.skill;

/** Bounded keyword-search output over one Run-stable skill reference manifest. */
public record SkillReferenceSearchResult(String content, boolean truncated, String error) {
    public SkillReferenceSearchResult {
        content = content == null ? "" : content;
        error = error == null ? "" : error;
    }
}
