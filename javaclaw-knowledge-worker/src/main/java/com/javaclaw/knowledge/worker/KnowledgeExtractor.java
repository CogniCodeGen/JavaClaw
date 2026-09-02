package com.javaclaw.knowledge.worker;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.stream.Collectors;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFGroupShape;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.apache.poi.xssf.extractor.XSSFExcelExtractor;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;

import com.javaclaw.builtin.contracts.KnowledgeContracts;
import com.javaclaw.builtin.contracts.KnowledgeWorkerProtocol;

/** 将 stdin 私有二进制帧解析为规范纯文本；不访问宿主文件、环境或网络。 */
final class KnowledgeExtractor {
    private static final String DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final String PPTX = "application/vnd.openxmlformats-officedocument.presentationml.presentation";
    private static final String PARSER_FINGERPRINT = "knowledge-worker-5.0.0/pdfbox-3.0.4/poi-5.4.1";

    KnowledgeContracts.ExtractionResult extract(KnowledgeWorkerProtocol.Request request, byte[] content)
            throws IOException {
        if (content.length != request.contentBytes()) {
            throw new IllegalArgumentException("Knowledge Worker content length differs from request");
        }
        String digest = sha256(content);
        if (!MessageDigest.isEqual(
                digest.getBytes(StandardCharsets.US_ASCII), request.digest().getBytes(StandardCharsets.US_ASCII))) {
            throw new IllegalArgumentException("Knowledge Worker content digest differs from request");
        }
        String text =
                switch (baseMediaType(request.mediaType())) {
                    case "application/pdf" -> pdf(content);
                    case DOCX -> docx(content);
                    case XLSX -> xlsx(content);
                    case PPTX -> pptx(content);
                    case "application/json", "application/xml", "application/yaml", "application/x-yaml" ->
                        utf8(content);
                    default -> text(request.mediaType(), content);
                };
        return new KnowledgeContracts.ExtractionResult(
                digest, PARSER_FINGERPRINT, truncate(normalize(text), request.maxCharacters()));
    }

    private static String text(String mediaType, byte[] content) throws CharacterCodingException {
        if (!baseMediaType(mediaType).startsWith("text/")) {
            throw new IllegalArgumentException("unsupported knowledge media type");
        }
        return utf8(content);
    }

    private static String utf8(byte[] content) throws CharacterCodingException {
        return StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(content))
                .toString();
    }

    private static String pdf(byte[] content) throws IOException {
        try (PDDocument document = Loader.loadPDF(content)) {
            return new PDFTextStripper().getText(document);
        }
    }

    private static String docx(byte[] content) throws IOException {
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(content))) {
            return new XWPFWordExtractor(document).getText();
        }
    }

    private static String xlsx(byte[] content) throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(content))) {
            return new XSSFExcelExtractor(workbook).getText();
        }
    }

    private static String pptx(byte[] content) throws IOException {
        try (XMLSlideShow slides = new XMLSlideShow(new ByteArrayInputStream(content))) {
            StringBuilder text = new StringBuilder();
            slides.getSlides().forEach(slide -> appendSlideText(slide.getShapes(), text));
            return text.toString();
        }
    }

    private static void appendSlideText(Iterable<XSLFShape> shapes, StringBuilder text) {
        for (XSLFShape shape : shapes) {
            if (shape instanceof XSLFTextShape textShape && !textShape.getText().isBlank()) {
                text.append(textShape.getText()).append('\n');
            } else if (shape instanceof XSLFGroupShape group) {
                appendSlideText(group.getShapes(), text);
            }
        }
    }

    private static String normalize(String text) {
        String lineFeed = text.replace("\r\n", "\n").replace('\r', '\n');
        return lineFeed.lines()
                .map(String::stripTrailing)
                .collect(Collectors.joining("\n"))
                .strip();
    }

    private static String truncate(String text, int maximumCharacters) {
        if (text.length() <= maximumCharacters) {
            return text;
        }
        int end = maximumCharacters;
        if (Character.isHighSurrogate(text.charAt(end - 1)) && Character.isLowSurrogate(text.charAt(end))) {
            end--;
        }
        return text.substring(0, end);
    }

    private static String baseMediaType(String mediaType) {
        return mediaType.split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }
}
