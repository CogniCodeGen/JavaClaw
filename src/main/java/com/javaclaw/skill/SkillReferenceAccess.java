package com.javaclaw.skill;

import com.javaclaw.util.SensitiveDataRedactor;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.nio.channels.Channels;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Bounded live access to reference files listed in an immutable Run skill manifest. */
final class SkillReferenceAccess {
    static final long DIRECT_READ_LIMIT_BYTES = 256L * 1024;
    static final long SEARCH_SCAN_LIMIT_BYTES = 64L * 1024 * 1024;
    static final int SEARCH_MATCH_LIMIT = 20;
    private static final int SNIPPET_CODE_POINTS = 320;

    private SkillReferenceAccess() { }

    static SkillContentPage readPage(
            SkillPromptRenderer.CatalogSkill skill,
            String relativePath,
            int cursor,
            Integer line,
            int maxCharacters) {
        return readPage(skill, relativePath, cursor, line, maxCharacters, null);
    }

    static SkillContentPage readPage(
            SkillPromptRenderer.CatalogSkill skill,
            String relativePath,
            int cursor,
            Integer line,
            int maxCharacters,
            SkillReferenceReadSession session) {
        if (cursor < 0 || maxCharacters <= 0 || (line != null && line < 1)) return null;
        if (line != null && cursor != 0) {
            return SkillContentPage.error("line 与 cursor 不能同时使用。首次定位使用 line，后续分页使用 next_cursor。");
        }
        ResolvedReference resolved = resolve(skill, relativePath);
        if (resolved == null) return null;
        try {
            BasicFileAttributes before = attributes(resolved.path());
            if (before.size() > SEARCH_SCAN_LIMIT_BYTES) {
                return SkillContentPage.error("参考文件超过 64 MiB 扫描上限，已拒绝读取。");
            }
            SkillReferenceReadSession.FileVersion version = version(before);
            if (before.size() > DIRECT_READ_LIMIT_BYTES) {
                boolean authorized = line != null
                        ? session != null && session.allowsLine(
                                skill, resolved.name(), line, version)
                        : cursor != 0 && session != null && session.allowsCursor(
                                skill, resolved.name(), cursor, version);
                if (!authorized) return SkillContentPage.requiresSearchPage();
            }
            int start = line == null ? cursor : lineOffset(resolved.path(), line);
            if (start < 0) return null;
            String header = "--- " + resolved.name() + " ---\n";
            int contentLimit = Math.max(1, maxCharacters - header.length());
            Page page = readCharacters(resolved.path(), start, contentLimit);
            BasicFileAttributes after = attributes(resolved.path());
            if (!sameVersion(before, after)) {
                return SkillContentPage.error("参考文件在读取过程中发生变化，请重新搜索后再试。");
            }
            String content = page.content();
            if (SensitiveDataRedactor.containsLikelyCredential(content)) {
                content = "[该参考文档页包含疑似凭据，系统已阻止载入]";
            }
            int nextCursor = start + page.charactersRead();
            if (before.size() > DIRECT_READ_LIMIT_BYTES && page.hasMore()) {
                session.grantCursor(skill, resolved.name(), nextCursor, version);
            }
            return new SkillContentPage(header + content, nextCursor, page.hasMore());
        } catch (java.nio.charset.CharacterCodingException malformed) {
            return SkillContentPage.error("参考文件不是合法 UTF-8 文本，已拒绝读取。");
        } catch (IOException failure) {
            return SkillContentPage.error("读取参考文件失败: " + failure.getMessage());
        }
    }

    static SkillReferenceSearchResult search(
            SkillPromptRenderer.CatalogSkill skill,
            String relativePath,
            String query,
            int maxCharacters) {
        return search(skill, relativePath, query, maxCharacters, null);
    }

    static SkillReferenceSearchResult search(
            SkillPromptRenderer.CatalogSkill skill,
            String relativePath,
            String query,
            int maxCharacters,
            SkillReferenceReadSession session) {
        List<String> terms = terms(query);
        if (terms.isEmpty()) {
            return new SkillReferenceSearchResult("", false, "query 为空，请提供搜索关键词。");
        }
        List<ResolvedReference> references = references(skill, relativePath);
        if (references == null) return null;

        StringBuilder output = new StringBuilder();
        long scanned = 0;
        int matches = 0;
        boolean truncated = false;
        List<PendingGrant> grants = new ArrayList<>();
        for (ResolvedReference reference : references) {
            try {
                BasicFileAttributes before = attributes(reference.path());
                if (before.size() > SEARCH_SCAN_LIMIT_BYTES
                        || scanned + before.size() > SEARCH_SCAN_LIMIT_BYTES) {
                    truncated = true;
                    break;
                }
                scanned += before.size();
                SkillReferenceReadSession.FileVersion version = version(before);
                try (BufferedReader reader = new BufferedReader(strictReader(reference.path()))) {
                    String line;
                    int lineNumber = 0;
                    while ((line = reader.readLine()) != null) {
                        lineNumber++;
                        String normalized = line.toLowerCase(Locale.ROOT);
                        if (!terms.stream().allMatch(normalized::contains)) continue;
                        String snippet = SensitiveDataRedactor.containsLikelyCredential(line)
                                ? "[匹配行包含疑似凭据，已隐藏]"
                                : truncateSingleLine(line, SNIPPET_CODE_POINTS);
                        String hit = "[" + reference.name() + ":" + lineNumber + "] "
                                + snippet + "\n";
                        if (matches >= SEARCH_MATCH_LIMIT
                                || output.length() + hit.length() > maxCharacters) {
                            truncated = true;
                            break;
                        }
                        output.append(hit);
                        grants.add(new PendingGrant(reference.name(), lineNumber, version));
                        matches++;
                    }
                }
                BasicFileAttributes after = attributes(reference.path());
                if (!sameVersion(before, after)) {
                    return new SkillReferenceSearchResult(
                            "", false, "参考文件在搜索过程中发生变化，请重试。");
                }
                if (truncated) break;
            } catch (java.nio.charset.CharacterCodingException malformed) {
                return new SkillReferenceSearchResult(
                        "", false, reference.name() + " 不是合法 UTF-8 文本，已拒绝搜索。");
            } catch (IOException failure) {
                return new SkillReferenceSearchResult(
                        "", false, "搜索参考文件失败: " + failure.getMessage());
            }
        }
        if (session != null) {
            grants.forEach(grant -> session.grantLine(
                    skill, grant.reference(), grant.line(), grant.version()));
        }
        if (output.isEmpty()) output.append("未找到同时包含全部关键词的行。\n");
        if (truncated) output.append("[结果或扫描范围已达到上限，请增加关键词或指定 path]\n");
        return new SkillReferenceSearchResult(output.toString().stripTrailing(), truncated, "");
    }

    private static List<ResolvedReference> references(
            SkillPromptRenderer.CatalogSkill skill, String relativePath) {
        if (relativePath != null && !relativePath.isBlank()) {
            ResolvedReference reference = resolve(skill, relativePath);
            return reference == null ? null : List.of(reference);
        }
        List<ResolvedReference> references = new ArrayList<>();
        for (SkillPromptRenderer.CatalogReference manifest : skill.references()) {
            ResolvedReference reference = resolve(skill, manifest.name());
            if (reference != null) references.add(reference);
        }
        return List.copyOf(references);
    }

    private static ResolvedReference resolve(
            SkillPromptRenderer.CatalogSkill skill, String relativePath) {
        String name = SkillPromptRenderer.normalizeReferenceName(relativePath);
        if (name == null || skill.reference(name) == null || skill.directory() == null) return null;
        Path root = skill.directory().resolve(Skill.REFERENCES_DIR).toAbsolutePath().normalize();
        Path target = SkillPromptRenderer.resolveReference(root, name);
        if (target == null || Files.isSymbolicLink(target)) return null;
        return new ResolvedReference(name, target);
    }

    private static BasicFileAttributes attributes(Path path) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(
                path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile()) throw new IOException("目标不是普通文件");
        return attributes;
    }

    private static SkillReferenceReadSession.FileVersion version(
            BasicFileAttributes attributes) {
        return new SkillReferenceReadSession.FileVersion(
                attributes.size(), attributes.lastModifiedTime(), attributes.fileKey());
    }

    private static boolean sameVersion(
            BasicFileAttributes before, BasicFileAttributes after) {
        return before.size() == after.size()
                && before.lastModifiedTime().equals(after.lastModifiedTime())
                && Objects.equals(before.fileKey(), after.fileKey());
    }

    private static Reader strictReader(Path path) throws IOException {
        var decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        return Channels.newReader(Files.newByteChannel(path), decoder, -1);
    }

    private static int lineOffset(Path path, int requestedLine) throws IOException {
        if (requestedLine == 1) return 0;
        int line = 1;
        int offset = 0;
        try (Reader reader = strictReader(path)) {
            int value;
            while ((value = reader.read()) >= 0) {
                offset = Math.addExact(offset, 1);
                if (value == '\n' && ++line == requestedLine) return offset;
            }
        }
        return -1;
    }

    private static Page readCharacters(Path path, int start, int limit) throws IOException {
        StringBuilder content = new StringBuilder(limit + 1);
        try (Reader reader = strictReader(path)) {
            int skipped = 0;
            while (skipped < start) {
                int value = reader.read();
                if (value < 0) return new Page("", 0, false);
                skipped++;
            }
            while (content.length() < limit) {
                int value = reader.read();
                if (value < 0) return new Page(content.toString(), content.length(), false);
                content.append((char) value);
            }
            int extra = reader.read();
            if (content.length() > 0
                    && Character.isHighSurrogate(content.charAt(content.length() - 1))) {
                if (extra >= 0 && Character.isLowSurrogate((char) extra)) {
                    content.append((char) extra);
                    return new Page(content.toString(), content.length(), reader.read() >= 0);
                }
                content.setLength(content.length() - 1);
                return new Page(content.toString(), content.length(), true);
            }
            return new Page(content.toString(), content.length(), extra >= 0);
        }
    }

    private static List<String> terms(String query) {
        if (query == null || query.isBlank()) return List.of();
        return java.util.Arrays.stream(query.strip().toLowerCase(Locale.ROOT).split("\\s+"))
                .filter(term -> !term.isBlank()).distinct().toList();
    }

    private static String truncateSingleLine(String value, int maxCodePoints) {
        String sanitized = value.codePoints()
                .map(codePoint -> Character.isISOControl(codePoint) ? ' ' : codePoint)
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                .toString().strip();
        int count = sanitized.codePointCount(0, sanitized.length());
        return count <= maxCodePoints ? sanitized
                : sanitized.substring(0, sanitized.offsetByCodePoints(0, maxCodePoints)) + "…";
    }

    private record ResolvedReference(String name, Path path) { }

    private record PendingGrant(
            String reference, int line, SkillReferenceReadSession.FileVersion version) { }

    private record Page(String content, int charactersRead, boolean hasMore) { }
}
