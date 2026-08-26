package com.javaclaw.skill;

/** One bounded page from a skill body or a single reference document. */
public record SkillContentPage(
        String content, int nextCursor, boolean hasMore,
        boolean searchRequired, String error) {
    public SkillContentPage {
        content = content == null ? "" : content;
        nextCursor = Math.max(0, nextCursor);
        error = error == null ? "" : error;
    }

    public SkillContentPage(String content, int nextCursor, boolean hasMore) {
        this(content, nextCursor, hasMore, false, "");
    }

    static SkillContentPage requiresSearchPage() {
        return new SkillContentPage("", 0, false, true, "");
    }

    static SkillContentPage error(String message) {
        return new SkillContentPage("", 0, false, false, message);
    }
}
