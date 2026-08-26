package com.javaclaw.skill;

/** One token-bounded page from the L0 skill and bundle index. */
public record SkillCatalogPage(String content, int nextCursor, boolean hasMore) {
    public SkillCatalogPage {
        content = content == null ? "" : content;
        nextCursor = Math.max(0, nextCursor);
    }
}
