package com.javaclaw.agent.knowledge;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;

/** Content-only document extraction. It never evaluates scripts, macros or external entities. */
public final class DocumentExtractor {
    public static final int MAX_SOURCE_BYTES = 256 * 1024 * 1024;
    public static final int MAX_EXTRACTED_CHARS = 32 * 1024 * 1024;
    private static final Set<String> TEXT_TYPES = Set.of(
            "text/plain", "text/markdown", "text/csv", "application/json", "application/xml", "text/xml", "text/html");

    /**
     * 从不超过 256 MiB 的文档提取文本；按 MIME/后缀选择解析器，拒绝空内容和超长结果，不执行宏、脚本或外部实体。
     *
     * @throws Exception 格式不支持、文档损坏或资源上限超出
     */
    public String extract(byte[] bytes, String mediaType, String displayName) throws Exception {
        if (bytes == null || bytes.length > MAX_SOURCE_BYTES) {
            throw new IllegalArgumentException("knowledge source exceeds 256 MiB");
        }
        String normalized = mediaType == null
                ? ""
                : mediaType.toLowerCase(Locale.ROOT).split(";", 2)[0].strip();
        String extension = extension(displayName);
        String content;
        if ("application/pdf".equals(normalized) || "pdf".equals(extension)) {
            try (var document = Loader.loadPDF(bytes)) {
                content = new PDFTextStripper().getText(document);
            }
        } else if ("application/vnd.openxmlformats-officedocument.wordprocessingml.document".equals(normalized)
                || "docx".equals(extension)) {
            try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(bytes))) {
                StringBuilder text = new StringBuilder();
                document.getParagraphs()
                        .forEach(value -> text.append(value.getText()).append('\n'));
                document.getTables()
                        .forEach(table -> table.getRows().forEach(row -> {
                            row.getTableCells()
                                    .forEach(cell -> text.append(cell.getText()).append('\t'));
                            text.append('\n');
                        }));
                content = text.toString();
            }
        } else if ("application/xml".equals(normalized) || "text/xml".equals(normalized) || "xml".equals(extension)) {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            content = factory.newDocumentBuilder()
                    .parse(new ByteArrayInputStream(bytes))
                    .getDocumentElement()
                    .getTextContent();
        } else if ("text/html".equals(normalized) || "html".equals(extension) || "htm".equals(extension)) {
            content = new String(bytes, StandardCharsets.UTF_8)
                    .replaceAll("(?is)<script\\b[^>]*>.*?</script>", " ")
                    .replaceAll("(?is)<style\\b[^>]*>.*?</style>", " ")
                    .replaceAll("(?s)<[^>]+>", " ")
                    .replace("&nbsp;", " ")
                    .replace("&amp;", "&")
                    .replace("&lt;", "<")
                    .replace("&gt;", ">");
        } else if (TEXT_TYPES.contains(normalized)
                || Set.of("txt", "md", "markdown", "csv", "json").contains(extension)) {
            content = new String(bytes, StandardCharsets.UTF_8);
        } else {
            throw new IllegalArgumentException("unsupported knowledge media type: " + mediaType);
        }
        content = content.replace("\u0000", "").replaceAll("\\R", "\n").strip();
        if (content.length() > MAX_EXTRACTED_CHARS) {
            throw new IllegalArgumentException("extracted knowledge exceeds 32 MiB");
        }
        if (content.isBlank()) {
            throw new IllegalArgumentException("document contains no text");
        }
        return content;
    }

    private static String extension(String name) {
        if (name == null) {
            return "";
        }
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
