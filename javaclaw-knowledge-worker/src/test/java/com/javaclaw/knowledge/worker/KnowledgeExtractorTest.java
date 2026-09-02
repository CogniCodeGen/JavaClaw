package com.javaclaw.knowledge.worker;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

import com.javaclaw.builtin.contracts.KnowledgeContracts;
import com.javaclaw.builtin.contracts.KnowledgeWorkerProtocol;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.LengthPrefixedFraming;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnowledgeExtractorTest {
    private final CanonicalJson json = new CanonicalJson();

    @Test
    void textExtractionUsesRawBytesNormalizesLinesAndPreservesDigest() throws Exception {
        byte[] content = "first  \r\nsecond\rthird".getBytes(StandardCharsets.UTF_8);
        KnowledgeWorkerProtocol.Request request = request("text/plain", content, 1_000);

        KnowledgeContracts.ExtractionResult result = new KnowledgeExtractor().extract(request, content);

        assertEquals(digest(content), result.digest());
        assertEquals("first\nsecond\nthird", result.text());
        assertTrue(result.parserFingerprint().contains("knowledge-worker-5.0.0"));
    }

    @Test
    void workerFramingNeverReturnsOriginalAttachmentAndReportsStableFailureCode() throws Exception {
        byte[] content = new byte[] {(byte) 0xC3, (byte) 0x28};
        KnowledgeWorkerProtocol.Request request = request("text/plain", content, 100);
        ByteArrayOutputStream input = new ByteArrayOutputStream();
        LengthPrefixedFraming.write(
                input,
                json.encode(request).json().getBytes(StandardCharsets.UTF_8),
                KnowledgeWorkerProtocol.MAXIMUM_JSON_BYTES);
        LengthPrefixedFraming.write(input, content, KnowledgeWorkerProtocol.MAXIMUM_CONTENT_BYTES);
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        KnowledgeWorkerMain.run(new ByteArrayInputStream(input.toByteArray()), output);

        byte[] responseFrame = LengthPrefixedFraming.read(
                new ByteArrayInputStream(output.toByteArray()), KnowledgeWorkerProtocol.MAXIMUM_JSON_BYTES);
        KnowledgeWorkerProtocol.Response response = json.decode(
                json.parse(new String(responseFrame, StandardCharsets.UTF_8)), KnowledgeWorkerProtocol.Response.class);
        assertEquals("KNOWLEDGE_EXTRACTION_FAILED", response.error().orElseThrow());
        assertTrue(response.result().isEmpty());
    }

    @Test
    void structuredTextMediaTypesNormalizeAndTruncateAtUnicodeBoundary() throws Exception {
        byte[] jsonContent = "{\r\n  \"emoji\": \"A\ud83d\ude00B\"  \r\n}".getBytes(StandardCharsets.UTF_8);

        KnowledgeContracts.ExtractionResult jsonResult = new KnowledgeExtractor()
                .extract(request("application/json; charset=utf-8", jsonContent, 16), jsonContent);
        KnowledgeContracts.ExtractionResult yamlResult = new KnowledgeExtractor()
                .extract(
                        request("APPLICATION/YAML", "key: value".getBytes(StandardCharsets.UTF_8), 100),
                        "key: value".getBytes(StandardCharsets.UTF_8));

        assertEquals("{\n  \"emoji\": \"A", jsonResult.text());
        assertEquals("key: value", yamlResult.text());
    }

    @Test
    void extractorRejectsLengthDigestUnsupportedMediaAndMalformedUtf8() throws Exception {
        byte[] content = "payload".getBytes(StandardCharsets.UTF_8);
        KnowledgeWorkerProtocol.Request valid = request("text/plain", content, 100);
        KnowledgeWorkerProtocol.Request wrongLength =
                new KnowledgeWorkerProtocol.Request(1, "text/plain", digest(content), content.length + 1, 100);
        KnowledgeWorkerProtocol.Request wrongDigest =
                new KnowledgeWorkerProtocol.Request(1, "text/plain", "0".repeat(64), content.length, 100);

        assertThrows(IllegalArgumentException.class, () -> new KnowledgeExtractor().extract(wrongLength, content));
        assertThrows(IllegalArgumentException.class, () -> new KnowledgeExtractor().extract(wrongDigest, content));
        assertThrows(
                IllegalArgumentException.class,
                () -> new KnowledgeExtractor().extract(request("application/octet-stream", content, 100), content));
        byte[] malformed = new byte[] {(byte) 0xC3, (byte) 0x28};
        assertThrows(
                java.nio.charset.CharacterCodingException.class,
                () -> new KnowledgeExtractor().extract(request("text/markdown", malformed, 100), malformed));
    }

    @Test
    void officeAndPdfBranchesReadIsolatedInMemoryDocuments() throws Exception {
        byte[] docx = docx("word-content");
        byte[] xlsx = xlsx("sheet-content");
        byte[] pptx = pptx("slide-content");
        byte[] pdf = emptyPdf();
        KnowledgeExtractor extractor = new KnowledgeExtractor();

        assertTrue(extractor
                .extract(
                        request("application/vnd.openxmlformats-officedocument.wordprocessingml.document", docx, 1_000),
                        docx)
                .text()
                .contains("word-content"));
        assertTrue(extractor
                .extract(
                        request("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", xlsx, 1_000), xlsx)
                .text()
                .contains("sheet-content"));
        assertTrue(extractor
                .extract(
                        request(
                                "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                                pptx,
                                1_000),
                        pptx)
                .text()
                .contains("slide-content"));
        assertEquals(
                "",
                extractor.extract(request("application/pdf", pdf, 1_000), pdf).text());
    }

    @Test
    void requestHandlerReturnsStableSuccessAndFailureWithoutLeakingExceptionText() throws Exception {
        byte[] content = "safe".getBytes(StandardCharsets.UTF_8);
        KnowledgeRequestHandler handler = new KnowledgeRequestHandler(new KnowledgeExtractor());

        KnowledgeWorkerProtocol.Response success = handler.handle(request("text/plain", content, 100), content);
        KnowledgeWorkerProtocol.Response failure =
                handler.handle(request("application/octet-stream", content, 100), content);

        assertEquals("safe", success.result().orElseThrow().text());
        assertEquals("KNOWLEDGE_EXTRACTION_FAILED", failure.error().orElseThrow());
        assertThrows(NullPointerException.class, () -> new KnowledgeRequestHandler(null));
        assertThrows(NullPointerException.class, () -> handler.handle(request("text/plain", content, 100), null));
    }

    @Test
    void workerReturnsLengthMismatchBeforeExtractorAndRejectsCommandLineArguments() throws Exception {
        byte[] content = "short".getBytes(StandardCharsets.UTF_8);
        KnowledgeWorkerProtocol.Request request =
                new KnowledgeWorkerProtocol.Request(1, "text/plain", digest(content), content.length + 1, 100);
        KnowledgeWorkerProtocol.Response response = runWorker(request, content);

        assertEquals("KNOWLEDGE_CONTENT_LENGTH_MISMATCH", response.error().orElseThrow());
        assertThrows(IllegalArgumentException.class, () -> KnowledgeWorkerMain.main(new String[] {"unexpected"}));
    }

    private KnowledgeWorkerProtocol.Response runWorker(KnowledgeWorkerProtocol.Request request, byte[] content)
            throws Exception {
        ByteArrayOutputStream input = new ByteArrayOutputStream();
        LengthPrefixedFraming.write(
                input,
                json.encode(request).json().getBytes(StandardCharsets.UTF_8),
                KnowledgeWorkerProtocol.MAXIMUM_JSON_BYTES);
        LengthPrefixedFraming.write(input, content, KnowledgeWorkerProtocol.MAXIMUM_CONTENT_BYTES);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        KnowledgeWorkerMain.run(new ByteArrayInputStream(input.toByteArray()), output);
        byte[] responseFrame = LengthPrefixedFraming.read(
                new ByteArrayInputStream(output.toByteArray()), KnowledgeWorkerProtocol.MAXIMUM_JSON_BYTES);
        return json.decode(
                json.parse(new String(responseFrame, StandardCharsets.UTF_8)), KnowledgeWorkerProtocol.Response.class);
    }

    private static byte[] docx(String value) throws Exception {
        try (XWPFDocument document = new XWPFDocument();
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText(value);
            document.write(output);
            return output.toByteArray();
        }
    }

    private static byte[] xlsx(String value) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            workbook.createSheet("sheet").createRow(0).createCell(0).setCellValue(value);
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private static byte[] pptx(String value) throws Exception {
        try (XMLSlideShow slides = new XMLSlideShow();
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            slides.createSlide().createTextBox().setText(value);
            slides.write(output);
            return output.toByteArray();
        }
    }

    private static byte[] emptyPdf() throws Exception {
        try (PDDocument document = new PDDocument();
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.addPage(new PDPage());
            document.save(output);
            return output.toByteArray();
        }
    }

    private static KnowledgeWorkerProtocol.Request request(String mediaType, byte[] content, int maxCharacters)
            throws Exception {
        return new KnowledgeWorkerProtocol.Request(1, mediaType, digest(content), content.length, maxCharacters);
    }

    private static String digest(byte[] content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    }
}
